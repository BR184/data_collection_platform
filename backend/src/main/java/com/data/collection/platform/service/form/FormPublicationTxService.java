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

  private static final int LEASE_MINUTES = 5;
  private static final int PROTOCOL_V2 = 2;
  private static final List<String> V2_ACTIVE_STAGES =
      List.of("VALIDATING", "BUILDING", "SWITCHING");
  private static final List<String> V1_ACTIVE_STAGES =
      List.of("PREPARED", "COPYING", "VERIFIED");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormVersionSchemaService schemaService;
  private final FormHeadSwitchService headSwitchService;
  private final FormScopeValidationService scopeValidationService;
  private final FormVersionStructureVerifier structureVerifier;
  private final Clock clock;

  public FormPublicationTxService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormVersionSchemaService schemaService,
      FormHeadSwitchService headSwitchService,
      FormScopeValidationService scopeValidationService,
      FormVersionStructureVerifier structureVerifier,
      Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.schemaService = schemaService;
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
    String kind = scope.currentVersionId() == null ? "INITIAL_EMPTY" : "MIGRATION";
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
    FormVersionSchema schema = schemaService.generate(scopeId, toVersionId, spec, false);
    if (preview.ddlHash() != null && !preview.ddlHash().equals(schema.ddlHash())) {
      throw new FormConflictException("预览 DDL 与冻结规格不一致，请重新预览");
    }
    Long fromVersionId = scope.currentVersionId();
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
    String migrationSpecJson = draft.migrationSpecJson();
    ObjectNode originBinding = canonicalScopeBinding(scope);
    String originBindingHash =
        originBinding == null
            ? FormTemplateAdminService.UNBOUND_BINDING_HASH
            : FormScopeBinding.hash(objectMapper, originBinding);
    if (scope.currentVersionId() != null
        && (originBinding == null || !originBindingHash.equals(scope.bindingHash()))) {
      throw new FormConflictException("范围绑定事实与登记不一致，停止发布");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, migration_spec, version_status, record_source_role, instance_mode)
        values (?, ?, ?, 'RECORDS', ?, ?, ?::jsonb, ?::jsonb, 'INSTALLED', 'TARGET', 'MATERIALIZED')
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
                    protocol_version, frozen_source_set_revision, frozen_source_set_hash,
                    origin_binding, origin_binding_revision, origin_binding_hash,
                    stage, attempt_no, task_version, operator_id, operator_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb,
                        ?::jsonb, ?, ?::jsonb, 2, ?, ?, ?::jsonb, ?, ?, 'VALIDATING', 0, 0, ?, ?, ?, ?)
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
                scope.sourceSetRevision(),
                scope.sourceSetHash(),
                originBinding == null ? null : writeJson(originBinding),
                scope.bindingRevision(),
                originBindingHash,
                actor.userId(),
                actor.displayName(),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)));
    return publicationView(publicationId);
  }

  // ------------------------------------------------------------------ 领取与接管

  /**
   * 取得 v2 任务租约：VALIDATING 进入 BUILDING，过期 BUILDING/SWITCHING 由新持有者接管。
   *
   * <p>统一锁序：记录序列咨询锁 → 范围行 → 任务行。BUILDING 不设范围栅栏；SWITCHING 只在
   * 本任务持有范围栅栏时生成新 token 接管，外来栅栏直接失败且不清除。
   */
  @Transactional
  public Claim claim(long publicationId, String owner) {
    PublicationRow snapshot = publication(publicationId);
    if (snapshot == null
        || snapshot.protocolVersion() != PROTOCOL_V2
        || !V2_ACTIVE_STAGES.contains(snapshot.stage())) {
      return null;
    }
    lockRecordSequence();
    lockScope(snapshot.scopeId());
    PublicationRow publication = lockPublication(publicationId);
    if (publication.protocolVersion() != PROTOCOL_V2) {
      return null;
    }
    Boolean expired =
        jdbcTemplate.queryForObject(
            "select lease_until is null or lease_until <= clock_timestamp()"
                + " from form_template_publications where id = ?",
            Boolean.class,
            publicationId);
    boolean leaseExpired = Boolean.TRUE.equals(expired);
    String stage = publication.stage();
    String phase;
    if ("VALIDATING".equals(stage)) {
      if (publication.leaseOwner() != null && !leaseExpired) {
        return null;
      }
      int claimed =
          jdbcTemplate.update(
              """
              update form_template_publications
                 set stage = 'BUILDING', lease_owner = ?,
                     lease_until = clock_timestamp() + interval '5 minutes',
                     fencing_version = fencing_version + 1, task_version = task_version + 1,
                     attempt_no = attempt_no + 1, fence_token = null, next_attempt_at = null,
                     updated_at = clock_timestamp()
               where id = ? and protocol_version = 2 and stage = 'VALIDATING'
              """,
              owner,
              publicationId);
      if (claimed != 1) {
        return null;
      }
      stage = "BUILDING";
      phase = "VALIDATE";
    } else if ("BUILDING".equals(stage)) {
      if (!leaseExpired) {
        return null;
      }
      int claimed =
          jdbcTemplate.update(
              """
              update form_template_publications
                 set lease_owner = ?,
                     lease_until = clock_timestamp() + interval '5 minutes',
                     fencing_version = fencing_version + 1, task_version = task_version + 1,
                     attempt_no = attempt_no + 1, next_attempt_at = null,
                     recovery_evidence = coalesce(recovery_evidence, '{}'::jsonb)
                       || jsonb_build_object('buildTakeoverFrom', lease_owner,
                                             'buildTakeoverAt', clock_timestamp()),
                     updated_at = clock_timestamp()
               where id = ? and protocol_version = 2 and stage = 'BUILDING'
                 and (lease_until is null or lease_until <= clock_timestamp())
              """,
              owner,
              publicationId);
      if (claimed != 1) {
        return null;
      }
      phase = "BUILD_TAKEOVER";
    } else if ("SWITCHING".equals(stage)) {
      if (!leaseExpired) {
        return null;
      }
      ScopeFenceRow fence = scopeFence(publication.scopeId());
      if (fence == null
          || !fence.fenced()
          || fence.publicationId() == null
          || fence.publicationId() != publicationId
          || !Objects.equals(fence.token(), publication.fenceToken())) {
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'FAILED', last_error = '范围栅栏不属于本任务，停止接管',
                   updated_at = clock_timestamp()
             where id = ? and protocol_version = 2 and stage = 'SWITCHING'
            """,
            publicationId);
        return null;
      }
      String token = UUID.randomUUID().toString();
      int refenced =
          jdbcTemplate.update(
              """
              update form_template_scopes
                 set write_fence_token = ?, write_fence_at = clock_timestamp(),
                     updated_at = clock_timestamp()
               where id = ? and write_fenced = true and write_fence_publication_id = ?
                 and write_fence_token = ?
              """,
              token,
              publication.scopeId(),
              publicationId,
              fence.token());
      if (refenced != 1) {
        return null;
      }
      int claimed =
          jdbcTemplate.update(
              """
              update form_template_publications
                 set lease_owner = ?,
                     lease_until = clock_timestamp() + interval '5 minutes',
                     fencing_version = fencing_version + 1, task_version = task_version + 1,
                     attempt_no = attempt_no + 1, fence_token = ?, next_attempt_at = null,
                     recovery_evidence = coalesce(recovery_evidence, '{}'::jsonb)
                       || jsonb_build_object('switchTakeoverFrom', lease_owner,
                                             'switchTakeoverAt', clock_timestamp()),
                     updated_at = clock_timestamp()
               where id = ? and protocol_version = 2 and stage = 'SWITCHING'
                 and (lease_until is null or lease_until <= clock_timestamp())
              """,
              owner,
              token,
              publicationId);
      if (claimed != 1) {
        return null;
      }
      phase = "SWITCH_TAKEOVER";
    } else {
      return null;
    }
    PublicationRow claimedRow = publication(publicationId);
    jdbcTemplate.update(
        """
        insert into form_template_publication_attempts(
            publication_id, attempt_no, phase, selection_captured_at, selection_counts,
            started_at, protocol_version)
        values (?, ?, ?, clock_timestamp(), ?::jsonb, clock_timestamp(), 2)
        """,
        publicationId,
        claimedRow.attemptNo(),
        phase,
        writeJson(emptyCounts()));
    return new Claim(
        publicationId,
        claimedRow.scopeId(),
        claimedRow.toVersionId(),
        claimedRow.fromVersionId(),
        claimedRow.kind(),
        claimedRow.expectedCurrentVersionId(),
        owner,
        claimedRow.fencingVersion(),
        claimedRow.fenceToken(),
        claimedRow.attemptNo(),
        claimedRow.stage());
  }

  // ------------------------------------------------------------------ 空结构构建

  /**
   * 短事务：只为目标版本创建空表并核验完整结构；不复制、不清空、不改写任何旧表。
   *
   * <p>表不存在则建；两表缺一、已有行或结构漂移均拒绝，交由失败收口。构建期间不设范围栅栏，
   * 当前版本记录照常写入。
   */
  @Transactional
  public void build(Claim claim) {
    lockRecordSequence();
    ScopeRow scope = lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    requireLease(publication, claim, "BUILDING");
    requireScopePublicationState(publication, scope);
    renewLease(claim, "BUILDING");
    VersionRow target = version(publication.scopeId(), publication.toVersionId());
    if (target == null || !"RECORDS".equals(target.storageKind())) {
      throw new IllegalStateException("目标版本行不存在或不合法");
    }
    FormTemplateSpec spec = parseSpec(publication.frozenFieldSpecJson());
    FormVersionSchema schema =
        schemaService.generate(publication.scopeId(), publication.toVersionId(), spec, false);
    if (!schema.ddlHash().equals(publication.ddlHash())) {
      throw new FormConflictException("冻结规格与目标 DDL 不一致，停止构建");
    }
    boolean instanceExists =
        jdbcTemplate.queryForObject(
                "select to_regclass(?)", String.class, schema.instanceTable())
            != null;
    boolean recordExists =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.recordTable())
            != null;
    if (instanceExists != recordExists) {
      throw new FormConflictException("目标结构不完整，停止构建");
    }
    if (!instanceExists) {
      schema.statements().forEach(jdbcTemplate::execute);
    } else {
      Long rows =
          jdbcTemplate.queryForObject(
              "select count(*) from \"%s\"".formatted(schema.recordTable()), Long.class);
      if (rows != null && rows > 0) {
        throw new FormConflictException("目标结构已存在记录，停止空结构发布");
      }
      Long instances =
          jdbcTemplate.queryForObject(
              "select count(*) from \"%s\"".formatted(schema.instanceTable()), Long.class);
      if (instances != null && instances > 0) {
        throw new FormConflictException("目标结构已存在实例，停止空结构发布");
      }
    }
    structureVerifier.verify(schema);
    // DDL 副作用后、提交前再次按实际时刻复核租约与持有者。
    requireLease(publication(claim.publicationId()), claim, "BUILDING");
    ObjectNode summary = emptyCounts();
    summary.put("ddlHash", schema.ddlHash());
    jdbcTemplate.update(
        """
        update form_template_publication_attempts
           set phase = 'BUILD', selection_captured_at = clock_timestamp(),
               selection_counts = ?::jsonb, verification_summary = ?::jsonb
         where publication_id = ? and attempt_no = ?
        """,
        writeJson(emptyCounts()),
        writeJson(summary),
        claim.publicationId(),
        claim.attemptNo());
    jdbcTemplate.update(
        """
        update form_template_publications
           set selection_captured_at = clock_timestamp(), selection_counts = ?::jsonb,
               verification_summary = ?::jsonb, updated_at = clock_timestamp()
         where id = ? and stage = 'BUILDING' and lease_owner = ? and fencing_version = ?
           and lease_until > clock_timestamp()
        """,
        writeJson(emptyCounts()),
        writeJson(summary),
        claim.publicationId(),
        claim.owner(),
        claim.fencingVersion());
  }

  // ------------------------------------------------------------------ 短切换

  /**
   * 短事务：开始停写并进入 SWITCHING。
   *
   * <p>统一锁序：记录序列咨询锁 → 范围行 → 任务行。只在此刻到 apply 提交之间设范围栅栏；
   * 构建期不停写。集合、指针、绑定或目标结构任一漂移即拒绝，零切换。
   */
  @Transactional
  public void enterSwitch(Claim claim) {
    lockRecordSequence();
    ScopeRow scope = lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    requireLease(publication, claim, "BUILDING");
    requireScopePublicationState(publication, scope);
    if (!Objects.equals(scope.sourceSetRevision(), publication.frozenSourceSetRevision())
        || !Objects.equals(scope.sourceSetHash(), publication.frozenSourceSetHash())) {
      throw new FormConflictException("源集合已变化，停止切换");
    }
    if (scope.writeFenced()) {
      throw new FormConflictException("范围已被其他发布停写");
    }
    VersionRow target = version(scope.id(), claim.toVersionId());
    if (target == null || !"INSTALLED".equals(target.versionStatus())) {
      throw new IllegalStateException("目标版本状态不允许切换");
    }
    requireEmptyTarget(publication, target);
    String token = UUID.randomUUID().toString();
    int fenced =
        jdbcTemplate.update(
            """
            update form_template_scopes
               set write_fenced = true, write_fence_reason = 'PUBLICATION',
                   write_fence_publication_id = ?, write_fence_token = ?,
                   write_fence_at = clock_timestamp(), updated_at = clock_timestamp()
             where id = ? and write_fenced = false
            """,
            claim.publicationId(),
            token,
            scope.id());
    if (fenced != 1) {
      throw new FormConflictException("范围已被其他发布停写");
    }
    int switched =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'SWITCHING', fence_token = ?, updated_at = clock_timestamp()
             where id = ? and protocol_version = 2 and stage = 'BUILDING'
               and lease_owner = ? and fencing_version = ?
               and lease_until > clock_timestamp()
            """,
            token,
            claim.publicationId(),
            claim.owner(),
            claim.fencingVersion());
    if (switched != 1) {
      throw new IllegalStateException("发布任务进入切换失败");
    }
    jdbcTemplate.update(
        """
        update form_template_publication_attempts set phase = 'SWITCH_ENTER'
         where publication_id = ? and attempt_no = ?
        """,
        claim.publicationId(),
        claim.attemptNo());
  }

  /**
   * 短事务：原子切换指针、版本角色/状态、源集合、目录事件与 head，并清除本任务栅栏。
   *
   * <p>所有冻结证据在锁内重读核对；任一失配整体回滚，旧记录与目录承载位置不变。
   */
  @Transactional
  public void applySwitch(Claim claim) {
    lockRecordSequence();
    ScopeRow scope = lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    requireLease(publication, claim, "SWITCHING");
    requireFence(scope.id(), claim.publicationId(), publication.fenceToken());
    requireScopePublicationState(publication, scope);
    if (!Objects.equals(scope.sourceSetRevision(), publication.frozenSourceSetRevision())
        || !Objects.equals(scope.sourceSetHash(), publication.frozenSourceSetHash())) {
      throw new FormConflictException("源集合已变化，停止切换");
    }
    VersionRow target = version(scope.id(), claim.toVersionId());
    if (target == null || !"INSTALLED".equals(target.versionStatus())) {
      throw new IllegalStateException("目标版本状态不允许切换");
    }
    requireEmptyTarget(publication, target);
    advanceRecordSequenceLocked(target.recordTable());
    LocalDateTime now = LocalDateTime.now(clock);
    List<String> instanceTables = new ArrayList<>();
    instanceTables.add(target.instanceTable());
    if (claim.fromVersionId() != null) {
      VersionRow from = version(scope.id(), claim.fromVersionId());
      if (from != null && from.instanceTable() != null) {
        instanceTables.add(from.instanceTable());
      }
    }
    headSwitchService.activate(
        scope.id(), scope.templateKey(), claim.toVersionId(), instanceTables, now);

    int versionUpdated =
        jdbcTemplate.update(
            """
            update form_template_versions
               set version_status = 'PUBLISHED', record_source_role = 'AUTHORITATIVE',
                   published_at = ?
             where id = ? and scope_id = ? and version_status = 'INSTALLED'
            """,
            Timestamp.valueOf(now),
            claim.toVersionId(),
            scope.id());
    if (versionUpdated != 1) {
      throw new IllegalStateException("目标版本发布状态写入失败");
    }
    if (claim.fromVersionId() != null) {
      jdbcTemplate.update(
          "update form_template_versions set version_status = 'RETIRED'"
              + " where id = ? and scope_id = ? and version_status = 'PUBLISHED'"
              + " and record_source_role = 'AUTHORITATIVE'",
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
    List<ScopeRow> updatedScopes =
        jdbcTemplate.query(
            """
            update form_template_scopes
               set current_version_id = ?, status = 'ACTIVE', source_config_id = ?,
                   source_instance = ?, canonical_gitlab_base_url = ?, project_ids = ?::jsonb,
                   version_project_id = ?, binding_hash = ?,
                   binding_revision = case
                       when current_version_id is null then greatest(binding_revision, 1)
                       else binding_revision end,
                   source_set_revision = source_set_revision + 1,
                   source_set_hash = form_source_set_hash(id),
                   updated_at = ?
             where id = ? and current_version_id is not distinct from ?
            returning id, template_key, status, current_version_id, resource_type, binding_hash,
                      source_set_revision, source_set_hash, write_fenced, binding_revision,
                      source_config_id, source_instance, canonical_gitlab_base_url,
                      project_ids::text as project_ids, version_project_id
            """,
            (rs, rowNum) ->
                new ScopeRow(
                    rs.getLong("id"),
                    rs.getString("template_key"),
                    rs.getString("status"),
                    rs.getObject("current_version_id", Long.class),
                    rs.getString("resource_type"),
                    rs.getString("binding_hash"),
                    rs.getLong("source_set_revision"),
                    rs.getString("source_set_hash"),
                    rs.getBoolean("write_fenced"),
                    rs.getLong("binding_revision"),
                    rs.getObject("source_config_id", Long.class),
                    rs.getString("source_instance"),
                    rs.getString("canonical_gitlab_base_url"),
                    parseProjects(rs.getString("project_ids")),
                    rs.getObject("version_project_id", Long.class)),
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
    if (updatedScopes.size() != 1) {
      throw new IllegalStateException("范围当前指针切换失败");
    }
    ScopeRow switchedScope = updatedScopes.getFirst();
    insertSwitchEvents(publication, switchedScope);
    jdbcTemplate.update(
        """
        update form_change_heads
           set pending_source_set_revision = ?, pending_source_set_hash = ?,
               status = 'PENDING', updated_at = ?
         where scope_id = ? and template_key = ? and pending_storage_version_id = ?
        """,
        switchedScope.sourceSetRevision(),
        switchedScope.sourceSetHash(),
        Timestamp.valueOf(now),
        scope.id(),
        scope.templateKey(),
        claim.toVersionId());
    int published =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'PUBLISHED', published_at = ?, updated_at = ?,
                   task_version = task_version + 1, last_error = null
             where id = ? and protocol_version = 2 and stage = 'SWITCHING'
            """,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            claim.publicationId());
    if (published != 1) {
      throw new IllegalStateException("发布任务终态写入失败");
    }
    jdbcTemplate.update(
        """
        update form_template_publication_attempts
           set phase = 'PUBLISH', finished_at = ?, verification_summary = ?::jsonb
         where publication_id = ? and attempt_no = ?
        """,
        Timestamp.valueOf(now),
        writeJson(
            objectMapper
                .createObjectNode()
                .put("sourceSetRevision", switchedScope.sourceSetRevision())
                .put("sourceSetHash", switchedScope.sourceSetHash())
                .put("toVersionId", claim.toVersionId())),
        claim.publicationId(),
        claim.attemptNo());
    requireLease(publication(claim.publicationId()), claim, "PUBLISHED");
    clearFence(scope.id(), publication, publication.fenceToken());
    scopeValidationService.validateActiveScopes();
  }

  private void insertSwitchEvents(PublicationRow publication, ScopeRow scope) {
    ObjectNode added = objectMapper.createObjectNode();
    added.put("versionId", publication.toVersionId());
    if (publication.fromVersionId() != null) {
      added.put("fromVersionId", publication.fromVersionId());
    }
    added.put("publicationId", publication.id());
    ObjectNode changed = objectMapper.createObjectNode();
    changed.put("toVersionId", publication.toVersionId());
    if (publication.fromVersionId() != null) {
      changed.put("fromVersionId", publication.fromVersionId());
    }
    changed.put("publicationId", publication.id());
    jdbcTemplate.update(
        """
        insert into form_directory_events(
            scope_id, event_type, source_set_revision, payload, operator_id)
        values (?, 'VERSION_ADDED', ?, ?::jsonb, ?)
        on conflict (scope_id, source_set_revision, event_type) do nothing
        """,
        scope.id(),
        scope.sourceSetRevision(),
        writeJson(added),
        publication.operatorId());
    jdbcTemplate.update(
        """
        insert into form_directory_events(
            scope_id, event_type, source_set_revision, payload, operator_id)
        values (?, 'CURRENT_CHANGED', ?, ?::jsonb, ?)
        on conflict (scope_id, source_set_revision, event_type) do nothing
        """,
        scope.id(),
        scope.sourceSetRevision(),
        writeJson(changed),
        publication.operatorId());
  }

  /** 发布类型对应的范围状态与旧指针：首启只认 DRAFT/NULL，普通发布只认 ACTIVE/冻结旧指针。 */
  private void requireScopePublicationState(PublicationRow publication, ScopeRow scope) {
    if ("INITIAL_EMPTY".equals(publication.kind())) {
      if (!"DRAFT".equals(scope.status()) || scope.currentVersionId() != null) {
        throw new FormConflictException("首次启用范围状态已变化，停止发布");
      }
    } else {
      if (!"ACTIVE".equals(scope.status())) {
        throw new FormConflictException("范围已退役或状态变化，停止发布");
      }
      if (!Objects.equals(scope.currentVersionId(), publication.expectedCurrentVersionId())) {
        throw new FormConflictException("范围当前版本已变化，停止切换");
      }
    }
    requireOriginBinding(publication, scope);
  }

  /**
   * 复核任务创建时冻结的原范围绑定证据。
   *
   * <p>旧任务不得用冻结绑定覆盖发布期间已变化且合法的范围绑定；失配只拒绝当前任务，
   * 不重冻结、不修改范围。
   */
  private void requireOriginBinding(PublicationRow publication, ScopeRow scope) {
    if (publication.originBindingRevision() == null
        || publication.originBindingHash() == null
        || publication.originBindingRevision() != scope.bindingRevision()
        || !Objects.equals(publication.originBindingHash(), scope.bindingHash())) {
      throw new FormConflictException("范围绑定已变化，停止发布");
    }
    ObjectNode canonical = canonicalScopeBinding(scope);
    String canonicalHash =
        canonical == null
            ? FormTemplateAdminService.UNBOUND_BINDING_HASH
            : FormScopeBinding.hash(objectMapper, canonical);
    if (!canonicalHash.equals(publication.originBindingHash())) {
      throw new FormConflictException("范围绑定事实已变化，停止发布");
    }
  }

  private ObjectNode canonicalScopeBinding(ScopeRow scope) {
    if (scope.sourceConfigId() == null
        || scope.sourceInstance() == null
        || scope.canonicalGitlabBaseUrl() == null
        || scope.projectIds() == null
        || scope.projectIds().isEmpty()
        || scope.versionProjectId() == null) {
      return null;
    }
    return FormScopeBinding.create(
        objectMapper,
        scope.templateKey(),
        scope.sourceConfigId(),
        scope.sourceInstance(),
        scope.canonicalGitlabBaseUrl(),
        scope.projectIds(),
        scope.versionProjectId(),
        scope.resourceType());
  }

  private void requireEmptyTarget(PublicationRow publication, VersionRow target) {    FormTemplateSpec spec = parseSpec(publication.frozenFieldSpecJson());
    FormVersionSchema schema =
        schemaService.generate(publication.scopeId(), publication.toVersionId(), spec, false);
    String instance =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.instanceTable());
    String record =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.recordTable());
    if (instance == null || record == null) {
      throw new FormConflictException("目标结构不完整，停止切换");
    }
    Long records =
        jdbcTemplate.queryForObject(
            "select count(*) from \"%s\"".formatted(schema.recordTable()), Long.class);
    Long instances =
        jdbcTemplate.queryForObject(
            "select count(*) from \"%s\"".formatted(schema.instanceTable()), Long.class);
    if ((records != null && records > 0) || (instances != null && instances > 0)) {
      throw new FormConflictException("目标结构已存在数据，停止空结构发布");
    }
    try {
      structureVerifier.verify(schema);
    } catch (IllegalStateException drift) {
      throw new FormConflictException("目标结构漂移，停止切换：" + truncate(drift.getMessage(), 500));
    }
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
    lockRecordSequence();
    lockScope(claim.scopeId());
    PublicationRow publication = lockPublication(claim.publicationId());
    if (publication.protocolVersion() != PROTOCOL_V2
        || !claim.owner().equals(publication.leaseOwner())
        || publication.fencingVersion() != claim.fencingVersion()
        || !V2_ACTIVE_STAGES.contains(publication.stage())) {
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
             where id = ? and protocol_version = 2
               and stage in ('VALIDATING', 'BUILDING', 'SWITCHING')
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
    jdbcTemplate.update(
        """
        update form_template_publication_attempts
           set phase = 'FAILED', error = ?, finished_at = clock_timestamp()
         where publication_id = ? and attempt_no = ?
        """,
        truncate(message, 2000),
        claim.publicationId(),
        claim.attemptNo());
    clearFence(publication.scopeId(), publication, publication.fenceToken());
    return true;
  }

  /** FAILED 任务复核冻结输入后重新进入 VALIDATING；输入已变化时要求重新预览。 */
  @Transactional
  public FormAdminViews.PublicationView recover(long publicationId, FormActorContext actor) {
    PublicationRow publication = publication(publicationId);
    if (publication == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布任务不存在");
    }
    lockRecordSequence();
    ScopeRow scope = lockScope(publication.scopeId());
    PublicationRow locked = lockPublication(publicationId);
    if (!"FAILED".equals(locked.stage())) {
      throw new FormConflictException("只有 FAILED 任务可以恢复");
    }
    if (locked.protocolVersion() != PROTOCOL_V2) {
      throw new FormConflictException("旧协议任务不能恢复，只能按运维流程收口");
    }
    List<PublicationRow> active =
        publicationRows("p.scope_id = " + scope.id() + " and p.stage in ('VALIDATING', 'BUILDING', 'SWITCHING')");
    if (!active.isEmpty()) {
      throw new FormConflictException("范围已有新的未结束任务，旧任务只能终止");
    }
    requireScopePublicationState(locked, scope);
    if (!Objects.equals(scope.sourceSetRevision(), locked.frozenSourceSetRevision())
        || !Objects.equals(scope.sourceSetHash(), locked.frozenSourceSetHash())) {
      throw new FormConflictException("源集合已变化，请重新预览后发布");
    }
    DraftRow draft = requireDraft(scope.id());
    if (draft.revision() != locked.draftRevision()
        || !FormHashSupport.sha256Hex(draft.fieldSpecJson())
            .equals(FormHashSupport.sha256Hex(locked.frozenFieldSpecJson()))
        || !draft.bindingHash().equals(locked.bindingHash())) {
      throw new FormConflictException("草稿或绑定已变化，请重新预览后发布");
    }
    VersionRow target = version(scope.id(), locked.toVersionId());
    if (target == null || "PUBLISHED".equals(target.versionStatus())) {
      throw new FormConflictException("目标版本不可恢复，请另开新发布");
    }
    FormTemplateSpec spec = parseSpec(locked.frozenFieldSpecJson());
    FormVersionSchema schema =
        schemaService.generate(scope.id(), locked.toVersionId(), spec, false);
    String instance =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.instanceTable());
    String record =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.recordTable());
    if ((instance == null) != (record == null)) {
      throw new FormConflictException("目标结构不完整，不能恢复");
    }
    if (instance != null) {
      Long rows =
          jdbcTemplate.queryForObject(
              "select count(*) from \"%s\"".formatted(schema.recordTable()), Long.class);
      if (rows != null && rows > 0) {
        throw new FormConflictException("目标结构已存在记录，不能按空结构恢复");
      }
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
           set stage = 'VALIDATING', recovery_cycle = recovery_cycle + 1,
               fencing_version = fencing_version + 1, lease_owner = null, lease_until = null,
               fence_token = null, next_attempt_at = null, last_error = null,
               recovery_verified_at = ?, recovery_evidence = ?::jsonb, updated_at = ?
         where id = ? and protocol_version = 2 and stage = 'FAILED'
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

  private List<Long> parseProjects(String raw) {
    List<Long> projects = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return projects;
    }
    try {
      objectMapper.readTree(raw).forEach(node -> projects.add(node.asLong()));
    } catch (Exception error) {
      throw new IllegalStateException("范围项目集合无法解析", error);
    }
    return projects;
  }

  // ------------------------------------------------------------------ 旧协议任务收口

  /** 只读盘点 v1 未结束任务；不修改任何行。 */
  @Transactional(readOnly = true)
  public List<FormAdminViews.PublicationView> listLegacyPublications() {
    return publicationRows(
            "p.protocol_version = 1 and p.stage in ('PREPARED', 'COPYING', 'VERIFIED')")
        .stream()
        .map(this::toView)
        .toList();
  }

  /**
   * 运维受控收口单个 v1 任务：有效租约不得终止，外来栅栏不清除。
   *
   * @return 是否完成收口
   */
  @Transactional
  public boolean closeLegacyPublication(long publicationId, String operatorId) {
    PublicationRow publication = publication(publicationId);
    if (publication == null || publication.protocolVersion() != 1) {
      return false;
    }
    lockRecordSequence();
    lockScope(publication.scopeId());
    PublicationRow locked = lockPublication(publicationId);
    if (locked.protocolVersion() != 1 || !V1_ACTIVE_STAGES.contains(locked.stage())) {
      return false;
    }
    Boolean live =
        jdbcTemplate.queryForObject(
            "select coalesce(lease_until, '-infinity') > clock_timestamp()"
                + " from form_template_publications where id = ?",
            Boolean.class,
            publicationId);
    if (Boolean.TRUE.equals(live)) {
      return false;
    }
    int updated =
        jdbcTemplate.update(
            """
            update form_template_publications
               set stage = 'FAILED', last_error = 'v6_no_copy_protocol',
                   recovery_evidence = coalesce(recovery_evidence, '{}'::jsonb)
                     || jsonb_build_object(
                          'closedBy', ?, 'closedAt', clock_timestamp(),
                          'previousStage', stage, 'previousLeaseOwner', lease_owner),
                   updated_at = clock_timestamp()
             where id = ? and protocol_version = 1
               and stage in ('PREPARED', 'COPYING', 'VERIFIED')
               and (lease_owner is null or lease_until is null
                    or lease_until <= clock_timestamp())
            """,
            operatorId,
            publicationId);
    if (updated != 1) {
      return false;
    }
    jdbcTemplate.update(
        """
        insert into form_template_publication_attempts(
            publication_id, attempt_no, phase, selection_captured_at, selection_counts,
            error, started_at, finished_at, protocol_version)
        values (?, (select attempt_no + 1 from form_template_publications where id = ?),
                'FAILED', clock_timestamp(), '{}'::jsonb, 'v6_no_copy_protocol',
                clock_timestamp(), clock_timestamp(), 1)
        """,
        publicationId,
        publicationId);
    clearFence(locked.scopeId(), locked, locked.fenceToken());
    return true;
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
             where protocol_version = 2
               and (
                 (stage = 'VALIDATING'
                  and (lease_owner is null or lease_until is null
                       or lease_until <= clock_timestamp()))
                 or (stage in ('BUILDING', 'SWITCHING') and lease_until <= clock_timestamp())
               )
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

  private void requireFence(long scopeId, long publicationId, String token) {
    Integer fenced =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_template_scopes
             where id = ? and write_fenced = true and write_fence_publication_id = ?
               and write_fence_token = ?
            """,
            Integer.class,
            scopeId,
            publicationId,
            token);
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

  private ScopeRow lockScope(long scopeId) {
    List<ScopeRow> rows =
        jdbcTemplate.query(
            """
            select id, template_key, status, current_version_id, resource_type, binding_hash,
                   source_set_revision, source_set_hash, write_fenced, binding_revision,
                   source_config_id, source_instance, canonical_gitlab_base_url,
                   project_ids::text as project_ids, version_project_id
              from form_template_scopes where id = ? for update
            """,
            (rs, rowNum) ->
                new ScopeRow(
                    rs.getLong("id"),
                    rs.getString("template_key"),
                    rs.getString("status"),
                    rs.getObject("current_version_id", Long.class),
                    rs.getString("resource_type"),
                    rs.getString("binding_hash"),
                    rs.getLong("source_set_revision"),
                    rs.getString("source_set_hash"),
                    rs.getBoolean("write_fenced"),
                    rs.getLong("binding_revision"),
                    rs.getObject("source_config_id", Long.class),
                    rs.getString("source_instance"),
                    rs.getString("canonical_gitlab_base_url"),
                    parseProjects(rs.getString("project_ids")),
                    rs.getObject("version_project_id", Long.class)),
            scopeId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    return rows.getFirst();
  }

  private ScopeFenceRow scopeFence(long scopeId) {
    List<ScopeFenceRow> rows =
        jdbcTemplate.query(
            """
            select write_fenced, write_fence_publication_id, write_fence_token
              from form_template_scopes where id = ?
            """,
            (rs, rowNum) ->
                new ScopeFenceRow(
                    rs.getBoolean("write_fenced"),
                    rs.getObject("write_fence_publication_id", Long.class),
                    rs.getString("write_fence_token")),
            scopeId);
    return rows.isEmpty() ? null : rows.getFirst();
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
               p.publication_kind, p.stage, p.protocol_version,
               p.attempt_no, p.recovery_cycle, p.expected_current_version_id, p.from_version_id,
               p.to_version_id, p.frozen_field_spec::text as frozen_field_spec,
               p.frozen_binding::text as frozen_binding, p.binding_hash, p.ddl_hash,
               p.selection_counts::text as selection_counts,
               p.verification_summary::text as verification_summary,
               p.frozen_source_set_revision, p.frozen_source_set_hash,
               p.origin_binding::text as origin_binding,
               p.origin_binding_revision, p.origin_binding_hash,
               p.lease_owner, p.lease_until, p.fencing_version, p.fence_token, p.last_error,
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
        rs.getInt("protocol_version"),
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
        rs.getObject("frozen_source_set_revision", Long.class),
        rs.getString("frozen_source_set_hash"),
        rs.getString("origin_binding"),
        rs.getObject("origin_binding_revision", Long.class),
        rs.getString("origin_binding_hash"),
        rs.getString("lease_owner"),
        rs.getTimestamp("lease_until") == null
            ? null
            : rs.getTimestamp("lease_until").toLocalDateTime(),
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
            "p.scope_id = " + scopeId
                + " and p.stage in ('PREPARED', 'COPYING', 'VERIFIED',"
                + " 'VALIDATING', 'BUILDING', 'SWITCHING')");
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

  private ObjectNode emptyCounts() {
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", 0);
    counts.put("instances", 0);
    counts.put("resources", 0);
    return counts;
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
      int attemptNo,
      String stage) {}

  private record ScopeRow(
      long id,
      String templateKey,
      String status,
      Long currentVersionId,
      String resourceType,
      String bindingHash,
      long sourceSetRevision,
      String sourceSetHash,
      boolean writeFenced,
      long bindingRevision,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId) {}

  private record ScopeFenceRow(boolean fenced, Long publicationId, String token) {}

  private record PublicationRow(
      long id,
      long scopeId,
      String requestKey,
      String requestHash,
      long draftRevision,
      long previewId,
      String kind,
      String stage,
      int protocolVersion,
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
      Long frozenSourceSetRevision,
      String frozenSourceSetHash,
      String originBindingJson,
      Long originBindingRevision,
      String originBindingHash,
      String leaseOwner,
      LocalDateTime leaseUntil,
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
