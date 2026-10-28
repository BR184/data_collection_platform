package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.GitlabResourceLinkService;
import com.data.collection.platform.service.GitlabResourceRootResolver;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 按资源上下文C解析唯一的已登记范围与当前结构版本。
 *
 * <p>写入路径必须取得范围共享锁并再次核对当前指针；读取路径允许退役范围，但同样只读冻结版本表。
 */
@Service
public class FormRecordContextService {

  private static final Pattern GENERATED_TABLE =
      Pattern.compile("^form_s\\d+_v\\d+_(instances|records)$");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormScopeValidationService scopeValidationService;
  private final GitlabResourceLinkService resourceLinkService;

  public FormRecordContextService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormScopeValidationService scopeValidationService,
      GitlabResourceLinkService resourceLinkService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.scopeValidationService = scopeValidationService;
    this.resourceLinkService = resourceLinkService;
  }

  /**
   * 解析资源所属范围与当前版本。
   *
   * @param templateKey 冻结模板键
   * @param gitlabBaseUrl 页面来源地址，只用于匹配已保存配置
   * @param projectId GitLab 项目 ID
   * @param resourceType merge_request 或 issue
   * @param resourceId 项目内 IID
   * @param forWrite 写入路径要求 ACTIVE/PUBLISHED；读取允许退役范围
   * @return 唯一上下文
   */
  public FormVersionContext resolve(
      String templateKey,
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      boolean forWrite) {
    String key = requireTemplateKey(templateKey);
    String type = requireResourceType(resourceType, key);
    long project = requirePositive(projectId, "Project ID");
    long iid = parsePositive(resourceId, "资源编号");
    String canonicalUrl = canonicalize(gitlabBaseUrl);
    requireActiveScopesHealthy();

    List<ScopeVersionRow> rows =
        jdbcTemplate.query(
            """
            select s.id, s.scope_key, s.status, s.source_config_id, s.source_instance,
                   s.canonical_gitlab_base_url, s.project_ids::text as project_ids,
                   s.current_version_id, s.binding_hash,
                   v.version_label, v.version_status, v.storage_kind, v.instance_table,
                   v.record_table, v.instance_mode, v.field_spec::text as field_spec
              from form_template_scopes s
              join form_template_versions v on v.id = s.current_version_id
             where s.template_key = ? and s.resource_type = ?
               and s.status in ('ACTIVE', 'RETIRED')
             order by case s.status when 'ACTIVE' then 0 else 1 end, s.id
            """,
            (rs, rowNum) ->
                new ScopeVersionRow(
                    rs.getLong("id"),
                    rs.getString("scope_key"),
                    rs.getString("status"),
                    rs.getObject("source_config_id", Long.class),
                    rs.getString("source_instance"),
                    rs.getString("canonical_gitlab_base_url"),
                    rs.getString("project_ids"),
                    rs.getObject("current_version_id", Long.class),
                    rs.getString("binding_hash"),
                    rs.getString("version_label"),
                    rs.getString("version_status"),
                    rs.getString("storage_kind"),
                    rs.getString("instance_table"),
                    rs.getString("record_table"),
                    rs.getString("instance_mode"),
                    rs.getString("field_spec")),
            key,
            type);

    ScopeVersionRow matched = null;
    for (ScopeVersionRow row : rows) {
      if (!canonicalUrl.equals(canonicalStoredUrl(row))) {
        continue;
      }
      if (!parseProjectIds(row).contains(project)) {
        continue;
      }
      matched = row;
      break;
    }
    if (matched == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "该资源尚未登记表单模板范围");
    }
    // 受控原位登记的 LEGACY/NONE 版本没有实例表与记录表，只允许只读回读历史记录。
    boolean materialized =
        "RECORDS".equals(matched.storageKind())
            && matched.instanceTable() != null
            && matched.recordTable() != null;
    boolean legacyInPlace =
        "LEGACY".equals(matched.storageKind()) && "NONE".equals(matched.instanceMode());
    if (matched.sourceConfigId() == null
        || matched.currentVersionId() == null
        || (!materialized && !legacyInPlace)) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围当前版本不完整");
    }
    if (forWrite && !materialized) {
      throw new FormConflictException("该范围仅有原位历史记录，不能写入");
    }
    if (materialized) {
      validateTables(matched);
    }
    if ("ACTIVE".equals(matched.status())) {
      validateSource(matched.sourceConfigId(), canonicalUrl);
    }
    if (forWrite && !"ACTIVE".equals(matched.status())) {
      throw new FormConflictException("表单范围已停用，仅保留历史查询");
    }
    if (forWrite && !"PUBLISHED".equals(matched.versionStatus())) {
      throw new FormConflictException("表单结构版本未发布，不能写入");
    }

    FormTemplateSpec spec = parseSpec(matched.fieldSpec(), key);
    JsonNode fieldSpecJson = readFieldSpec(matched.fieldSpec());
    Long rootId;
    String severity = null;
    if ("merge_request".equals(type)) {
      rootId =
          GitlabResourceRootResolver.findMergeRequestRootId(
              jdbcTemplate, GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, project, iid);
    } else {
      rootId =
          GitlabResourceRootResolver.findIssueRootId(
              jdbcTemplate, GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, project, iid);
      severity = severityLevel(rootId);
    }
    return new FormVersionContext(
        matched.id(),
        matched.status(),
        matched.sourceConfigId(),
        matched.sourceInstance(),
        canonicalUrl,
        matched.currentVersionId(),
        matched.versionLabel(),
        matched.versionStatus(),
        matched.instanceTable(),
        matched.recordTable(),
        spec,
        fieldSpecJson,
        project,
        type,
        iid,
        rootId,
        severity,
        resourceUrl(matched.sourceInstance(), canonicalUrl, type, project, iid));
  }

  /**
   * 在写事务内锁定范围行并再次核对当前指针和停写栅栏，阻止发布切换与写入交错。
   *
   * @param context 已解析上下文
   */
  public void lockWritableScope(FormVersionContext context) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select status, current_version_id, write_fenced from form_template_scopes"
                + " where id = ? for share",
            context.scopeId());
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    Map<String, Object> row = rows.get(0);
    if (!"ACTIVE".equals(row.get("status"))) {
      throw new FormConflictException("表单范围已停用，仅保留历史查询");
    }
    if (Boolean.TRUE.equals(row.get("write_fenced"))) {
      throw new FormConflictException("表单范围已停写，请稍后重试");
    }
    long current = ((Number) row.get("current_version_id")).longValue();
    if (current != context.storageVersionId()) {
      throw new FormConflictException("表单结构版本已变化，请刷新后重试");
    }
  }

  /** 写事务内锁定范围行与停写栅栏；编辑/作废按记录自身承载版本执行，不要求当前指针相等。 */
  public void lockScopeForStorageWrite(FormVersionContext context) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select status, write_fenced from form_template_scopes where id = ? for share",
            context.scopeId());
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    Map<String, Object> row = rows.get(0);
    if (!"ACTIVE".equals(row.get("status"))) {
      throw new FormConflictException("表单范围已停用，仅保留历史查询");
    }
    if (Boolean.TRUE.equals(row.get("write_fenced"))) {
      throw new FormConflictException("表单范围已停写，请稍后重试");
    }
  }

  /**
   * 构造以请求携带结构版本为规格来源的重放上下文。
   *
   * <p>跨版本重放只替换规格与结构版本：范围、资源与条件严重级别仍取当前权威上下文。
   * 请求版本必须属于本范围且为 RECORDS 结构，已退役版本同样可用于核验历史请求。
   *
   * @param current 当前权威上下文
   * @param versionId 请求携带的结构版本 ID
   * @return 使用请求版本冻结规格的上下文
   */
  public FormVersionContext replayContext(FormVersionContext current, long versionId) {
    if (versionId == current.storageVersionId()) {
      return current;
    }
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select field_spec::text as field_spec, storage_kind from form_template_versions"
                + " where scope_id = ? and id = ?",
            current.scopeId(),
            versionId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求携带的结构版本不属于当前范围");
    }
    if (!"RECORDS".equals(rows.get(0).get("storage_kind"))) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求携带的结构版本不受支持");
    }
    String raw = (String) rows.get(0).get("field_spec");
    FormTemplateSpec spec = parseSpec(raw, current.spec().templateKey());
    return new FormVersionContext(
        current.scopeId(),
        current.scopeStatus(),
        current.sourceConfigId(),
        current.sourceInstance(),
        current.canonicalGitlabBaseUrl(),
        versionId,
        current.versionLabel(),
        current.versionStatus(),
        current.instanceTable(),
        current.recordTable(),
        spec,
        readFieldSpec(raw),
        current.projectId(),
        current.resourceType(),
        current.resourceId(),
        current.rootId(),
        current.severityLevel(),
        current.webUrl());
  }

  /**
   * 核对客户端携带的范围与结构版本。
   *
   * @param context 服务端权威上下文
   * @param scopeId 请求范围 ID
   * @param storageVersionId 请求结构版本 ID
   */
  public void requireExpected(FormVersionContext context, String scopeId, String storageVersionId) {
    long expectedScope = parsePositive(scopeId, "范围 ID");
    long expectedVersion = parsePositive(storageVersionId, "结构版本 ID");
    if (expectedScope != context.scopeId()) {
      throw new BizException(ResultCode.BAD_REQUEST, "范围与资源上下文不一致");
    }
    if (expectedVersion != context.storageVersionId()) {
      throw new FormConflictException("表单结构版本已变化，请刷新后重试");
    }
  }

  private void requireActiveScopesHealthy() {
    try {
      scopeValidationService.validateActiveScopes();
    } catch (IllegalStateException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围配置无效：" + error.getMessage());
    }
  }

  private String requireTemplateKey(String templateKey) {
    if (templateKey == null || templateKey.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "模板编码不能为空");
    }
    String key = templateKey.trim();
    if (!FormTemplateSpecCatalog.TEMPLATE_KEYS.contains(key)) {
      throw new BizException(ResultCode.BAD_REQUEST, "未知模板：" + key);
    }
    return key;
  }

  private String requireResourceType(String resourceType, String templateKey) {
    if (resourceType == null || resourceType.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "资源类型不能为空");
    }
    String type = resourceType.trim();
    String expected =
        FormTemplateSpecCatalog.CODE_REVIEW.equals(templateKey) ? "merge_request" : "issue";
    if (!expected.equals(type)) {
      throw new BizException(ResultCode.BAD_REQUEST, "模板与资源类型不匹配");
    }
    return type;
  }

  private String canonicalize(String gitlabBaseUrl) {
    try {
      return FormSourceUrlSupport.canonicalize(gitlabBaseUrl);
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, error.getMessage());
    }
  }

  private String canonicalStoredUrl(ScopeVersionRow row) {
    try {
      return FormSourceUrlSupport.canonicalize(row.canonicalGitlabBaseUrl());
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围来源地址无效：" + row.id());
    }
  }

  private List<Long> parseProjectIds(ScopeVersionRow row) {
    JsonNode node;
    try {
      node = objectMapper.readTree(row.projectIdsJson());
    } catch (Exception error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围项目集合无效：" + row.id());
    }
    if (node == null || !node.isArray()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围项目集合无效：" + row.id());
    }
    List<Long> projects = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.canConvertToLong() || item.asLong() <= 0) {
        throw new BizException(ResultCode.BAD_REQUEST, "表单范围包含非法项目：" + row.id());
      }
      projects.add(item.asLong());
    }
    return List.copyOf(projects);
  }

  private void validateTables(ScopeVersionRow row) {
    String expectedInstances = "form_s" + row.id() + "_v" + row.currentVersionId() + "_instances";
    String expectedRecords = "form_s" + row.id() + "_v" + row.currentVersionId() + "_records";
    if (!GENERATED_TABLE.matcher(row.instanceTable()).matches()
        || !GENERATED_TABLE.matcher(row.recordTable()).matches()
        || !expectedInstances.equals(row.instanceTable())
        || !expectedRecords.equals(row.recordTable())) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单版本表名与范围版本不一致");
    }
  }

  private void validateSource(long sourceConfigId, String canonicalUrl) {
    List<String> configured =
        jdbcTemplate.queryForList(
            "select web_base_url from gitlab_sync_configs where id = ?", String.class, sourceConfigId);
    if (configured.isEmpty() || configured.get(0) == null || configured.get(0).isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围来源配置不存在");
    }
    String canonical;
    try {
      canonical = FormSourceUrlSupport.canonicalize(configured.get(0));
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围来源地址配置无效");
    }
    if (!canonical.equals(canonicalUrl)) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围来源地址与当前配置不一致");
    }
  }

  public FormTemplateSpec parseSpec(String raw, String templateKey) {
    try {
      FormTemplateSpec spec = objectMapper.readValue(raw, FormTemplateSpec.class);
      if (spec.specVersion() != 1 || !templateKey.equals(spec.templateKey())) {
        throw new BizException(ResultCode.BAD_REQUEST, "表单结构版本规格不合法");
      }
      return spec;
    } catch (BizException error) {
      throw error;
    } catch (Exception error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单结构版本规格无法解析");
    }
  }

  public JsonNode readFieldSpec(String raw) {
    try {
      return objectMapper.readTree(raw);
    } catch (Exception error) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单结构版本规格无法解析");
    }
  }

  private String severityLevel(Long rootId) {
    if (rootId == null) {
      return "未设定";
    }
    List<String> values =
        jdbcTemplate.query(
            """
            select f.severity_level
              from issue_fact f
             where f.source_system = ?
               and f.source_instance = ?
               and f.issue_id = ?
               and f.deleted = false
               and """
                + FactReadVisibility.liveRevisionPredicate("f")
                + """
             order by f.id desc
             limit 1
            """,
            (rs, rowNum) -> rs.getString("severity_level"),
            GitlabResourceRootResolver.DEFAULT_SOURCE_SYSTEM,
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            rootId);
    return displaySeverity(values.isEmpty() ? null : values.get(0));
  }

  private String displaySeverity(String raw) {
    if (raw == null || raw.isBlank()) {
      return "未设定";
    }
    return switch (raw.trim()) {
      case "LEVEL1" -> "一级缺陷";
      case "LEVEL2" -> "二级缺陷";
      case "LEVEL3" -> "三级缺陷";
      case "SUGGESTION" -> "建议类";
      default -> raw.trim();
    };
  }

  private String resourceUrl(
      String sourceInstance, String canonicalUrl, String resourceType, long projectId, long resourceId) {
    if (resourceId > Integer.MAX_VALUE) {
      return null;
    }
    return "merge_request".equals(resourceType)
        ? resourceLinkService.mergeRequestUrl(
            sourceInstance, canonicalUrl, projectId, (int) resourceId)
        : resourceLinkService.issueUrl(
            sourceInstance, canonicalUrl, projectId, (int) resourceId);
  }

  private long parsePositive(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, label + "不能为空");
    }
    try {
      long value = Long.parseLong(raw.trim());
      if (value <= 0) {
        throw new NumberFormatException("non-positive");
      }
      return value;
    } catch (NumberFormatException error) {
      throw new BizException(ResultCode.BAD_REQUEST, label + "必须为正整数");
    }
  }

  private long requirePositive(Long value, String label) {
    if (value == null || value <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, label + "必须为正整数");
    }
    return value;
  }

  private record ScopeVersionRow(
      long id,
      String scopeKey,
      String status,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      String projectIdsJson,
      Long currentVersionId,
      String bindingHash,
      String versionLabel,
      String versionStatus,
      String storageKind,
      String instanceTable,
      String recordTable,
      String instanceMode,
      String fieldSpec) {}
}
