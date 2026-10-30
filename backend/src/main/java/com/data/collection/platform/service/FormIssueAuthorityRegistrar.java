package com.data.collection.platform.service;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 首次权威登记。
 *
 * <p>启用点只在这里参与：创建时间不早于启用点、项目已登记 {@code ACTIVE} 议题表单范围的资源，首次
 * 出现时登记持久 {@code FORM} 权威与变更 head；之后每次消费只读该权威，不再看启用点或当前范围。
 * 已有 `COMMENT` 权威不改写，避免静默切源；已有 `FORM` 权威只补齐缺失 head。
 */
@Service
class FormIssueAuthorityRegistrar {
  private static final String RESOURCE_TYPE = "issue";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  FormIssueAuthorityRegistrar(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 为启用点之后且尚无权威的议题补齐首次 {@code FORM} 权威与 head。
   *
   * @param sourceInstance 来源实例
   * @param refs 议题及其创建时间
   * @param enabledAt 启用点；为空时不做任何登记
   * @param now 登记时刻
   */
  void registerMissing(
      String sourceInstance,
      Map<FormIssueInputResolver.IssueRef, LocalDateTime> refs,
      LocalDateTime enabledAt,
      LocalDateTime now) {
    if (enabledAt == null || refs == null || refs.isEmpty()) {
      return;
    }
    List<ScopeRow> scopes = loadActiveScopes(sourceInstance);
    if (scopes.isEmpty()) {
      return;
    }
    Map<Long, Map<Long, Set<Long>>> scopesByProject = new HashMap<>();
    for (ScopeRow scope : scopes) {
      for (Long projectId : scope.projectIds()) {
        scopesByProject
            .computeIfAbsent(projectId, key -> new LinkedHashMap<>())
            .computeIfAbsent(scope.scopeId(), key -> new HashSet<>())
            .add(scope.currentVersionId());
      }
    }
    for (Map.Entry<FormIssueInputResolver.IssueRef, LocalDateTime> entry : refs.entrySet()) {
      LocalDateTime createdAt = entry.getValue();
      if (createdAt == null || createdAt.isBefore(enabledAt)) {
        continue;
      }
      Map<Long, Set<Long>> projectScopes =
          scopesByProject.get(entry.getKey().projectId());
      if (projectScopes == null) {
        continue;
      }
      for (ScopeRow scope : scopes) {
        if (!projectScopes.containsKey(scope.scopeId())) {
          continue;
        }
        register(scope, entry.getKey(), now);
      }
    }
  }

  private void register(
      ScopeRow scope, FormIssueInputResolver.IssueRef ref, LocalDateTime now) {
    long resourceId = ref.issueIid();
    List<Long> inserted =
        jdbcTemplate.query(
            """
            insert into form_resource_authorities(
                source_config_id, source_instance, canonical_gitlab_base_url, project_id,
                resource_type, resource_id, template_key, scope_id, authority,
                authority_revision, effective_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, 'FORM', 1, ?, ?, ?)
            on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
            do nothing
            returning id
            """,
            (rs, rowNum) -> rs.getLong("id"),
            scope.sourceConfigId(),
            scope.sourceInstance(),
            scope.canonicalUrl(),
            ref.projectId(),
            RESOURCE_TYPE,
            resourceId,
            scope.templateKey(),
            scope.scopeId(),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now));
    if (inserted.isEmpty()) {
      List<Map<String, Object>> existing =
          jdbcTemplate.queryForList(
              """
              select scope_id, authority from form_resource_authorities
               where source_config_id = ? and project_id = ? and resource_type = ?
                 and resource_id = ? and template_key = ?
              """,
              scope.sourceConfigId(),
              ref.projectId(),
              RESOURCE_TYPE,
              resourceId,
              scope.templateKey());
      if (existing.isEmpty()
          || !"FORM".equals(existing.getFirst().get("authority"))
          || ((Number) existing.getFirst().get("scope_id")).longValue() != scope.scopeId()) {
        return;
      }
    }
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            pending_storage_version_id, pending_authority_revision, status, fencing_version,
            created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, 1, 'PENDING', 0, ?, ?)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do update set scope_id = excluded.scope_id, status = 'PENDING',
                      updated_at = excluded.updated_at
        """,
        scope.sourceConfigId(),
        scope.sourceInstance(),
        scope.canonicalUrl(),
        ref.projectId(),
        RESOURCE_TYPE,
        resourceId,
        scope.templateKey(),
        scope.scopeId(),
        scope.currentVersionId(),
        Timestamp.valueOf(now),
        Timestamp.valueOf(now));
  }

  private List<ScopeRow> loadActiveScopes(String sourceInstance) {
    return jdbcTemplate.query(
        """
        select id, template_key, source_config_id, source_instance,
               canonical_gitlab_base_url, project_ids::text as project_ids, current_version_id
          from form_template_scopes
         where template_key in (?, ?)
           and source_instance = ?
           and resource_type = ?
           and status = 'ACTIVE'
           and current_version_id is not null
         order by id
        """,
        (rs, rowNum) ->
            new ScopeRow(
                rs.getLong("id"),
                rs.getString("template_key"),
                rs.getLong("source_config_id"),
                rs.getString("source_instance"),
                rs.getString("canonical_gitlab_base_url"),
                parseProjectIds(rs.getString("project_ids")),
                rs.getLong("current_version_id")),
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        FormTemplateSpecCatalog.ISSUE_REPLY,
        sourceInstance,
        RESOURCE_TYPE);
  }

  private Set<Long> parseProjectIds(String raw) {
    Set<Long> ids = new HashSet<>();
    if (!StringUtils.hasText(raw)) {
      return ids;
    }
    try {
      JsonNode node = objectMapper.readTree(raw);
      if (node.isArray()) {
        for (JsonNode item : node) {
          if (item.canConvertToLong()) {
            ids.add(item.asLong());
          }
        }
      }
    } catch (Exception error) {
      throw new IllegalStateException("表单范围项目集合无法解析", error);
    }
    return ids;
  }

  private record ScopeRow(
      long scopeId,
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      Set<Long> projectIds,
      long currentVersionId) {}
}
