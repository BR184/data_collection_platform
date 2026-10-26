package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布任务的持久状态机事务。
 *
 * <p>所有持久副作用都在短事务内先锁范围行与任务行、按 {@code clock_timestamp()} 续租并核对
 * 栅栏；复制事务与切换事务分离，长复制不占用切换锁。
 */
@Service
public class FormPublicationTxService {

  private static final int LEASE_MINUTES = 15;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormVersionSchemaService schemaService;
  private final FormPublicationMigrator migrator;
  private final FormLegacyRecordMigrator legacyMigrator;
  private final FormHeadSwitchService headSwitchService;
  private final FormScopeValidationService scopeValidationService;
  private final FormVersionStructureVerifier structureVerifier;
  private final Clock clock;

  public FormPublicationTxService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormVersionSchemaService schemaService,
      FormPublicationMigrator migrator,
      FormLegacyRecordMigrator legacyMigrator,
      FormHeadSwitchService headSwitchService,
      FormScopeValidationService scopeValidationService,
      FormVersionStructureVerifier structureVerifier,
      Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.schemaService = schemaService;
    this.migrator = migrator;
    this.legacyMigrator = legacyMigrator;
    this.headSwitchService = headSwitchService;
    this.scopeValidationService = scopeValidationService;
    this.structureVerifier = structureVerifier;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ 创建

  /** 校验草稿/预览/CAS 后插入 PREPARED 任务与目标版本行。 */
  @Transactional
  public FormAdminViews.PublicationView createPublication(
      long scopeId,
      FormPublicationCreateRequest request,
      String requestKey,
      FormActorContext actor) {
    ScopeRow scope = lockScope(scopeId);
    PublicationRow existing = publicationByRequestKey(scopeId, requestKey);
    if (existing != null) {
      if (matchesFrozenRequest(existing, request)) {
        return toView(existing);
      }
      throw new FormConflictException("相同请求标识对应不同发布内容");
    }
    if ("RETIRED".equals(scope.status())) {
      throw new FormConflictException("范围已退役，不能继续发布");
    }
    DraftRow draft = requireDraft(scopeId);
    if (draft.revision() != request.draftRevision()) {
      throw new FormConflictException("草稿已变化，请刷新后重试");
    }
    PreviewRow preview = requirePreview(scopeId, request.draftRevision(), request.previewId());
    String fieldSpecHash = FormHashSupport.sha256Hex(draft.fieldSpecJson());
    if (!fieldSpecHash.equals(preview.fieldSpecHash())
        || !draft.bindingHash().equals(preview.bindingHash())) {
      throw new FormConflictException("预览与当前草稿不一致，请重新预览");
    }
    if (!preview.blockingErrors().isEmpty()) {
      throw new BizException(
          ResultCode.BAD_REQUEST, "预览存在阻断问题：" + String.join("；", preview.blockingErrors()));
    }
    if (!draft.bound()) {
      throw new BizException(ResultCode.BAD_REQUEST, "尚未完成来源与项目绑定");
    }
    if (!Objects.equals(scope.currentVersionId(), request.expectedCurrentVersionId())) {
      throw new FormConflictException("范围当前版本已变化，请刷新后重试");
    }
    String requestHash =
        requestHash(scopeId, request, preview, draft, scope.currentVersionId());
    requireNoActivePublication(scopeId);
    String kind = publicationKind(scope, draft);
    if (preview.publicationKind() != null && !kind.equals(preview.publicationKind())) {
      throw new FormConflictException("预览发布类型与当前绑定不一致，请重新预览");
    }
    long toVersionId = preview.targetVersionId();
    if (toVersionId <= 0) {
      throw new FormConflictException("预览缺少目标版本，请重新预览");
    }
    Integer versionTaken =
        jdbcTemplate.queryForObject(
            "select count(*) from form_template_versions where id = ?", Integer.class, toVersionId);
    if (versionTaken != null && versionTaken > 0) {
      throw new FormConflictException("目标版本已被占用，请重新预览");
    }
    FormTemplateSpec spec = parseSpec(draft.fieldSpecJson());
    boolean migrationTarget = !"INITIAL_EMPTY".equals(kind);
    FormVersionSchema schema = schemaService.generate(scopeId, toVersionId, spec, migrationTarget);
    if (preview.ddlHash() != null && !preview.ddlHash().equals(schema.ddlHash())) {
      throw new FormConflictException("预览 DDL 与冻结规格不一致，请重新预览");
    }
    Long fromVersionId = scope.currentVersionId();
    if ("INITIAL_MIGRATION".equals(kind)) {
      fromVersionId = insertLegacyVersion(scopeId, draft.fieldSpecJson());
    }
    ObjectNode binding =
        FormScopeBinding.create(
            objectMapper,
            scope.templateKey(),
            draft.sourceConfigId(),
            draft.sourceInstance(),
            draft.canonicalGitlabBaseUrl(),
            draft.projectIds(),
            draft.versionProjectId(),
            scope.resourceType());
    String bindingHash = FormScopeBinding.hash(objectMapper, binding);
    if (!bindingHash.equals(draft.bindingHash())) {
      throw new FormConflictException("绑定哈希与草稿不一致，请重新保存草稿");
    }
    String migrationSpecJson =
        draft.migrationSpecJson() == null
            ? defaultMigrationSpec(scope, draft, spec, bindingHash, kind, fromVersionId, toVersionId)
            : draft.migrationSpecJson();
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, migration_spec, version_status)
        values (?, ?, ?, 'RECORDS', ?, ?, ?::jsonb, ?::jsonb, 'INSTALLED')
        """,
        toVersionId,
        scopeId,
        draft.versionLabel(),
        schema.instanceTable(),
        schema.recordTable(),
        draft.fieldSpecJson(),
        migrationSpecJson);
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", 0);
    counts.put("instances", 0);
    counts.put("resources", 0);
    Long publicationId =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                """
                insert into form_template_publications(
                    scope_id, request_key, request_hash, draft_revision, preview_id, publication_kind,
                    expected_current_version_id, from_version_id, to_version_id,
                    frozen_field_spec, frozen_migration_spec, frozen_binding, binding_hash,
                    version_evidence, ddl_summary, ddl_hash, selection_counts,
                    stage, attempt_no, task_version, operator_id, operator_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb,
                        ?::jsonb, ?, ?::jsonb, 'PREPARED', 0, 0, ?, ?, ?, ?)
                returning id
                """,
                Long.class,
                scopeId,
                requestKey,
                requestHash,
                draft.revision(),
                preview.id(),
                kind,
                scope.currentVersionId(),
                fromVersionId,
                toVersionId,
                draft.fieldSpecJson(),
                migrationSpecJson,
                writeJson(binding),
                bindingHash,
                draft.versionEvidence() == null ? "{}" : writeJson(draft.versionEvidence()),
                writeJson(schema.ddlSummary()),
                schema.ddlHash(),
                writeJson(counts),
                actor.userId(),
                actor.displayName(),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)));
    return publicationView(publicationId);
  }

  // ------------------------------------------------------------------ 领取

  /**
   * 取得任务租约并设置停写栅栏。
   *
   * <p>先锁范围行等待在途写入，再设置栅栏并提交；复制事务随后取得的新快照因此覆盖全部已提交写入。
   */
  @Transactional
  public Claim claim(long publicationId, String owner) {
    PublicationRow snapshot = publication(publicationId);
    if (snapshot == null) {
      return null;
    }
    lockScope(snapshot.scopeId());
    PublicationRow publication = lockPublication(publicationId);
    if (!"PREPARED".equals(publication.stage())) {
      return null;
    }
    int fenced =
        jdbcTemplate.update(
            """
            update form_template_scopes
               set write_fenced = true, write_fence_reason = 'PUBLICATION',
                   write_fence_publication_id = ?, write_fence_token = ?,
                   write_fence_at = clock_timestamp(), updated_at = clock_timestamp()
             where id = ?
               and (write_fence_publication_id is null or write_fence_publication_id = ?)
            """,
            publicationId,
            UUID.randomUUID().toString(),
            publication.scopeId(),
            publicationId);
    if (fenced != 1) {
      throw new FormConflictException("范围已被其他发布停写");
    }
    String token =
        jdbcTemplate.queryForObject(
            "select write_fence_token from form_template_scopes where id = ?",
            String.class,
            publication.scopeId());
    int claimed =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'COPYING', lease_owner = ?,
                   lease_until = clock_timestamp() + interval '15 minutes',
                   fencing_version = fencing_version + 1, task_version = task_version + 1,
                   attempt_no = attempt_no + 1, fence_token = ?, next_attempt_at = null,
                   updated_at = clock_timestamp()
             where id = ? and stage = 'PREPARED'
            """,
            owner,
            token,
            publicationId);
    if (claimed != 1) {
      throw new FormConflictException("发布任务已被其他工作者领取");
    }
    PublicationRow claimedRow = publication(publicationId);
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", 0);
    counts.put("instances", 0);
    counts.put("resources", 0);
    jdbcTemplate.update(
        """
        insert into form_template_publication_attempts(
            publication_id, attempt_no, phase, selection_captured_at, selection_counts, started_at)
        values (?, ?, 'COPY', clock_timestamp(), ?::jsonb, clock_timestamp())
        """,
        publicationId,
        claimedRow.attemptNo(),
        writeJson(counts));
    return new Claim(
        publicationId,
        claimedRow.scopeId(),
        claimedRow.toVersionId(),
        claimedRow.fromVersionId(),
        claimedRow.kind(),
        claimedRow.expectedCurrentVersionId(),
        owner,
        claimedRow.fencingVersion(),
        token,
        claimedRow.attemptNo());
  }

  // ------------------------------------------------------------------ 复制

  /** 长事务：建表、清空、全量复制与守恒核验；失败整体回滚。 */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public FormPublicationMigrator.CopyResult copy(Claim claim) {
    // 统一锁序：范围行先于任务行。复制插入实例/记录时外键会对范围行取 KEY SHARE；
    // 若先经续租取得任务行再等待范围外键，会与“范围→任务”的领取/回收路径形成 40P01 环。
    lockScope(claim.scopeId());
    lockPublication(claim.publicationId());
    requireFence(claim.scopeId(), claim);
    renewLease(claim, "COPYING");
    PublicationRow publication = publication(claim.publicationId());
    VersionRow target = version(publication.scopeId(), publication.toVersionId());
    if (target == null || !"RECORDS".equals(target.storageKind())) {
      throw new IllegalStateException("目标版本行不存在或不合法");
    }
    VersionRow source = null;
    if (claim.fromVersionId() != null) {
      source = version(publication.scopeId(), claim.fromVersionId());
      if (source == null) {
        throw new IllegalStateException("源版本行不存在或不合法");
      }
      boolean expectedStorage =
          "INITIAL_MIGRATION".equals(publication.kind())
              ? "LEGACY".equals(source.storageKind())
              : "RECORDS".equals(source.storageKind());
      if (!expectedStorage) {
        throw new IllegalStateException("源版本行不存在或不合法");
      }
    }
    FormPublicationMigrator.CopyResult result;
    if ("INITIAL_MIGRATION".equals(publication.kind())) {
      JsonNode binding = readJson(publication.frozenBindingJson());
      List<Long> projectIds = new ArrayList<>();
      binding.path("projectIds").forEach(node -> projectIds.add(node.asLong()));
      FormLegacyRecordMigrator.CopyResult legacyResult =
          legacyMigrator.copy(
              new FormLegacyRecordMigrator.CopyRequest(
                  claim.scopeId(),
                  claim.toVersionId(),
                  claim.fromVersionId(),
                  publication.frozenFieldSpecJson(),
                  publication.ddlHash(),
                  target.instanceTable(),
                  target.recordTable(),
                  binding.path("sourceConfigId").asLong(),
                  binding.path("sourceInstance").asText(),
                  binding.path("canonicalGitlabBaseUrl").asText(),
                  publication.templateKey(),
                  projectIds,
                  publication.bindingHash()));
      result =
          new FormPublicationMigrator.CopyResult(
              legacyResult.counts(), legacyResult.checksum());
    } else {
      result =
          migrator.copy(
              new FormPublicationMigrator.CopyRequest(
                  claim.scopeId(),
                  claim.toVersionId(),
                  claim.fromVersionId(),
                  source == null ? null : source.instanceTable(),
                  source == null ? null : source.recordTable(),
                  publication.frozenFieldSpecJson(),
                  source == null ? null : source.fieldSpecJson(),
                  publication.ddlHash(),
                  target.instanceTable(),
                  target.recordTable()));
    }
    ObjectNode summary = ((ObjectNode) result.counts()).deepCopy();
    summary.set("checksum", result.checksum());
    int attemptUpdated =
        jdbcTemplate.update(
            """
            update form_template_publication_attempts
               set phase = 'VERIFY', selection_captured_at = clock_timestamp(),
                   selection_counts = ?::jsonb, copy_checksum = ?::jsonb,
                   verification_summary = ?::jsonb
             where publication_id = ? and attempt_no = ?
            """,
            writeJson(result.counts()),
            writeJson(result.checksum()),
            writeJson(summary),
            claim.publicationId(),
            claim.attemptNo());
    if (attemptUpdated != 1) {
      throw new IllegalStateException("复制证据写入失败");
    }
    int updated =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'VERIFIED', selection_captured_at = clock_timestamp(),
                   selection_counts = ?::jsonb, copy_checksum = ?::jsonb,
                   verification_summary = ?::jsonb, updated_at = clock_timestamp()
             where id = ? and stage = 'COPYING' and lease_owner = ? and fencing_version = ?
               and lease_until > clock_timestamp()
            """,
            writeJson(result.counts()),
            writeJson(result.checksum()),
            writeJson(summary),
            claim.publicationId(),
            claim.owner(),
            claim.fencingVersion());
    if (updated != 1) {
      throw new IllegalStateException("发布租约失效，复制结果不写入");
    }
    return result;
  }

  // ------------------------------------------------------------------ 切换

  /**
   * 短事务：指针 CAS、版本状态、迁移日志、head 与撤栅栏。
   *
   * <p>统一锁序：共享记录序列咨询锁 → 范围行 → 发布任务行 → head（按 id）。创建路径同样先取
   * 序列咨询锁再取范围共享锁，任一方向都不能先锁范围再等待序列锁，否则会与创建互相死锁。
   */
  @Transactional
  public void switchVersion(Claim claim) {
    lockRecordSequence();
    ScopeRow scope = lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    requireLease(publication, claim, "VERIFIED");
    if (!Objects.equals(scope.currentVersionId(), publication.expectedCurrentVersionId())) {
      throw new FormConflictException("范围当前版本已变化，停止切换");
    }
    VersionRow target = version(scope.id(), claim.toVersionId());
    if (target == null || !"INSTALLED".equals(target.versionStatus())) {
      throw new IllegalStateException("目标版本状态不允许切换");
    }
    requireFence(scope.id(), claim);
    advanceRecordSequenceLocked(target.recordTable());
    LocalDateTime now = LocalDateTime.now(clock);
    Long cutoverManifestId = null;
    if ("INITIAL_MIGRATION".equals(publication.kind())) {
      cutoverManifestId = registerCutoverManifest(publication, target, now);
    }
    headSwitchService.activate(
        scope.id(), scope.templateKey(), claim.toVersionId(), target.instanceTable(), now);
    if (cutoverManifestId != null) {
      attachCutoverManifest(scope.id(), target.instanceTable(), cutoverManifestId, now);
    }

    int versionUpdated =
        jdbcTemplate.update(
            "update form_template_versions set version_status = 'PUBLISHED', published_at = ?"
                + " where id = ? and scope_id = ? and version_status = 'INSTALLED'",
            Timestamp.valueOf(now),
            claim.toVersionId(),
            scope.id());
    if (versionUpdated != 1) {
      throw new IllegalStateException("目标版本发布状态写入失败");
    }
    if (claim.fromVersionId() != null) {
      jdbcTemplate.update(
          "update form_template_versions set version_status = 'RETIRED'"
              + " where id = ? and scope_id = ? and version_status = 'PUBLISHED'",
          claim.fromVersionId(),
          scope.id());
    }
    ObjectNode binding = (ObjectNode) readJson(publication.frozenBindingJson());
    long sourceConfigId = binding.path("sourceConfigId").asLong();
    String sourceInstance = binding.path("sourceInstance").asText();
    String canonicalUrl = binding.path("canonicalGitlabBaseUrl").asText();
    List<Long> projectIds = new ArrayList<>();
    binding.path("projectIds").forEach(node -> projectIds.add(node.asLong()));
    long versionProjectId = binding.path("versionProjectId").asLong();
    int scopeUpdated =
        jdbcTemplate.update(
            """
            update form_template_scopes
               set current_version_id = ?, status = 'ACTIVE', source_config_id = ?,
                   source_instance = ?, canonical_gitlab_base_url = ?, project_ids = ?::jsonb,
                   version_project_id = ?, binding_hash = ?,
                   binding_revision = case
                       when current_version_id is null then greatest(binding_revision, 1)
                       else binding_revision end,
                   updated_at = ?
             where id = ? and current_version_id is not distinct from ?
            """,
            claim.toVersionId(),
            sourceConfigId,
            sourceInstance,
            canonicalUrl,
            writeJson(projectIdsNode(projectIds)),
            versionProjectId,
            publication.bindingHash(),
            Timestamp.valueOf(now),
            scope.id(),
            publication.expectedCurrentVersionId());
    if (scopeUpdated != 1) {
      throw new IllegalStateException("范围当前指针切换失败");
    }
    if ("MIGRATION".equals(publication.kind())
        || "INITIAL_MIGRATION".equals(publication.kind())) {
      JsonNode summary = readJson(publication.verificationSummaryJson());
      long rows = summary == null ? 0 : summary.path("records").asLong();
      jdbcTemplate.update(
          """
          insert into form_template_migration_logs(
              scope_id, from_version_id, to_version_id, migrated_rows, verification_summary, executed_at)
          values (?, ?, ?, ?, ?::jsonb, ?)
          """,
          scope.id(),
          claim.fromVersionId(),
          claim.toVersionId(),
          rows,
          publication.verificationSummaryJson(),
          Timestamp.valueOf(now));
    }
    int published =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'PUBLISHED', published_at = ?, updated_at = ?,
                   task_version = task_version + 1
             where id = ? and stage = 'VERIFIED'
            """,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            claim.publicationId());
    if (published != 1) {
      throw new IllegalStateException("发布任务终态写入失败");
    }
    jdbcTemplate.update(
        """
        update form_template_publication_attempts set phase = 'PUBLISH', finished_at = ?
         where publication_id = ? and attempt_no = ?
        """,
        Timestamp.valueOf(now),
        claim.publicationId(),
        claim.attemptNo());
    // 最后一条写语句前按实际时刻复核租约。
    Boolean valid =
        jdbcTemplate.queryForObject(
            """
            select lease_until > clock_timestamp() from form_template_publications
             where id = ? and lease_owner = ? and fencing_version = ? and stage = 'PUBLISHED'
            """,
            Boolean.class,
            claim.publicationId(),
            claim.owner(),
            claim.fencingVersion());
    if (!Boolean.TRUE.equals(valid)) {
      throw new IllegalStateException("切换提交前租约已失效");
    }
    clearFence(scope.id(), claim);
    scopeValidationService.validateActiveScopes();
  }

  // ------------------------------------------------------------------ 失败、恢复、回收

  /**
   * 有效持有者记录失败并撤销本任务栅栏；过期或被接管的持有者零副作用。
   *
   * <p>锁内按 {@code clock_timestamp()} 复核阶段、owner、fencing 与有效租约；任务更新成功后才写
   * attempt 并撤栅栏。过期租约只能由维护回收结算，迟到的失败诊断不得改阶段或栅栏。
   *
   * @return 是否由本次调用写入失败状态
   */
  @Transactional
  public boolean fail(Claim claim, String message) {
    lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    if (!claim.owner().equals(publication.leaseOwner())
        || publication.fencingVersion() != claim.fencingVersion()
        || !List.of("PREPARED", "COPYING", "VERIFIED").contains(publication.stage())) {
      return false;
    }
    Boolean leaseValid =
        jdbcTemplate.queryForObject(
            """
            select lease_until > clock_timestamp() from form_template_publications
             where id = ? and lease_owner = ? and fencing_version = ?
            """,
            Boolean.class,
            claim.publicationId(),
            claim.owner(),
            claim.fencingVersion());
    if (!Boolean.TRUE.equals(leaseValid)) {
      return false;
    }
    int updated =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'FAILED', last_error = ?, updated_at = clock_timestamp()
             where id = ? and stage in ('PREPARED', 'COPYING', 'VERIFIED')
               and lease_owner = ? and fencing_version = ?
               and lease_until > clock_timestamp()
            """,
            truncate(message, 2000),
            claim.publicationId(),
            claim.owner(),
            claim.fencingVersion());
    if (updated != 1) {
      return false;
    }
    int attemptUpdated =
        jdbcTemplate.update(
            """
            update form_template_publication_attempts
               set phase = 'FAILED', error = ?, finished_at = clock_timestamp()
             where publication_id = ? and attempt_no = ? and phase in ('COPY', 'VERIFY')
            """,
            truncate(message, 2000),
            claim.publicationId(),
            claim.attemptNo());
    if (attemptUpdated != 1) {
      throw new IllegalStateException("发布失败证据写入失败");
    }
    clearFence(publication.scopeId(), publication, claim.fenceToken());
    return true;
  }

  /** FAILED 任务显式恢复：校验结构未漂移且无新任务后重新进入 PREPARED。 */
  @Transactional
  public FormAdminViews.PublicationView recover(long publicationId, FormActorContext actor) {
    PublicationRow publication = publication(publicationId);
    if (publication == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布任务不存在");
    }
    ScopeRow scope = lockScope(publication.scopeId());
    PublicationRow locked = lockPublication(publicationId);
    if (!"FAILED".equals(locked.stage())) {
      throw new FormConflictException("只有 FAILED 任务可以恢复");
    }
    List<PublicationRow> active =
        publicationRows(
            "p.scope_id = " + scope.id()
                + " and p.stage in ('PREPARED', 'COPYING', 'VERIFIED')");
    if (!active.isEmpty()) {
      throw new FormConflictException("范围已有新的未结束任务，旧任务只能终止");
    }
    VersionRow target = version(scope.id(), locked.toVersionId());
    if (target == null || "PUBLISHED".equals(target.versionStatus())) {
      throw new FormConflictException("目标版本不可恢复，请另开新发布");
    }
    FormTemplateSpec spec = parseSpec(locked.frozenFieldSpecJson());
    FormVersionSchema schema =
        schemaService.generate(
            scope.id(), locked.toVersionId(), spec, locked.fromVersionId() != null);
    String instance =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.instanceTable());
    String record =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.recordTable());
    if ((instance == null) != (record == null)) {
      throw new FormConflictException("目标结构不完整，不能恢复");
    }
    if (instance != null) {
      try {
        structureVerifier.verify(schema);
      } catch (IllegalStateException drift) {
        jdbcTemplate.update(
            "update form_template_publications set last_error = ?, updated_at = clock_timestamp()"
                + " where id = ?",
            "目标结构漂移，恢复终止：" + truncate(drift.getMessage(), 1000),
            publicationId);
        throw new FormConflictException("目标结构漂移，不能恢复；请另开新发布");
      }
    }
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        """
        update form_template_publications
           set stage = 'PREPARED', recovery_cycle = recovery_cycle + 1,
               fencing_version = fencing_version + 1, lease_owner = null, lease_until = null,
               fence_token = null, next_attempt_at = null, last_error = null,
               recovery_verified_at = ?, recovery_evidence = ?::jsonb, updated_at = ?
         where id = ?
        """,
        Timestamp.valueOf(now),
        writeJson(
            objectMapper
                .createObjectNode()
                .put("operatorId", actor.userId())
                .put("ddlHash", locked.ddlHash())),
        Timestamp.valueOf(now),
        publicationId);
    return publicationView(publicationId);
  }

  /** 回收租约过期的活动任务；旧工作者因 fencing 失效不能再写。 */
  @Transactional
  public int reapExpired() {
    List<PublicationRow> expired =
        publicationRows(
            "p.stage in ('COPYING', 'VERIFIED') and p.lease_until is not null"
                + " and p.lease_until < clock_timestamp() - interval '1 minute'");
    int reaped = 0;
    for (PublicationRow publication : expired) {
      lockScope(publication.scopeId());
      PublicationRow locked = lockPublication(publication.id());
      int updated =
          jdbcTemplate.update(
              """
              update form_template_publications
                 set stage = 'FAILED', last_error = '发布租约过期，任务已回收',
                     updated_at = clock_timestamp()
               where id = ? and stage in ('COPYING', 'VERIFIED')
                 and lease_until < clock_timestamp()
              """,
              locked.id());
      if (updated == 1) {
        clearFence(locked.scopeId(), locked, locked.fenceToken());
        jdbcTemplate.update(
            """
            update form_template_publication_attempts
               set phase = 'FAILED', error = '发布租约过期', finished_at = clock_timestamp()
             where publication_id = ? and attempt_no = ?
            """,
            publication.id(),
            publication.attemptNo());
        reaped++;
      }
    }
    return reaped;
  }

  // ------------------------------------------------------------------ 查询

  @Transactional(readOnly = true)
  public FormAdminViews.PublicationView publicationView(long publicationId) {
    PublicationRow row = publication(publicationId);
    if (row == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布任务不存在");
    }
    return toView(row);
  }

  @Transactional(readOnly = true)
  public Long claimablePublicationId() {
    List<Long> ids =
        jdbcTemplate.queryForList(
            """
            select id from form_template_publications
             where stage = 'PREPARED'
               and (next_attempt_at is null or next_attempt_at <= clock_timestamp())
             order by id limit 1
            """,
            Long.class);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  // ------------------------------------------------------------------ 内部实现

  private FormAdminViews.PublicationView toView(PublicationRow row) {
    return new FormAdminViews.PublicationView(
        row.id(),
        row.scopeId(),
        row.requestKey(),
        row.kind(),
        row.stage(),
        row.attemptNo(),
        row.recoveryCycle(),
        row.expectedCurrentVersionId(),
        row.fromVersionId(),
        row.toVersionId(),
        row.lastError(),
        readJson(row.selectionCountsJson()),
        readJson(row.verificationSummaryJson()),
        row.createdAt(),
        row.updatedAt(),
        row.publishedAt());
  }

  /**
   * 幂等重放只核对首次请求标识，不依赖当前草稿、预览或指针。
   *
   * <p>请求载荷由 draftRevision、previewId、expectedCurrentVersionId 与 requestKey 唯一确定；
   * 三者相同即同一次发布请求，原任务无论处于哪个阶段都返回。异载荷继续冲突。
   */
  private boolean matchesFrozenRequest(
      PublicationRow existing, FormPublicationCreateRequest request) {
    return existing.draftRevision() == request.draftRevision()
        && existing.previewId() == request.previewId()
        && Objects.equals(existing.expectedCurrentVersionId(), request.expectedCurrentVersionId());
  }

  private String requestHash(
      long scopeId,
      FormPublicationCreateRequest request,
      PreviewRow preview,
      DraftRow draft,
      Long currentVersionId) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("scopeId", scopeId);
    root.put("draftRevision", request.draftRevision());
    root.put("previewId", request.previewId());
    root.put("previewHash", preview.previewHash());
    root.put("expectedCurrentVersionId", currentVersionId == null ? null : currentVersionId.toString());
    root.put("fieldSpecHash", preview.fieldSpecHash());
    root.put("bindingHash", draft.bindingHash());
    return FormHashSupport.sha256Hex(writeJson(root));
  }

  private String defaultMigrationSpec(
      ScopeRow scope,
      DraftRow draft,
      FormTemplateSpec spec,
      String bindingHash,
      String kind,
      Long fromVersionId,
      long toVersionId) {
    if (scope.currentVersionId() == null && !"INITIAL_MIGRATION".equals(kind)) {
      return null;
    }
    ObjectNode root = objectMapper.createObjectNode();
    root.put("specVersion", 1);
    root.put("kind", kind);
    if (fromVersionId != null) {
      root.put("fromVersionId", fromVersionId);
    }
    if ("INITIAL_MIGRATION".equals(kind)) {
      root.put("toVersionId", toVersionId);
      root.put("legacyIncomplete", true);
    }
    ArrayNode mappings = root.putArray("fieldMappings");
    spec.fields()
        .forEach(
            field ->
                mappings.add(
                    field.name()
                        + "->"
                        + (field.storage() == null ? "" : field.storage().resolvedColumn())));
    root.put("bindingHash", bindingHash);
    return writeJson(root);
  }

  /**
   * 首次旧表迁移按当前草稿绑定判定，不统计其它范围的旧行。
   *
   * <p>已有当前版本一律走 MIGRATION；无当前版本时仅当本绑定来源、规范地址与项目集合内
   * 存在旧 code_review 记录才走 INITIAL_MIGRATION，否则走 INITIAL_EMPTY。
   */
  private String publicationKind(ScopeRow scope, DraftRow draft) {
    if (scope.currentVersionId() != null) {
      return "MIGRATION";
    }
    if (draft.bound()
        && legacyMigrator.hasScopedSelection(
            scope.templateKey(),
            draft.canonicalGitlabBaseUrl(),
            draft.projectIds())) {
      return "INITIAL_MIGRATION";
    }
    return "INITIAL_EMPTY";
  }

  private long insertLegacyVersion(long scopeId, String fieldSpecJson) {
    long versionId =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                "select nextval('form_template_versions_id_seq')", Long.class));
    ObjectNode legacySpec = objectMapper.createObjectNode();
    ObjectNode mappings = legacySpec.putObject("legacyColumns");
    mappings.put("reviewer", "reviewer_name");
    mappings.put("specification_score", "specification_count");
    mappings.put("logic_score", "logic_count");
    mappings.put("performance_score", "performance_count");
    mappings.put("design_score", "design_count");
    mappings.put("other_score", "other_count");
    mappings.put("remark", "supplement_note");
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, migration_spec, version_status)
        values (?, ?, null, 'LEGACY', ?, null, ?::jsonb, ?::jsonb, 'RETIRED')
        """,
        versionId,
        scopeId,
        FormLegacyRecordMigrator.LEGACY_TABLE,
        fieldSpecJson,
        writeJson(legacySpec));
    return versionId;
  }

  private void requireLease(PublicationRow publication, Claim claim, String stage) {
    if (!stage.equals(publication.stage())
        || !claim.owner().equals(publication.leaseOwner())
        || publication.fencingVersion() != claim.fencingVersion()) {
      throw new IllegalStateException("发布任务租约已失效");
    }
    Boolean valid =
        jdbcTemplate.queryForObject(
            "select lease_until > clock_timestamp() from form_template_publications where id = ?",
            Boolean.class,
            claim.publicationId());
    if (!Boolean.TRUE.equals(valid)) {
      throw new IllegalStateException("发布任务租约已过期");
    }
  }

  private void renewLease(Claim claim, String stage) {
    int updated =
        jdbcTemplate.update(
            """
            update form_template_publications
               set lease_until = clock_timestamp() + interval '15 minutes', updated_at = clock_timestamp()
             where id = ? and stage = ? and lease_owner = ? and fencing_version = ?
               and lease_until > clock_timestamp()
            """,
            claim.publicationId(),
            stage,
            claim.owner(),
            claim.fencingVersion());
    if (updated != 1) {
      throw new IllegalStateException("发布任务续租失败");
    }
  }

  private void requireFence(long scopeId, Claim claim) {
    Integer fenced =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_template_scopes
             where id = ? and write_fenced = true and write_fence_publication_id = ?
               and write_fence_token = ?
            """,
            Integer.class,
            scopeId,
            claim.publicationId(),
            claim.fenceToken());
    if (fenced == null || fenced != 1) {
      throw new IllegalStateException("发布栅栏不属于当前任务");
    }
  }

  private void clearFence(long scopeId, Claim claim) {
    int cleared =
        jdbcTemplate.update(
            """
            update form_template_scopes
               set write_fenced = false, write_fence_reason = null,
                   write_fence_publication_id = null, write_fence_token = null,
                   write_fence_at = null, updated_at = clock_timestamp()
             where id = ? and write_fenced = true
               and write_fence_publication_id = ? and write_fence_token = ?
            """,
            scopeId,
            claim.publicationId(),
            claim.fenceToken());
    if (cleared != 1) {
      throw new IllegalStateException("撤销发布栅栏失败");
    }
  }

  private void clearFence(long scopeId, PublicationRow publication, String token) {
    if (token == null) {
      return;
    }
    jdbcTemplate.update(
        """
        update form_template_scopes
           set write_fenced = false, write_fence_reason = null,
               write_fence_publication_id = null, write_fence_token = null,
               write_fence_at = null, updated_at = clock_timestamp()
         where id = ? and write_fenced = true
           and write_fence_publication_id = ? and write_fence_token = ?
        """,
        scopeId,
        publication.id(),
        token);
  }

  /** 取得共享记录序列的事务级咨询锁；所有需要该锁的路径都必须先于范围锁调用。 */
  private void lockRecordSequence() {
    jdbcTemplate.execute(
        "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)");
  }

  private void advanceRecordSequenceLocked(String targetRecordTable) {
    List<String> tables = new ArrayList<>();
    tables.add("collect_form_records");
    List<String> registered =
        jdbcTemplate.queryForList(
            "select record_table from form_template_versions where record_table is not null",
            String.class);
    for (String table : registered) {
      if (table.matches("form_s[0-9]+_v[0-9]+_records")
          && jdbcTemplate.queryForObject("select to_regclass(?)", String.class, table) != null
          && !tables.contains(table)) {
        tables.add(table);
      }
    }
    if (targetRecordTable != null
        && jdbcTemplate.queryForObject(
                "select to_regclass(?)", String.class, targetRecordTable)
            != null
        && !tables.contains(targetRecordTable)) {
      tables.add(targetRecordTable);
    }
    List<String> existing = new ArrayList<>();
    for (String table : tables) {
      if (jdbcTemplate.queryForObject("select to_regclass(?)", String.class, table) != null) {
        existing.add(table);
      }
    }
    if (existing.isEmpty()) {
      return;
    }
    String union =
        String.join(
            " union all ",
            existing.stream().map(table -> "select id from \"" + table + "\"").toList());
    Long highWater =
        jdbcTemplate.queryForObject(
            "select coalesce(max(id), 0) from (" + union + ") allocated_records", Long.class);
    Map<String, Object> state =
        jdbcTemplate.queryForMap("select last_value, is_called from form_record_id_seq");
    long lastValue = ((Number) state.get("last_value")).longValue();
    boolean called = Boolean.TRUE.equals(state.get("is_called"));
    long next = called ? lastValue + 1 : lastValue;
    if (highWater != null && next <= highWater) {
      jdbcTemplate.execute("select setval('form_record_id_seq', " + highWater + ", true)");
    }
  }

  /** 首次旧表迁移的权威交接清单；切换事务内与指针一起提交。 */
  private long registerCutoverManifest(
      PublicationRow publication, VersionRow target, LocalDateTime now) {
    JsonNode binding = readJson(publication.frozenBindingJson());
    List<Long> projectIds = new ArrayList<>();
    binding.path("projectIds").forEach(node -> projectIds.add(node.asLong()));
    ObjectNode resourceRule = objectMapper.createObjectNode();
    resourceRule.put("type", "EXPLICIT_SELECTION");
    resourceRule.put("reason", "INITIAL_MIGRATION");
    long manifestId =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                """
                insert into form_authority_cutover_manifests(
                    manifest_key, source_config_id, source_instance, canonical_gitlab_base_url,
                    template_key, scope_id, effective_at, project_ids, resource_rule, rule_version,
                    status, operator_id, operator_name, created_at, applied_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'initial-migration-v1',
                        'APPLIED', ?, ?, ?, ?)
                returning id
                """,
                Long.class,
                "initial-" + publication.scopeId(),
                binding.path("sourceConfigId").asLong(),
                binding.path("sourceInstance").asText(),
                binding.path("canonicalGitlabBaseUrl").asText(),
                publication.templateKey(),
                publication.scopeId(),
                Timestamp.valueOf(now),
                writeJson(projectIdsNode(projectIds)),
                writeJson(resourceRule),
                publication.operatorId(),
                publication.operatorName(),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)));
    jdbcTemplate.update(
        """
        insert into form_authority_cutover_items(
            manifest_id, project_id, resource_type, resource_id, authority, created_at)
        select ?, i.project_id, i.resource_type, i.resource_id, 'FORM', ?
          from %s i
        """
            .formatted(target.instanceTable()),
        manifestId,
        Timestamp.valueOf(now));
    return manifestId;
  }

  private void attachCutoverManifest(
      long scopeId, String instanceTable, long manifestId, LocalDateTime now) {
    jdbcTemplate.update(
        """
        update form_resource_authorities a
           set cutover_manifest_id = ?, effective_at = ?, updated_at = ?
          from %s i
         where a.source_config_id = i.source_config_id
           and a.project_id = i.project_id and a.resource_type = i.resource_type
           and a.resource_id = i.resource_id and a.template_key = i.template_key
           and a.scope_id = ?
        """
            .formatted(instanceTable),
        manifestId,
        Timestamp.valueOf(now),
        Timestamp.valueOf(now),
        scopeId);
  }

  private ScopeRow lockScope(long scopeId) {    List<ScopeRow> rows =
        jdbcTemplate.query(
            """
            select id, template_key, status, current_version_id, resource_type, binding_hash
              from form_template_scopes where id = ? for update
            """,
            (rs, rowNum) ->
                new ScopeRow(
                    rs.getLong("id"),
                    rs.getString("template_key"),
                    rs.getString("status"),
                    rs.getObject("current_version_id", Long.class),
                    rs.getString("resource_type"),
                    rs.getString("binding_hash")),
            scopeId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    return rows.getFirst();
  }

  private PublicationRow lockPublication(long publicationId) {
    List<PublicationRow> rows =
        jdbcTemplate.query(
            publicationSelect() + " where p.id = ? order by p.id for update",
            this::mapPublication,
            publicationId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布任务不存在");
    }
    return rows.getFirst();
  }

  private PublicationRow publication(long publicationId) {
    List<PublicationRow> rows =
        jdbcTemplate.query(
            publicationSelect() + " where p.id = ? order by p.id",
            this::mapPublication,
            publicationId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private PublicationRow publicationByRequestKey(long scopeId, String requestKey) {
    List<PublicationRow> rows =
        jdbcTemplate.query(
            publicationSelect() + " where p.scope_id = ? and p.request_key = ? order by p.id",
            this::mapPublication,
            scopeId,
            requestKey);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<PublicationRow> publicationRows(String condition, Object... args) {
    return jdbcTemplate.query(
        publicationSelect() + " where " + condition + " order by p.id",
        this::mapPublication,
        args);
  }

  private String publicationSelect() {
    return """
        select p.id, p.scope_id, p.request_key, p.request_hash, p.draft_revision, p.preview_id,
               p.publication_kind, p.stage,
               p.attempt_no, p.recovery_cycle, p.expected_current_version_id, p.from_version_id,
               p.to_version_id, p.frozen_field_spec::text as frozen_field_spec,
               p.frozen_binding::text as frozen_binding, p.binding_hash, p.ddl_hash,
               p.selection_counts::text as selection_counts,
               p.verification_summary::text as verification_summary,
               p.lease_owner, p.fencing_version, p.fence_token, p.last_error,
               p.operator_id, p.operator_name,
               p.created_at, p.updated_at, p.published_at, s.template_key
          from form_template_publications p
          join form_template_scopes s on s.id = p.scope_id
        """;
  }

  private PublicationRow mapPublication(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new PublicationRow(
        rs.getLong("id"),
        rs.getLong("scope_id"),
        rs.getString("request_key"),
        rs.getString("request_hash"),
        rs.getLong("draft_revision"),
        rs.getLong("preview_id"),
        rs.getString("publication_kind"),
        rs.getString("stage"),
        rs.getInt("attempt_no"),
        rs.getLong("recovery_cycle"),
        rs.getObject("expected_current_version_id", Long.class),
        rs.getObject("from_version_id", Long.class),
        rs.getLong("to_version_id"),
        rs.getString("frozen_field_spec"),
        rs.getString("frozen_binding"),
        rs.getString("binding_hash"),
        rs.getString("ddl_hash"),
        rs.getString("selection_counts"),
        rs.getString("verification_summary"),
        rs.getString("lease_owner"),
        rs.getLong("fencing_version"),
        rs.getString("fence_token"),
        rs.getString("last_error"),
        rs.getString("operator_id"),
        rs.getString("operator_name"),
        rs.getTimestamp("created_at").toLocalDateTime(),
        rs.getTimestamp("updated_at").toLocalDateTime(),
        rs.getTimestamp("published_at") == null
            ? null
            : rs.getTimestamp("published_at").toLocalDateTime(),
        rs.getString("template_key"));
  }

  private DraftRow requireDraft(long scopeId) {
    List<DraftRow> rows =
        jdbcTemplate.query(
            """
            select draft_revision, field_spec::text as field_spec, source_config_id,
                   source_instance, canonical_gitlab_base_url, project_ids::text as project_ids,
                   version_project_id, version_label, version_evidence::text as version_evidence,
                   binding_hash, migration_spec::text as migration_spec
              from form_template_scope_drafts where scope_id = ? for update
            """,
            (rs, rowNum) -> {
              List<Long> projects = new ArrayList<>();
              JsonNode nodes = readJson(rs.getString("project_ids"));
              if (nodes != null && nodes.isArray()) {
                nodes.forEach(node -> projects.add(node.asLong()));
              }
              return new DraftRow(
                  rs.getLong("draft_revision"),
                  rs.getString("field_spec"),
                  rs.getObject("source_config_id", Long.class),
                  rs.getString("source_instance"),
                  rs.getString("canonical_gitlab_base_url"),
                  projects,
                  rs.getObject("version_project_id", Long.class),
                  rs.getString("version_label"),
                  readJson(rs.getString("version_evidence")),
                  rs.getString("binding_hash"),
                  rs.getString("migration_spec"));
            },
            scopeId);
    if (rows.isEmpty()) {
      throw new FormConflictException("范围草稿不存在，请重新创建范围");
    }
    return rows.getFirst();
  }

  private PreviewRow requirePreview(long scopeId, long draftRevision, long previewId) {
    List<PreviewRow> rows =
        jdbcTemplate.query(
            """
            select id, draft_revision, field_spec_hash, binding_hash, preview_hash,
                   preview_json::text as preview_json, blocking_errors::text as blocking_errors
              from form_template_preview_evidence
             where id = ? and scope_id = ? and draft_revision = ?
            """,
            (rs, rowNum) -> {
              List<String> errors = new ArrayList<>();
              JsonNode blocking = readJson(rs.getString("blocking_errors"));
              if (blocking != null && blocking.isArray()) {
                blocking.forEach(node -> errors.add(node.asText()));
              }
              JsonNode preview = readJson(rs.getString("preview_json"));
              return new PreviewRow(
                  rs.getLong("id"),
                  rs.getLong("draft_revision"),
                  rs.getString("field_spec_hash"),
                  rs.getString("binding_hash"),
                  rs.getString("preview_hash"),
                  preview == null ? 0 : preview.path("targetVersionId").asLong(),
                  preview == null ? null : preview.path("publicationKind").asText(null),
                  preview == null ? null : preview.path("ddlHash").asText(null),
                  errors);
            },
            previewId,
            scopeId,
            draftRevision);
    if (rows.isEmpty()) {
      throw new FormConflictException("预览不存在或与草稿修订不一致，请重新预览");
    }
    return rows.getFirst();
  }

  private VersionRow version(long scopeId, long versionId) {
    List<VersionRow> rows =
        jdbcTemplate.query(
            """
            select id, instance_table, record_table, storage_kind, version_status,
                   field_spec::text as field_spec
              from form_template_versions where scope_id = ? and id = ?
            """,
            (rs, rowNum) ->
                new VersionRow(
                    rs.getLong("id"),
                    rs.getString("instance_table"),
                    rs.getString("record_table"),
                    rs.getString("storage_kind"),
                    rs.getString("version_status"),
                    rs.getString("field_spec")),
            scopeId,
            versionId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void requireNoActivePublication(long scopeId) {
    List<PublicationRow> active =
        publicationRows(
            "p.scope_id = " + scopeId + " and p.stage in ('PREPARED', 'COPYING', 'VERIFIED')");
    if (!active.isEmpty()) {
      throw new FormConflictException("范围已有未结束的发布任务");
    }
  }

  private FormTemplateSpec parseSpec(String raw) {
    try {
      return objectMapper.readValue(raw, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException("冻结结构无法解析", error);
    }
  }

  private JsonNode readJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (Exception error) {
      return null;
    }
  }

  private ArrayNode projectIdsNode(List<Long> projectIds) {
    ArrayNode node = objectMapper.createArrayNode();
    projectIds.forEach(node::add);
    return node;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to serialize publication payload", error);
    }
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }

  /** 任务领取凭据；fencing 失效后任何副作用都不再命中。 */
  public record Claim(
      long publicationId,
      long scopeId,
      long toVersionId,
      Long fromVersionId,
      String kind,
      Long expectedCurrentVersionId,
      String owner,
      long fencingVersion,
      String fenceToken,
      int attemptNo) {}

  private record ScopeRow(
      long id,
      String templateKey,
      String status,
      Long currentVersionId,
      String resourceType,
      String bindingHash) {}

  private record PublicationRow(
      long id,
      long scopeId,
      String requestKey,
      String requestHash,
      long draftRevision,
      long previewId,
      String kind,
      String stage,
      int attemptNo,
      long recoveryCycle,
      Long expectedCurrentVersionId,
      Long fromVersionId,
      long toVersionId,
      String frozenFieldSpecJson,
      String frozenBindingJson,
      String bindingHash,
      String ddlHash,
      String selectionCountsJson,
      String verificationSummaryJson,
      String leaseOwner,
      long fencingVersion,
      String fenceToken,
      String lastError,
      String operatorId,
      String operatorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime publishedAt,
      String templateKey) {}

  private record DraftRow(
      long revision,
      String fieldSpecJson,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId,
      String versionLabel,
      JsonNode versionEvidence,
      String bindingHash,
      String migrationSpecJson) {

    boolean bound() {
      return sourceConfigId != null
          && sourceInstance != null
          && canonicalGitlabBaseUrl != null
          && versionProjectId != null
          && projectIds != null
          && !projectIds.isEmpty()
          && bindingHash != null
          && !FormTemplateAdminService.UNBOUND_BINDING_HASH.equals(bindingHash);
    }
  }

  private record PreviewRow(
      long id,
      long draftRevision,
      String fieldSpecHash,
      String bindingHash,
      String previewHash,
      long targetVersionId,
      String publicationKind,
      String ddlHash,
      List<String> blockingErrors) {}

  private record VersionRow(
      long id,
      String instanceTable,
      String recordTable,
      String storageKind,
      String versionStatus,
      String fieldSpecJson) {}
}
