package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 范围实际绑定校验：首次迁移、发布激活和启动校验共用同一实现。 */
@Service
public class FormScopeValidationService {

  private static final String BINDING_LOCK_SQL =
      "select pg_advisory_xact_lock(hashtext('form_scope_binding')::bigint)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FormScopeValidationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** 校验候选绑定并持有范围激活锁到当前事务提交；调用方必须处于事务中。 */
  public void validateCandidateBinding(
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      List<Long> projectIds,
      long versionProjectId,
      String resourceType) {
    validateCandidateBinding(
        templateKey,
        sourceConfigId,
        sourceInstance,
        canonicalUrl,
        projectIds,
        versionProjectId,
        resourceType,
        null);
  }

  /**
   * 校验候选绑定，可排除自身范围的重叠判定。
   *
   * <p>已激活范围保存未变更绑定时必须排除自身，否则会被当成与自身重叠。
   */
  public void validateCandidateBinding(
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      List<Long> projectIds,
      long versionProjectId,
      String resourceType,
      Long excludedScopeId) {
    jdbcTemplate.execute(BINDING_LOCK_SQL);
    List<Long> normalized = normalizeProjectIds(projectIds);
    validateTemplateDefinition(templateKey);
    if (versionProjectId <= 0 || !normalized.contains(versionProjectId)) {
      throw new IllegalArgumentException("发布项目必须属于范围项目集合: " + versionProjectId);
    }
    validateSource(sourceConfigId, sourceInstance, canonicalUrl);
    validateNoActiveOverlap(
        templateKey, sourceConfigId, resourceType, normalized, excludedScopeId);
  }

  /** 启动和激活后复核所有 ACTIVE 范围；空平台直接通过。 */
  public void validateActiveScopes() {
    List<ScopeRow> scopes =
        jdbcTemplate.query(
            """
            select s.id, s.template_key, s.source_config_id, s.source_instance,
                   s.canonical_gitlab_base_url, s.project_ids::text as project_ids,
                   s.version_project_id, s.resource_type, s.current_version_id, s.binding_hash,
                   v.scope_id as version_scope_id, v.version_status, v.storage_kind, v.version_label
              from form_template_scopes s
              left join form_template_versions v on v.id = s.current_version_id
             where s.status = 'ACTIVE'
             order by s.id
            """,
            (rs, rowNum) ->
                new ScopeRow(
                    rs.getLong("id"),
                    rs.getString("template_key"),
                    rs.getLong("source_config_id"),
                    rs.getString("source_instance"),
                    rs.getString("canonical_gitlab_base_url"),
                    rs.getString("project_ids"),
                    rs.getLong("version_project_id"),
                    rs.getString("resource_type"),
                    rs.getObject("current_version_id", Long.class),
                    rs.getString("binding_hash"),
                    rs.getObject("version_scope_id", Long.class),
                    rs.getString("version_status"),
                    rs.getString("storage_kind"),
                    rs.getString("version_label")));
    Map<OverlapKey, Long> projectOwners = new HashMap<>();
    for (ScopeRow scope : scopes) {
      validateTemplateDefinition(scope.templateKey());
      if (scope.currentVersionId() == null
          || scope.versionScopeId() == null
          || scope.id() != scope.versionScopeId()
          || !"PUBLISHED".equals(scope.versionStatus())
          || !"RECORDS".equals(scope.storageKind())
          || scope.versionLabel() == null
          || scope.versionLabel().isBlank()) {
        throw new IllegalStateException("ACTIVE 范围当前指针无效: " + scope.id());
      }
      List<Long> projectIds = parseProjectIds(scope.projectIdsJson(), scope.id());
      if (projectIds.isEmpty()) {
        throw new IllegalStateException("ACTIVE 范围项目集合不能为空: " + scope.id());
      }
      if (!projectIds.contains(scope.versionProjectId())) {
        throw new IllegalStateException(
            "ACTIVE 范围发布项目不属于项目集合: scope="
                + scope.id()
                + ", versionProjectId="
                + scope.versionProjectId());
      }
      String canonicalUrl = canonicalizeStoredUrl(scope.canonicalGitlabBaseUrl(), scope.id());
      validateSource(scope.sourceConfigId(), scope.sourceInstance(), canonicalUrl);
      String expectedHash =
          FormScopeBinding.hash(
              objectMapper,
              FormScopeBinding.create(
                  objectMapper,
                  scope.templateKey(),
                  scope.sourceConfigId(),
                  scope.sourceInstance(),
                  canonicalUrl,
                  projectIds,
                  scope.versionProjectId(),
                  scope.resourceType()));
      if (!expectedHash.equals(scope.bindingHash())) {
        throw new IllegalStateException("ACTIVE 范围绑定哈希不一致: " + scope.id());
      }
      for (Long projectId : projectIds) {
        OverlapKey key =
            new OverlapKey(
                scope.templateKey(), scope.sourceConfigId(), scope.resourceType(), projectId);
        Long previous = projectOwners.putIfAbsent(key, scope.id());
        if (previous != null) {
          throw new IllegalStateException(
              "ACTIVE 范围在项目上重叠: scope=" + previous + "/" + scope.id() + ", project=" + projectId);
        }
      }
    }
  }

  /**
   * 列出旧记录覆盖预检使用的已绑定范围。
   *
   * <p>ACTIVE 范围与完整绑定的 DRAFT 范围都表示已规划的项目归属；DRAFT 只用于判定待迁移归属，
   * 不构成写入资格。来源、项目集合、版本项目、绑定哈希或来源配置不完整/不一致的 DRAFT 不计入覆盖。
   */
  public List<CoverageScope> listCoverageScopes(String templateKey, String resourceType) {
    List<CoverageScopeRow> rows =
        jdbcTemplate.query(
            """
            select id, status, template_key, resource_type, source_config_id, source_instance,
                   canonical_gitlab_base_url, project_ids::text as project_ids,
                   version_project_id, binding_hash
              from form_template_scopes
             where template_key = ? and resource_type = ?
               and status in ('ACTIVE', 'DRAFT')
             order by id
            """,
            (rs, rowNum) ->
                new CoverageScopeRow(
                    rs.getLong("id"),
                    rs.getString("status"),
                    rs.getString("template_key"),
                    rs.getString("resource_type"),
                    rs.getObject("source_config_id", Long.class),
                    rs.getString("source_instance"),
                    rs.getString("canonical_gitlab_base_url"),
                    rs.getString("project_ids"),
                    rs.getObject("version_project_id", Long.class),
                    rs.getString("binding_hash")),
            templateKey,
            resourceType);
    List<CoverageScope> scopes = new ArrayList<>();
    for (CoverageScopeRow row : rows) {
      CoverageScope scope =
          "ACTIVE".equals(row.status())
              ? requireActiveCoverageScope(row)
              : boundDraftCoverageScope(row);
      if (scope != null) {
        scopes.add(scope);
      }
    }
    return List.copyOf(scopes);
  }

  private CoverageScope requireActiveCoverageScope(CoverageScopeRow row) {
    if (row.sourceConfigId() == null
        || row.sourceInstance() == null
        || row.canonicalGitlabBaseUrl() == null
        || row.versionProjectId() == null
        || row.bindingHash() == null) {
      throw new IllegalStateException("ACTIVE 范围绑定字段不完整: " + row.id());
    }
    String canonicalUrl = canonicalizeStoredUrl(row.canonicalGitlabBaseUrl(), row.id());
    List<Long> projectIds = parseProjectIds(row.projectIdsJson(), row.id());
    if (projectIds.isEmpty() || !projectIds.contains(row.versionProjectId())) {
      throw new IllegalStateException("ACTIVE 范围项目集合或版本项目无效: " + row.id());
    }
    String expectedHash = coverageBindingHash(row, canonicalUrl, projectIds);
    if (!expectedHash.equals(row.bindingHash())) {
      throw new IllegalStateException("ACTIVE 范围绑定哈希不一致: " + row.id());
    }
    return new CoverageScope(row.id(), canonicalUrl, projectIds, row.bindingHash(), true);
  }

  private CoverageScope boundDraftCoverageScope(CoverageScopeRow row) {
    if (row.sourceConfigId() == null
        || row.sourceConfigId() <= 0
        || row.sourceInstance() == null
        || row.canonicalGitlabBaseUrl() == null
        || row.versionProjectId() == null
        || row.versionProjectId() <= 0
        || row.bindingHash() == null
        || row.bindingHash().isBlank()) {
      return null;
    }
    if (!GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE.equals(row.sourceInstance())) {
      return null;
    }
    List<Long> projectIds;
    String canonicalUrl;
    try {
      projectIds = parseProjectIds(row.projectIdsJson(), row.id());
      canonicalUrl = FormSourceUrlSupport.canonicalize(row.canonicalGitlabBaseUrl());
    } catch (IllegalArgumentException | IllegalStateException error) {
      return null;
    }
    if (projectIds.isEmpty() || !projectIds.contains(row.versionProjectId())) {
      return null;
    }
    try {
      if (!coverageBindingHash(row, canonicalUrl, projectIds).equals(row.bindingHash())) {
        return null;
      }
      validateSource(row.sourceConfigId(), row.sourceInstance(), canonicalUrl);
    } catch (IllegalArgumentException | IllegalStateException error) {
      return null;
    }
    return new CoverageScope(row.id(), canonicalUrl, projectIds, row.bindingHash(), false);
  }

  private String coverageBindingHash(
      CoverageScopeRow row, String canonicalUrl, List<Long> projectIds) {
    return FormScopeBinding.hash(
        objectMapper,
        FormScopeBinding.create(
            objectMapper,
            row.templateKey(),
            row.sourceConfigId(),
            row.sourceInstance(),
            canonicalUrl,
            projectIds,
            row.versionProjectId(),
            row.resourceType()));
  }

  public List<Long> normalizeProjectIds(List<Long> projectIds) {
    if (projectIds == null || projectIds.isEmpty()) {
      throw new IllegalArgumentException("项目集合不能为空");
    }
    Set<Long> normalized = new TreeSet<>();
    for (Long projectId : projectIds) {
      if (projectId == null || projectId <= 0) {
        throw new IllegalArgumentException("项目 ID 必须为正整数");
      }
      normalized.add(projectId);
    }
    return List.copyOf(normalized);
  }

  private void validateNoActiveOverlap(
      String templateKey,
      long sourceConfigId,
      String resourceType,
      List<Long> projectIds,
      Long excludedScopeId) {
    List<Map<String, Object>> scopes =
        jdbcTemplate.queryForList(
            """
            select id, project_ids::text as project_ids
              from form_template_scopes
             where status = 'ACTIVE' and template_key = ? and source_config_id = ?
               and resource_type = ?
            """,
            templateKey,
            sourceConfigId,
            resourceType);
    Set<Long> current = Set.copyOf(projectIds);
    for (Map<String, Object> scope : scopes) {
      long scopeId = ((Number) scope.get("id")).longValue();
      if (excludedScopeId != null && excludedScopeId == scopeId) {
        continue;
      }
      List<Long> occupied = parseProjectIds((String) scope.get("project_ids"), scopeId);
      for (Long projectId : occupied) {
        if (current.contains(projectId)) {
          throw new IllegalStateException(
              "ACTIVE 范围项目重叠: scope=" + scopeId + ", project=" + projectId);
        }
      }
    }
  }

  private void validateTemplateDefinition(String templateKey) {
    List<String> statuses =
        jdbcTemplate.queryForList(
            "select status from form_template_definitions where template_key = ?",
            String.class,
            templateKey);
    if (statuses.isEmpty() || !"ACTIVE".equals(statuses.get(0))) {
      throw new IllegalStateException("模板定义不存在或未启用: " + templateKey);
    }
  }

  private void validateSource(long sourceConfigId, String sourceInstance, String canonicalUrl) {
    if (sourceConfigId <= 0) {
      throw new IllegalStateException("范围来源配置 ID 非法: " + sourceConfigId);
    }
    if (!GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE.equals(sourceInstance)) {
      throw new IllegalStateException("范围来源实例与当前来源配置不一致: " + sourceInstance);
    }
    List<String> configured =
        jdbcTemplate.queryForList(
            "select web_base_url from gitlab_sync_configs where id = ?",
            String.class,
            sourceConfigId);
    if (configured.isEmpty()) {
      throw new IllegalStateException("范围来源配置不存在: " + sourceConfigId);
    }
    String configuredUrl = configured.get(0);
    if (configuredUrl == null || configuredUrl.isBlank()) {
      throw new IllegalStateException("范围来源配置缺少 Web 基址: " + sourceConfigId);
    }
    String canonical;
    try {
      canonical = FormSourceUrlSupport.canonicalize(configuredUrl);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("范围来源配置 Web 基址非法: " + configuredUrl, error);
    }
    if (!canonical.equals(canonicalUrl)) {
      throw new IllegalStateException("范围来源 Web 基址与已保存来源配置不一致");
    }
  }

  private String canonicalizeStoredUrl(String storedUrl, long scopeId) {
    try {
      return FormSourceUrlSupport.canonicalize(storedUrl);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("ACTIVE 范围 Web 基址非法: " + scopeId, error);
    }
  }

  private List<Long> parseProjectIds(String raw, long scopeId) {
    JsonNode node;
    try {
      node = objectMapper.readTree(raw);
    } catch (Exception error) {
      throw new IllegalStateException("范围项目集合不是合法 JSON: " + scopeId, error);
    }
    if (!node.isArray()) {
      throw new IllegalStateException("范围项目集合必须是 JSON 数组: " + scopeId);
    }
    Set<Long> unique = new HashSet<>();
    List<Long> projectIds = new ArrayList<>();
    for (JsonNode item : node) {
      if (!item.canConvertToLong() || item.asLong() <= 0) {
        throw new IllegalStateException("范围包含非法项目 ID: scope=" + scopeId + ", value=" + item);
      }
      if (!unique.add(item.asLong())) {
        throw new IllegalStateException("范围项目 ID 重复: scope=" + scopeId + ", project=" + item);
      }
      projectIds.add(item.asLong());
    }
    return List.copyOf(projectIds);
  }

  /** 覆盖预检中的范围归属；active=false 表示仅由完整绑定 DRAFT 声明待迁移归属。 */
  public record CoverageScope(
      long scopeId, String canonicalUrl, List<Long> projectIds, String bindingHash, boolean active) {
    public boolean covers(String rowUrl, long projectId) {
      return canonicalUrl.equals(rowUrl) && projectIds.contains(projectId);
    }
  }

  private record CoverageScopeRow(
      long id,
      String status,
      String templateKey,
      String resourceType,
      Long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      String projectIdsJson,
      Long versionProjectId,
      String bindingHash) {}

  private record ScopeRow(
      long id,
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalGitlabBaseUrl,
      String projectIdsJson,
      long versionProjectId,
      String resourceType,
      Long currentVersionId,
      String bindingHash,
      Long versionScopeId,
      String versionStatus,
      String storageKind,
      String versionLabel) {}

  private record OverlapKey(
      String templateKey, long sourceConfigId, String resourceType, long projectId) {}
}
