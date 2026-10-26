package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormSpecValidator;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormBindingRequest;
import com.data.collection.platform.entity.form.admin.FormDraftUpdateRequest;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.entity.form.admin.FormScopeCreateRequest;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员表单字段草稿、预览、绑定、发布与退役。
 *
 * <p>草稿修订是预览与发布的唯一版本源；发布冻结预览证据后由 {@link FormPublicationService}
 * 在租约与栅栏保护下执行。绑定首次发布后不可变更，后续结构升级不得换源。
 */
@Service
public class FormTemplateAdminService {

  /** 未绑定草稿的占位绑定哈希；不构成覆盖归属，也不允许发布。 */
  public static final String UNBOUND_BINDING_HASH = FormHashSupport.sha256Hex("unbound");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormTemplateSpecCatalog specCatalog;
  private final FormSpecValidator specValidator;
  private final FormVersionSchemaService schemaService;
  private final FormScopeValidationService scopeValidationService;
  private final FormPublicationService publicationService;
  private final FormLegacyRecordMigrator legacyMigrator;
  private final Clock clock;

  public FormTemplateAdminService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormTemplateSpecCatalog specCatalog,
      FormSpecValidator specValidator,
      FormVersionSchemaService schemaService,
      FormScopeValidationService scopeValidationService,
      FormPublicationService publicationService,
      FormLegacyRecordMigrator legacyMigrator,
      Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.specCatalog = specCatalog;
    this.specValidator = specValidator;
    this.schemaService = schemaService;
    this.scopeValidationService = scopeValidationService;
    this.publicationService = publicationService;
    this.legacyMigrator = legacyMigrator;
    this.clock = clock;
  }

  /** 模板与全部范围的当前状态。 */
  @Transactional(readOnly = true)
  public List<FormAdminViews.TemplateSummary> templates() {
    Map<String, FormAdminViews.TemplateSummary> summaries = new LinkedHashMap<>();
    jdbcTemplate.query(
        "select template_key, display_name, status from form_template_definitions order by template_key",
        rs -> {
          summaries.put(
              rs.getString("template_key"),
              new FormAdminViews.TemplateSummary(
                  rs.getString("template_key"),
                  rs.getString("display_name"),
                  rs.getString("status"),
                  new ArrayList<>()));
        });
    for (ScopeRow row : listScopeRows(null)) {
      FormAdminViews.TemplateSummary summary = summaries.get(row.templateKey());
      if (summary != null) {
        summary.scopes().add(toScopeSummary(row));
      }
    }
    return List.copyOf(summaries.values());
  }

  /** 范围、草稿、版本、最近预览/发布与当前记录规模。 */
  @Transactional(readOnly = true)
  public FormAdminViews.ScopeDetail scope(long scopeId) {
    ScopeRow row = requireScope(scopeId);
    DraftRow draftRow = loadDraft(scopeId);
    FormAdminViews.DraftView draft = draftRow == null ? null : toDraftView(draftRow);
    List<FormAdminViews.VersionView> versions = versions(scopeId);
    FormAdminViews.PreviewView preview =
        draftRow == null ? null : latestPreview(scopeId, draftRow.revision());
    FormAdminViews.PublicationView latestPublication = latestPublication(scopeId);
    long recordCount = 0;
    long instanceCount = 0;
    if (row.currentVersionId() != null) {
      VersionTables tables = versionTables(scopeId, row.currentVersionId());
      if (tables != null) {
        recordCount = count(tables.recordTable());
        instanceCount = count(tables.instanceTable());
      }
    }
    return new FormAdminViews.ScopeDetail(
        toScopeSummary(row), draft, versions, preview, latestPublication, recordCount, instanceCount);
  }

  /** 新建 DRAFT 范围并写入模板种子草稿。 */
  @Transactional
  public FormAdminViews.ScopeDetail createScope(
      FormScopeCreateRequest request, FormActorContext actor) {
    String templateKey = requireTemplate(request.templateKey());
    String resourceType = resourceTypeOf(templateKey);
    requireUniqueScopeKey(templateKey, request.scopeKey());
    LocalDateTime now = LocalDateTime.now(clock);
    long scopeId =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                """
                insert into form_template_scopes(
                    template_key, scope_key, project_ids, resource_type, status, updated_at,
                    binding_revision, binding_hash)
                values (?, ?, '[]'::jsonb, ?, 'DRAFT', ?, 0, ?)
                returning id
                """,
                Long.class,
                templateKey,
                request.scopeKey(),
                resourceType,
                Timestamp.valueOf(now),
                UNBOUND_BINDING_HASH));
    jdbcTemplate.update(
        """
        insert into form_template_scope_drafts(
            scope_id, draft_revision, field_spec, project_ids, binding_hash, updated_by, updated_at)
        values (?, 1, ?::jsonb, '[]'::jsonb, ?, ?, ?)
        """,
        scopeId,
        specCatalog.rawJson(templateKey),
        UNBOUND_BINDING_HASH,
        actor.userId(),
        Timestamp.valueOf(now));
    return scope(scopeId);
  }

  /**
   * 保存草稿；修订号 CAS 冲突返回 409。
   *
   * <p>绑定在首次发布后冻结；任何来源、项目集合或发布项目变化都必须走独立迁移单元。
   */
  @Transactional
  public FormAdminViews.DraftView updateDraft(
      long scopeId, FormDraftUpdateRequest request, FormActorContext actor) {
    ScopeRow scope = lockScope(scopeId);
    if ("RETIRED".equals(scope.status())) {
      throw new FormConflictException("范围已退役，不能继续编辑草稿");
    }
    DraftRow existing = requireDraft(scopeId);
    if (existing.revision() != request.expectedDraftRevision()) {
      throw new FormConflictException(
          "草稿已被他人修改，请刷新后重试（当前修订 " + existing.revision() + "）");
    }
    String fieldSpecJson = writeJson(request.fieldSpec());
    FormTemplateSpec spec = parseSpec(fieldSpecJson, scope.templateKey());
    specValidator.validate(spec);

    ResolvedBinding binding = existing.binding();
    if (request.binding() != null) {
      binding = resolveBinding(scope, request.binding(), scopeId);
      if (scope.currentVersionId() != null
          && scope.bindingHash() != null
          && !scope.bindingHash().equals(binding.hash())) {
        throw new FormConflictException("绑定变更需独立迁移，当前范围绑定已冻结");
      }
    }
    String versionLabel = trimToNull(request.versionLabel());
    if (versionLabel != null && versionLabel.length() > 64) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布标签不能超过 64 个字符");
    }
    JsonNode versionEvidence = request.versionEvidence();
    if (versionEvidence != null && versionEvidence.isNull()) {
      versionEvidence = null;
    }
    if (versionEvidence != null && !versionEvidence.isObject()) {
      throw new BizException(ResultCode.BAD_REQUEST, "发布依据必须是 JSON 对象");
    }
    JsonNode migrationSpec = request.migrationSpec();
    if (migrationSpec != null && migrationSpec.isNull()) {
      migrationSpec = null;
    }
    if (migrationSpec == null) {
      migrationSpec = defaultMigrationSpec(scope, existing, spec, binding);
    }
    if (migrationSpec != null && !migrationSpec.isObject()) {
      throw new BizException(ResultCode.BAD_REQUEST, "迁移说明必须是 JSON 对象");
    }
    long revision = existing.revision() + 1;
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        """
        update form_template_scope_drafts
           set draft_revision = ?, field_spec = ?::jsonb, source_config_id = ?,
               source_instance = ?, canonical_gitlab_base_url = ?, project_ids = ?::jsonb,
               version_project_id = ?, version_label = ?, version_evidence = ?::jsonb,
               binding_hash = ?, migration_spec = ?::jsonb, updated_by = ?, updated_at = ?
         where scope_id = ?
        """,
        revision,
        fieldSpecJson,
        binding.sourceConfigId(),
        binding.sourceInstance(),
        binding.canonicalGitlabBaseUrl(),
        writeJson(projectIdsNode(binding.projectIds())),
        binding.versionProjectId(),
        versionLabel,
        versionEvidence == null ? null : writeJson(versionEvidence),
        binding.hash(),
        migrationSpec == null ? null : writeJson(migrationSpec),
        actor.userId(),
        Timestamp.valueOf(now),
        scopeId);
    jdbcTemplate.update(
        "update form_template_scopes set updated_at = ? where id = ?",
        Timestamp.valueOf(now),
        scopeId);
    return toDraftView(requireDraft(scopeId));
  }

  /**
   * 对指定草稿修订生成并持久化预览。
   *
   * <p>预览预留目标版本 ID，使 DDL 摘要与正式发布完全一致；未使用的预览只会留下未分配的版本号。
   */
  @Transactional
  public FormAdminViews.PreviewView preview(
      long scopeId, long draftRevision, FormActorContext actor) {
    ScopeRow scope = requireScope(scopeId);
    DraftRow draft = requireDraft(scopeId);
    if (draft.revision() != draftRevision) {
      throw new FormConflictException("草稿已变化，请刷新后重新预览");
    }
    String fieldSpecJson = draft.fieldSpecJson();
    List<String> blockers = new ArrayList<>();
    FormTemplateSpec spec = null;
    try {
      spec = parseSpec(fieldSpecJson, scope.templateKey());
      blockers.addAll(specValidator.validationErrors(spec));
    } catch (IllegalArgumentException error) {
      blockers.add(error.getMessage());
    }
    ResolvedBinding binding = draft.binding();
    if (!binding.bound()) {
      blockers.add("尚未完成来源与项目绑定");
    } else {
      try {
        scopeValidationService.validateCandidateBinding(
            scope.templateKey(),
            binding.sourceConfigId(),
            binding.sourceInstance(),
            binding.canonicalGitlabBaseUrl(),
            binding.projectIds(),
            binding.versionProjectId(),
            scope.resourceType(),
            scopeId);
      } catch (IllegalArgumentException | IllegalStateException error) {
        blockers.add("范围绑定无效：" + error.getMessage());
      }
    }
    if (trimToNull(draft.versionLabel()) == null) {
      blockers.add("发布标签不能为空");
    }
    if (draft.versionEvidence() == null
        || !draft.versionEvidence().isObject()
        || draft.versionEvidence().isEmpty()) {
      blockers.add("发布依据不能为空");
    }
    String publicationKind = publicationKind(scope, binding);
    boolean migrationTarget = !"INITIAL_EMPTY".equals(publicationKind);
    ObjectNode diff = objectMapper.createObjectNode();
    ObjectNode impact = objectMapper.createObjectNode();
    ArrayNode ddlSummary = objectMapper.createArrayNode();
    String ddlHash = null;
    long targetVersionId = nextVersionId();
    if (spec != null) {
      FormTemplateSpec previous =
          scope.currentVersionId() == null
              ? specCatalog.require(scope.templateKey())
              : currentSpec(scope);
      diff = diff(previous, spec, blockers);
      impact =
          "INITIAL_MIGRATION".equals(publicationKind)
              ? legacyImpact(scope, binding, blockers)
              : impact(scope);
      try {
        FormVersionSchema schema =
            schemaService.generate(scopeId, targetVersionId, spec, migrationTarget);
        ddlSummary = (ArrayNode) schema.ddlSummary().path("columns").deepCopy();
        ddlHash = schema.ddlHash();
      } catch (RuntimeException error) {
        blockers.add("结构 DDL 生成失败：" + error.getMessage());
      }
    }
    ObjectNode preview = objectMapper.createObjectNode();
    preview.put("specVersion", 1);
    preview.put("templateKey", scope.templateKey());
    preview.put("draftRevision", draft.revision());
    preview.put("targetVersionId", targetVersionId);
    preview.put("publicationKind", publicationKind);
    preview.put("migrationTarget", migrationTarget);
    preview.put("fieldSpecHash", FormHashSupport.sha256Hex(fieldSpecJson));
    preview.put("bindingHash", binding.hash());
    preview.set("diff", diff);
    preview.set("impact", impact);
    preview.set("ddlSummary", ddlSummary);
    if (ddlHash != null) {
      preview.put("ddlHash", ddlHash);
    }
    ArrayNode blocking = preview.putArray("blockingErrors");
    blockers.forEach(blocking::add);
    String previewJson = writeJson(preview);
    String previewHash = FormHashSupport.sha256Hex(previewJson);
    LocalDateTime now = LocalDateTime.now(clock);
    long previewId =
        Objects.requireNonNull(
            jdbcTemplate.queryForObject(
                """
                insert into form_template_preview_evidence(
                    scope_id, draft_revision, field_spec_hash, binding_hash, preview_hash,
                    preview_json, blocking_errors, created_by, created_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                on conflict (scope_id, draft_revision) do update
                set field_spec_hash = excluded.field_spec_hash,
                    binding_hash = excluded.binding_hash,
                    preview_hash = excluded.preview_hash,
                    preview_json = excluded.preview_json,
                    blocking_errors = excluded.blocking_errors,
                    created_by = excluded.created_by,
                    created_at = excluded.created_at
                returning id
                """,
                Long.class,
                scopeId,
                draft.revision(),
                preview.path("fieldSpecHash").asText(),
                binding.hash(),
                previewHash,
                previewJson,
                writeJson(blocking),
                actor.userId(),
                Timestamp.valueOf(now)));
    return new FormAdminViews.PreviewView(
        previewId,
        draft.revision(),
        targetVersionId,
        previewHash,
        diff,
        ddlSummary,
        ddlHash,
        impact,
        List.copyOf(blockers),
        now);
  }

  /**
   * 创建发布任务。
   *
   * <p>发布种类与预览一致：已有版本为 MIGRATION；首次按当前绑定 S 选择 INITIAL_MIGRATION 或
   * INITIAL_EMPTY。旧表首迁同样由通用发布执行器按冻结草稿规格执行。
   */
  public FormAdminViews.PublicationView createPublication(
      long scopeId, FormPublicationCreateRequest request, FormActorContext actor) {
    String requestKey = canonicalRequestKey(request.requestKey());
    return publicationService.createPublication(scopeId, request, requestKey, actor);
  }

  /** 退役范围；历史读取保留，写入拒绝。 */
  @Transactional
  public FormAdminViews.ScopeDetail retire(
      long scopeId, long expectedCurrentVersionId, FormActorContext actor) {
    ScopeRow scope = lockScope(scopeId);
    if (!"ACTIVE".equals(scope.status())) {
      throw new FormConflictException("只有 ACTIVE 范围可以退役");
    }
    if (!Objects.equals(scope.currentVersionId(), expectedCurrentVersionId)) {
      throw new FormConflictException("范围当前版本已变化，请刷新后重试");
    }
    requireNoActivePublication(scopeId);
    LocalDateTime now = LocalDateTime.now(clock);
    jdbcTemplate.update(
        "update form_template_scopes set status = 'RETIRED', updated_at = ? where id = ?",
        Timestamp.valueOf(now),
        scopeId);
    return scope(scopeId);
  }

  /** 删除尚未发布且无历史归属的 DRAFT 范围。 */
  @Transactional
  public void deleteScope(long scopeId) {
    ScopeRow scope = lockScope(scopeId);
    if (!"DRAFT".equals(scope.status()) || scope.currentVersionId() != null) {
      throw new FormConflictException("只能删除未发布的 DRAFT 范围");
    }
    if (count("form_template_versions where scope_id = " + scopeId) > 0) {
      throw new FormConflictException("范围已有版本记录，不能删除");
    }
    if (count("form_template_publications where scope_id = " + scopeId) > 0) {
      throw new FormConflictException("范围已有发布记录，不能删除");
    }
    if (count("form_resource_authorities where scope_id = " + scopeId) > 0
        || count("form_change_heads where scope_id = " + scopeId) > 0) {
      throw new FormConflictException("范围已有权威或变更登记，不能删除");
    }
    jdbcTemplate.update("delete from form_template_scopes where id = ?", scopeId);
  }

  /** 可选来源配置；规范地址无法解析时返回 null，由管理页禁用。 */
  @Transactional(readOnly = true)
  public List<FormAdminViews.BindingOption> bindingOptions() {
    return jdbcTemplate.query(
        "select id, name, web_base_url from gitlab_sync_configs order by id",
        (rs, rowNum) -> {
          String canonical = null;
          String raw = rs.getString("web_base_url");
          if (raw != null && !raw.isBlank()) {
            try {
              canonical = FormSourceUrlSupport.canonicalize(raw);
            } catch (IllegalArgumentException ignored) {
              // 非法来源配置不能作为绑定候选，管理页按 null 禁用。
            }
          }
          return new FormAdminViews.BindingOption(
              rs.getLong("id"),
              rs.getString("name"),
              GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
              canonical);
        });
  }

  /**
   * ODS 里程碑候选。
   *
   * <p>镜像表未就绪时返回 available=false，管理页提示先补同步；不阻塞列表读取。
   */
  @Transactional(readOnly = true)
  public FormAdminViews.MilestoneAvailability milestones(long sourceConfigId, long projectId) {
    if (sourceConfigId <= 0 || projectId <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置与项目必须为正整数");
    }
    Integer source =
        jdbcTemplate.queryForObject(
            "select count(*) from gitlab_sync_configs where id = ?", Integer.class, sourceConfigId);
    if (source == null || source == 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置不存在");
    }
    String table = jdbcTemplate.queryForObject("select to_regclass(?)", String.class, "ods_gitlab_milestones");
    if (table == null) {
      return new FormAdminViews.MilestoneAvailability(false, List.of());
    }
    List<FormAdminViews.MilestoneView> items =
        jdbcTemplate.query(
            """
            select id, title from ods_gitlab_milestones
             where project_id = ? and mirror_deleted = false
             order by id desc limit 500
            """,
            (rs, rowNum) ->
                new FormAdminViews.MilestoneView(rs.getLong("id"), rs.getString("title")),
            projectId);
    return new FormAdminViews.MilestoneAvailability(true, items);
  }

  // ------------------------------------------------------------------ 内部实现

  private ScopeRow lockScope(long scopeId) {
    List<Long> locked =
        jdbcTemplate.queryForList(
            "select id from form_template_scopes where id = ? for update", Long.class, scopeId);
    if (locked.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    return requireScope(scopeId);
  }

  private ScopeRow requireScope(long scopeId) {
    List<ScopeRow> rows = listScopeRows("s.id = " + scopeId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    return rows.getFirst();
  }

  private List<ScopeRow> listScopeRows(String condition) {
    String where = condition == null ? "" : " where " + condition;
    return jdbcTemplate.query(
        """
        select s.id, s.template_key, s.scope_key, s.status, s.resource_type, s.source_config_id,
               s.source_instance, s.canonical_gitlab_base_url, s.project_ids::text as project_ids,
               s.version_project_id, s.binding_hash, s.binding_revision, s.current_version_id,
               s.write_fenced, s.updated_at,
               v.version_label, v.version_status,
               d.draft_revision,
               (select count(*) from form_template_publications p
                 where p.scope_id = s.id and p.stage in ('PREPARED', 'COPYING', 'VERIFIED'))
                 as active_publications
          from form_template_scopes s
          left join form_template_versions v on v.id = s.current_version_id
          left join form_template_scope_drafts d on d.scope_id = s.id
        """
            + where
            + (condition == null ? " order by s.template_key, s.id" : ""),
        (rs, rowNum) ->
            new ScopeRow(
                rs.getLong("id"),
                rs.getString("template_key"),
                rs.getString("scope_key"),
                rs.getString("status"),
                rs.getString("resource_type"),
                rs.getObject("source_config_id", Long.class),
                rs.getString("source_instance"),
                rs.getString("canonical_gitlab_base_url"),
                parseProjects(rs.getString("project_ids")),
                rs.getObject("version_project_id", Long.class),
                rs.getString("binding_hash"),
                rs.getLong("binding_revision"),
                rs.getObject("current_version_id", Long.class),
                rs.getString("version_label"),
                rs.getString("version_status"),
                rs.getObject("draft_revision", Long.class),
                rs.getBoolean("write_fenced"),
                rs.getLong("active_publications"),
                rs.getTimestamp("updated_at").toLocalDateTime()));
  }

  private FormAdminViews.ScopeSummary toScopeSummary(ScopeRow row) {
    return new FormAdminViews.ScopeSummary(
        row.id(),
        row.templateKey(),
        row.scopeKey(),
        row.status(),
        row.resourceType(),
        row.sourceConfigId(),
        row.sourceInstance(),
        row.canonicalGitlabBaseUrl(),
        row.projectIds(),
        row.versionProjectId(),
        row.bindingHash(),
        row.bindingRevision(),
        row.currentVersionId(),
        row.versionLabel(),
        row.versionStatus(),
        row.draftRevision(),
        row.writeFenced(),
        row.activePublications(),
        row.updatedAt());
  }

  private DraftRow requireDraft(long scopeId) {
    DraftRow row = loadDraft(scopeId);
    if (row == null) {
      throw new FormConflictException("范围草稿不存在，请重新创建范围");
    }
    return row;
  }

  private DraftRow loadDraft(long scopeId) {
    List<DraftRow> rows =
        jdbcTemplate.query(
            """
            select draft_revision, field_spec::text as field_spec, source_config_id,
                   source_instance, canonical_gitlab_base_url, project_ids::text as project_ids,
                   version_project_id, version_label, version_evidence::text as version_evidence,
                   binding_hash, migration_spec::text as migration_spec, updated_by, updated_at
              from form_template_scope_drafts where scope_id = ?
            """,
            (rs, rowNum) ->
                new DraftRow(
                    rs.getLong("draft_revision"),
                    rs.getString("field_spec"),
                    new ResolvedBinding(
                        rs.getObject("source_config_id", Long.class),
                        rs.getString("source_instance"),
                        rs.getString("canonical_gitlab_base_url"),
                        parseProjects(rs.getString("project_ids")),
                        rs.getObject("version_project_id", Long.class),
                        rs.getString("binding_hash")),
                    rs.getString("version_label"),
                    readJson(rs.getString("version_evidence")),
                    readJson(rs.getString("migration_spec")),
                    rs.getString("updated_by"),
                    rs.getTimestamp("updated_at").toLocalDateTime()),
            scopeId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private FormAdminViews.DraftView toDraftView(DraftRow row) {
    ResolvedBinding binding = row.binding();
    boolean bound =
        binding.sourceConfigId() != null
            && binding.canonicalGitlabBaseUrl() != null
            && binding.versionProjectId() != null
            && !binding.projectIds().isEmpty()
            && !UNBOUND_BINDING_HASH.equals(binding.hash());
    return new FormAdminViews.DraftView(
        row.revision(),
        readJson(row.fieldSpecJson()),
        new FormAdminViews.BindingView(
            binding.sourceConfigId(),
            binding.sourceInstance(),
            binding.canonicalGitlabBaseUrl(),
            binding.projectIds(),
            binding.versionProjectId(),
            binding.hash(),
            bound),
        row.versionLabel(),
        row.versionEvidence(),
        row.migrationSpec(),
        row.updatedBy(),
        row.updatedAt());
  }

  private List<FormAdminViews.VersionView> versions(long scopeId) {
    return jdbcTemplate.query(
        """
        select id, version_label, storage_kind, version_status, instance_table, record_table,
               published_at
          from form_template_versions where scope_id = ? order by id desc
        """,
        (rs, rowNum) ->
            new FormAdminViews.VersionView(
                rs.getLong("id"),
                rs.getString("version_label"),
                rs.getString("storage_kind"),
                rs.getString("version_status"),
                rs.getString("instance_table"),
                rs.getString("record_table"),
                rs.getTimestamp("published_at") == null
                    ? null
                    : rs.getTimestamp("published_at").toLocalDateTime()),
        scopeId);
  }

  private FormAdminViews.PreviewView latestPreview(long scopeId, long draftRevision) {
    List<FormAdminViews.PreviewView> rows =
        jdbcTemplate.query(
            """
            select id, draft_revision, preview_hash, preview_json::text as preview_json,
                   blocking_errors::text as blocking_errors, created_at
              from form_template_preview_evidence
             where scope_id = ? and draft_revision = ?
            """,
            (rs, rowNum) -> {
              JsonNode preview = readJson(rs.getString("preview_json"));
              JsonNode blocking = readJson(rs.getString("blocking_errors"));
              List<String> errors = new ArrayList<>();
              if (blocking != null && blocking.isArray()) {
                blocking.forEach(node -> errors.add(node.asText()));
              }
              return new FormAdminViews.PreviewView(
                  rs.getLong("id"),
                  rs.getLong("draft_revision"),
                  preview.path("targetVersionId").asLong(),
                  rs.getString("preview_hash"),
                  preview.path("diff"),
                  preview.path("ddlSummary"),
                  preview.path("ddlHash").asText(null),
                  preview.path("impact"),
                  List.copyOf(errors),
                  rs.getTimestamp("created_at").toLocalDateTime());
            },
            scopeId,
            draftRevision);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private FormAdminViews.PublicationView latestPublication(long scopeId) {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from form_template_publications where scope_id = ? order by id desc limit 1",
            Long.class,
            scopeId);
    return ids.isEmpty() ? null : publicationService.publicationView(ids.getFirst());
  }

  private long requirePreview(long scopeId, long draftRevision, long previewId) {
    List<String> hashes =
        jdbcTemplate.queryForList(
            """
            select preview_hash from form_template_preview_evidence
             where id = ? and scope_id = ? and draft_revision = ?
            """,
            String.class,
            previewId,
            scopeId,
            draftRevision);
    if (hashes.isEmpty()) {
      throw new FormConflictException("预览不存在或与草稿修订不一致，请重新预览");
    }
    return previewId;
  }

  private ResolvedBinding resolveBinding(
      ScopeRow scope, FormBindingRequest request, long scopeId) {
    long sourceConfigId = request.sourceConfigId();
    List<String> urls =
        jdbcTemplate.queryForList(
            "select web_base_url from gitlab_sync_configs where id = ?", String.class, sourceConfigId);
    if (urls.isEmpty() || urls.getFirst() == null || urls.getFirst().isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置不存在或缺少 Web 基址");
    }
    String canonical;
    try {
      canonical = FormSourceUrlSupport.canonicalize(urls.getFirst());
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源配置 Web 基址非法");
    }
    List<Long> projectIds = scopeValidationService.normalizeProjectIds(request.projectIds());
    try {
      scopeValidationService.validateCandidateBinding(
          scope.templateKey(),
          sourceConfigId,
          GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
          canonical,
          projectIds,
          request.versionProjectId(),
          scope.resourceType(),
          scopeId);
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, error.getMessage());
    } catch (IllegalStateException error) {
      if (error.getMessage() != null && error.getMessage().contains("重叠")) {
        throw new FormConflictException(error.getMessage());
      }
      throw new BizException(ResultCode.BAD_REQUEST, error.getMessage());
    }
    ObjectNode binding =
        FormScopeBinding.create(
            objectMapper,
            scope.templateKey(),
            sourceConfigId,
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            canonical,
            projectIds,
            request.versionProjectId(),
            scope.resourceType());
    return new ResolvedBinding(
        sourceConfigId,
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        canonical,
        projectIds,
        request.versionProjectId(),
        FormScopeBinding.hash(objectMapper, binding));
  }

  private FormTemplateSpec currentSpec(ScopeRow scope) {
    if (scope.currentVersionId() == null) {
      return null;
    }
    List<String> specs =
        jdbcTemplate.queryForList(
            "select field_spec::text from form_template_versions where scope_id = ? and id = ?",
            String.class,
            scope.id(),
            scope.currentVersionId());
    if (specs.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.readValue(specs.getFirst(), FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException("当前版本结构无法解析", error);
    }
  }

  /** 结构差异与不可自动迁移的变更；问题写入 blockers。 */
  private ObjectNode diff(
      FormTemplateSpec previous, FormTemplateSpec next, List<String> blockers) {
    Map<String, FormFieldSpec> oldFields = new LinkedHashMap<>();
    if (previous != null) {
      previous.fields().forEach(field -> oldFields.put(field.name(), field));
    }
    Map<String, FormFieldSpec> newFields = new LinkedHashMap<>();
    next.fields().forEach(field -> newFields.put(field.name(), field));
    ArrayNode changes = objectMapper.createArrayNode();
    for (Map.Entry<String, FormFieldSpec> entry : newFields.entrySet()) {
      FormFieldSpec current = entry.getValue();
      FormFieldSpec old = oldFields.get(entry.getKey());
      if (old == null) {
        changes.add(change(entry.getKey(), "ADDED", null, null, null, current));
        continue;
      }
      ArrayNode changedKinds = objectMapper.createArrayNode();
      ObjectNode node = objectMapper.createObjectNode();
      node.put("name", entry.getKey());
      node.put("oldLabel", old.label());
      node.put("newLabel", current.label());
      node.put("oldRequired", old.required());
      node.put("newRequired", current.required());
      node.put("oldControl", old.control());
      node.put("newControl", current.control());
      if (!Objects.equals(old.label(), current.label())) {
        changedKinds.add("LABEL");
      }
      if (!Objects.equals(old.required(), current.required())) {
        changedKinds.add("REQUIRED");
      }
      if (!Objects.equals(old.control(), current.control())) {
        changedKinds.add("CONTROL");
      }
      if (!sameOptions(old, current)) {
        changedKinds.add("OPTIONS");
        putOptions(node, "old", old);
        putOptions(node, "new", current);
      }
      String oldColumn = old.storage() == null ? null : old.storage().resolvedColumn();
      String newColumn = current.storage() == null ? null : current.storage().resolvedColumn();
      if (!Objects.equals(oldColumn, newColumn)) {
        changedKinds.add("COLUMN");
        node.put("oldColumn", oldColumn);
        node.put("newColumn", newColumn);
        blockers.add("字段 " + entry.getKey() + " 的存储列不可变更；请新增字段并迁移数据");
      }
      String oldType = old.storage() == null ? null : old.storage().resolvedColumnType();
      String newType = current.storage() == null ? null : current.storage().resolvedColumnType();
      node.put("oldType", oldType);
      node.put("newType", newType);
      if (!Objects.equals(oldType, newType)) {
        changedKinds.add("TYPE");
        if (!convertibleType(oldType, newType)) {
          blockers.add(
              "字段 " + entry.getKey() + " 的类型从 " + oldType + " 改为 " + newType + " 不可自动迁移");
        }
      }
      if (!changedKinds.isEmpty()) {
        node.set("changes", changedKinds);
        node.put("change", changedKinds.size() == 1 ? changedKinds.get(0).asText() : "MIXED");
        changes.add(node);
      }
    }
    for (String name : oldFields.keySet()) {
      if (!newFields.containsKey(name)) {
        ObjectNode node = changes.addObject();
        node.put("name", name);
        node.put("change", "REMOVED");
        FormFieldSpec removed = oldFields.get(name);
        node.put("oldLabel", removed.label());
        node.put("oldRequired", removed.required());
        node.put("oldControl", removed.control());
        if (removed.storage() != null) {
          node.put("oldColumn", removed.storage().resolvedColumn());
          node.put("oldType", removed.storage().resolvedColumnType());
        }
        putOptions(node, "old", removed);
      }
    }
    for (String snapshotColumn : next.snapshotColumns()) {
      if (previous != null && previous.snapshotColumns().contains(snapshotColumn)) {
        continue;
      }
      if (previous != null) {
        blockers.add("新增条件快照列 " + snapshotColumn + " 需要独立迁移契约");
      }
    }
    ObjectNode result = objectMapper.createObjectNode();
    result.set("fields", changes);
    int added = changesLess(changes, "ADDED");
    int removed = changesLess(changes, "REMOVED");
    result.put("added", added);
    result.put("removed", removed);
    result.put("modified", changes.size() - added - removed);
    return result;
  }

  private ObjectNode change(
      String name, String kind, String oldColumn, String oldType, String oldLabel, FormFieldSpec current) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("name", name);
    node.put("change", kind);
    if (oldColumn != null) {
      node.put("oldColumn", oldColumn);
    }
    if (oldType != null) {
      node.put("oldType", oldType);
    }
    if (oldLabel != null) {
      node.put("oldLabel", oldLabel);
    }
    if (current != null) {
      node.put("newLabel", current.label());
      node.put("newControl", current.control());
      node.put("newRequired", current.required());
      putOptions(node, "new", current);
      if (current.storage() != null) {
        node.put("newColumn", current.storage().resolvedColumn());
        node.put("newType", current.storage().resolvedColumnType());
      }
    }
    return node;
  }

  private boolean sameOptions(FormFieldSpec left, FormFieldSpec right) {
    List<String> leftValues =
        left.options() == null
            ? List.of()
            : left.options().stream().map(option -> option.value() + "=" + option.label()).toList();
    List<String> rightValues =
        right.options() == null
            ? List.of()
            : right.options().stream().map(option -> option.value() + "=" + option.label()).toList();
    return leftValues.equals(rightValues);
  }

  private void putOptions(ObjectNode node, String prefix, FormFieldSpec field) {
    ArrayNode options = node.putArray(prefix + "Options");
    if (field.options() == null) {
      return;
    }
    for (int index = 0; index < field.options().size(); index++) {
      ObjectNode option = options.addObject();
      option.put("value", field.options().get(index).value());
      option.put("label", field.options().get(index).label());
    }
  }

  private int changesLess(ArrayNode changes, String kind) {
    int count = 0;
    for (JsonNode node : changes) {
      if (kind.equals(node.path("change").asText())) {
        count++;
      }
    }
    return count;
  }

  /** 记录与实例规模，供预览展示迁移影响。 */
  private ObjectNode impact(ScopeRow scope) {
    ObjectNode impact = objectMapper.createObjectNode();
    if (scope.currentVersionId() == null) {
      impact.put("records", 0);
      impact.put("instances", 0);
      impact.put("resources", 0);
      return impact;
    }
    VersionTables tables = versionTables(scope.id(), scope.currentVersionId());
    if (tables == null) {
      impact.put("records", 0);
      impact.put("instances", 0);
      impact.put("resources", 0);
      return impact;
    }
    impact.put("records", count(tables.recordTable()));
    impact.put("instances", count(tables.instanceTable()));
    impact.put("resources", count(tables.instanceTable()));
    return impact;
  }

  private VersionTables versionTables(long scopeId, long versionId) {
    List<VersionTables> rows =
        jdbcTemplate.query(
            "select instance_table, record_table from form_template_versions where scope_id = ? and id = ?",
            (rs, rowNum) ->
                new VersionTables(rs.getString("instance_table"), rs.getString("record_table")),
            scopeId,
            versionId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 首次发布路径：已有当前版本一律 MIGRATION；无当前版本时只按本绑定范围的旧表选集 S 判定。
   *
   * <p>其它范围或项目的旧记录不触发 INITIAL_MIGRATION；本范围 S 为空时走 INITIAL_EMPTY。
   */
  private String publicationKind(ScopeRow scope, ResolvedBinding binding) {
    if (scope.currentVersionId() != null) {
      return "MIGRATION";
    }
    if (binding.bound()
        && legacyMigrator.hasScopedSelection(
            scope.templateKey(), binding.canonicalGitlabBaseUrl(), binding.projectIds())) {
      return "INITIAL_MIGRATION";
    }
    return "INITIAL_EMPTY";
  }

  /** 初始迁移影响：只统计冻结依赖的当前绑定 S，不把其它项目旧行计入。 */
  private ObjectNode legacyImpact(
      ScopeRow scope, ResolvedBinding binding, List<String> blockers) {
    ObjectNode impact = objectMapper.createObjectNode();
    impact.put("records", 0);
    impact.put("instances", 0);
    impact.put("resources", 0);
    impact.put("sourceRows", 0);
    if (!binding.bound()) {
      return impact;
    }
    try {
      FormLegacyRecordMigrator.Selection selection =
          legacyMigrator.select(
              scope.templateKey(),
              binding.canonicalGitlabBaseUrl(),
              binding.projectIds(),
              binding.hash());
      impact.put("records", selection.rows().size());
      impact.put("instances", selection.instances().size());
      impact.put("resources", selection.instances().size());
      impact.put("sourceRows", selection.rows().size());
    } catch (IllegalArgumentException | IllegalStateException error) {
      blockers.add("旧表选集无效：" + error.getMessage());
    }
    return impact;
  }

  private JsonNode defaultMigrationSpec(
      ScopeRow scope, DraftRow draft, FormTemplateSpec next, ResolvedBinding binding) {
    if (scope.currentVersionId() == null) {
      return null;
    }
    ObjectNode root = objectMapper.createObjectNode();
    root.put("specVersion", 1);
    root.put("kind", "MIGRATION");
    root.put("fromVersionId", scope.currentVersionId());
    ArrayNode mappings = root.putArray("fieldMappings");
    next.fields()
        .forEach(
            field ->
                mappings.add(
                    field.name() + "->" + (field.storage() == null ? "" : field.storage().resolvedColumn())));
    root.put("bindingHash", binding.hash());
    return root;
  }

  private void requireNoActivePublication(long scopeId) {
    Integer active =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_template_publications
             where scope_id = ? and stage in ('PREPARED', 'COPYING', 'VERIFIED')
            """,
            Integer.class,
            scopeId);
    if (active != null && active > 0) {
      throw new FormConflictException("范围存在未结束的发布任务");
    }
  }

  private String requireTemplate(String templateKey) {
    if (templateKey == null || !FormTemplateSpecCatalog.TEMPLATE_KEYS.contains(templateKey)) {
      throw new BizException(ResultCode.BAD_REQUEST, "未知模板：" + templateKey);
    }
    List<String> statuses =
        jdbcTemplate.queryForList(
            "select status from form_template_definitions where template_key = ?",
            String.class,
            templateKey);
    if (statuses.isEmpty() || !"ACTIVE".equals(statuses.getFirst())) {
      throw new BizException(ResultCode.BAD_REQUEST, "模板定义不存在或未启用");
    }
    return templateKey;
  }

  private void requireUniqueScopeKey(String templateKey, String scopeKey) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from form_template_scopes where template_key = ? and scope_key = ?",
            Integer.class,
            templateKey,
            scopeKey);
    if (count != null && count > 0) {
      throw new FormConflictException("同模板下范围键已存在：" + scopeKey);
    }
  }

  private String resourceTypeOf(String templateKey) {
    return FormTemplateSpecCatalog.CODE_REVIEW.equals(templateKey) ? "merge_request" : "issue";
  }

  private FormTemplateSpec parseSpec(String raw, String templateKey) {
    FormTemplateSpec spec;
    try {
      spec = objectMapper.readValue(raw, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalArgumentException("表单规格不是合法 JSON：" + error.getMessage(), error);
    }
    if (spec.specVersion() != 1) {
      throw new IllegalArgumentException("不支持的 spec_version " + spec.specVersion());
    }
    if (!templateKey.equals(spec.templateKey())) {
      throw new IllegalArgumentException("规格 template_key 与范围不一致");
    }
    return spec;
  }

  private boolean convertibleType(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    if (from.equals(to)) {
      return true;
    }
    if ("integer".equals(from) && ("text".equals(to) || to.startsWith("varchar("))) {
      return true;
    }
    if ("text".equals(from) && to.startsWith("varchar(")) {
      return true;
    }
    if (from.startsWith("varchar(") && "text".equals(to)) {
      return true;
    }
    if (from.startsWith("varchar(") && to.startsWith("varchar(")) {
      Integer fromLength = varcharLength(from);
      Integer toLength = varcharLength(to);
      return fromLength != null && toLength != null && toLength >= fromLength;
    }
    return false;
  }

  private Integer varcharLength(String type) {
    try {
      return Integer.parseInt(type.substring("varchar(".length(), type.length() - 1));
    } catch (RuntimeException error) {
      return null;
    }
  }

  private long nextVersionId() {
    return Objects.requireNonNull(
        jdbcTemplate.queryForObject("select nextval('form_template_versions_id_seq')", Long.class));
  }

  private long count(String fromWhere) {
    Integer value = jdbcTemplate.queryForObject("select count(*) from " + fromWhere, Integer.class);
    return value == null ? 0 : value;
  }

  private List<Long> parseProjects(String raw) {
    JsonNode node = readJson(raw);
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<Long> projects = new ArrayList<>();
    for (JsonNode item : node) {
      if (item.canConvertToLong()) {
        projects.add(item.asLong());
      }
    }
    return List.copyOf(projects);
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
    ArrayNode projects = objectMapper.createArrayNode();
    projectIds.forEach(projects::add);
    return projects;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to serialize form admin payload", error);
    }
  }

  private String canonicalRequestKey(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求标识不能为空");
    }
    try {
      return java.util.UUID.fromString(raw.trim()).toString();
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求标识必须是规范 UUID");
    }
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record ScopeRow(
      long id,
      String templateKey,
      String scopeKey,
      String status,
      String resourceType,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId,
      String bindingHash,
      long bindingRevision,
      Long currentVersionId,
      String versionLabel,
      String versionStatus,
      Long draftRevision,
      boolean writeFenced,
      long activePublications,
      LocalDateTime updatedAt) {}

  private record DraftRow(
      long revision,
      String fieldSpecJson,
      ResolvedBinding binding,
      String versionLabel,
      JsonNode versionEvidence,
      JsonNode migrationSpec,
      String updatedBy,
      LocalDateTime updatedAt) {}

  private record ResolvedBinding(
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      List<Long> projectIds,
      Long versionProjectId,
      String hash) {

    boolean bound() {
      return sourceConfigId != null
          && canonicalGitlabBaseUrl != null
          && versionProjectId != null
          && projectIds != null
          && !projectIds.isEmpty()
          && hash != null
          && !UNBOUND_BINDING_HASH.equals(hash);
    }
  }

  private record VersionTables(String instanceTable, String recordTable) {}
}
