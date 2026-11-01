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
 * 首次权威判定：在资源第一次进入消费时冻结 {@code COMMENT}/{@code FORM} 身份。
 *
 * <p>启用点只在这里参与一次：创建时间不早于启用点且项目落在 {@code ACTIVE} 范围内的议题登记
 * {@code FORM}；其余登记 {@code COMMENT}。此后消费只读持久权威，改变启用点、范围或重启都不会
 * 翻转既有身份。已有权威与 head 只核验，不重置状态、归属、租约或消费水位；缺 head 时才按真实
 * 权威修订与当前集合证据初始化。项目同时命中多个同模板范围时保持“待登记”，不擅自选择。
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
   * 为尚未登记权威的议题冻结首次身份。
   *
   * @param sourceInstance 来源实例
   * @param refs 议题及其创建时间
   * @param enabledAt 启用点；为空时视为未启用，只能登记 {@code COMMENT}
   * @param now 登记时刻
   */
  void registerMissing(
      String sourceInstance,
      Map<FormIssueInputResolver.IssueRef, LocalDateTime> refs,
      LocalDateTime enabledAt,
      LocalDateTime now) {
    if (refs == null || refs.isEmpty()) {
      return;
    }
    List<ScopeRow> scopes = loadScopes(sourceInstance);
    if (scopes.isEmpty()) {
      return;
    }
    Map<Long, Map<String, ScopeRow>> uniqueScopes = uniqueScopesByProject(scopes);
    for (Map.Entry<FormIssueInputResolver.IssueRef, LocalDateTime> entry : refs.entrySet()) {
      FormIssueInputResolver.IssueRef ref = entry.getKey();
      Map<String, ScopeRow> byTemplate = uniqueScopes.get(ref.projectId());
      if (byTemplate == null) {
        continue;
      }
      for (ScopeRow scope : byTemplate.values()) {
        Authority existing = findAuthority(scope, ref);
        if (existing != null) {
          ensureExistingFormHead(scope, ref, existing, now);
          continue;
        }
        boolean form = enabledAt != null
            && entry.getValue() != null
            && !entry.getValue().isBefore(enabledAt)
            && "ACTIVE".equals(scope.status())
            && scope.currentVersionId() != null;
        boolean inserted = register(scope, ref, form ? "FORM" : "COMMENT", now);
        if (!inserted && form) {
          // 并发登记可能先写入权威；只在身份一致时补齐缺失 head，不覆盖他人结果。
          ensureExistingFormHead(scope, ref, findAuthority(scope, ref), now);
        }
      }
    }
  }

  private boolean register(
      ScopeRow scope, FormIssueInputResolver.IssueRef ref, String authority, LocalDateTime now) {
    int inserted =
        jdbcTemplate.update(
            """
            insert into form_resource_authorities(
                source_config_id, source_instance, canonical_gitlab_base_url, project_id,
                resource_type, resource_id, template_key, scope_id, authority,
                authority_revision, effective_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
            on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
            do nothing
            """,
            scope.sourceConfigId(),
            scope.sourceInstance(),
            scope.canonicalUrl(),
            ref.projectId(),
            RESOURCE_TYPE,
            ref.issueIid(),
            scope.templateKey(),
            scope.scopeId(),
            authority,
            Timestamp.valueOf(now),
            Timestamp.valueOf(now),
            Timestamp.valueOf(now));
    if (inserted == 0) {
      return false;
    }
    if ("FORM".equals(authority)) {
      ensureHead(scope, ref, 1L, now);
    }
    return true;
  }

  /**
   * 已有 {@code FORM} 权威但缺少 head 时，按真实权威修订与当前集合证据补建；head 已存在则原样保留。
   */
  private void ensureExistingFormHead(
      ScopeRow scope,
      FormIssueInputResolver.IssueRef ref,
      Authority existing,
      LocalDateTime now) {
    if (existing == null
        || !"FORM".equals(existing.authority())
        || existing.scopeId() != scope.scopeId()
        || existing.revision() == null) {
      return;
    }
    ensureHead(scope, ref, existing.revision(), now);
  }

  private void ensureHead(
      ScopeRow scope, FormIssueInputResolver.IssueRef ref, long authorityRevision, LocalDateTime now) {
    if (scope.currentVersionId() == null
        || scope.sourceSetRevision() == null
        || scope.sourceSetHash() == null) {
      return;
    }
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            pending_storage_version_id, pending_source_set_revision, pending_source_set_hash,
            pending_authority_revision, status, fencing_version, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do nothing
        """,
        scope.sourceConfigId(),
        scope.sourceInstance(),
        scope.canonicalUrl(),
        ref.projectId(),
        RESOURCE_TYPE,
        ref.issueIid(),
        scope.templateKey(),
        scope.scopeId(),
        scope.currentVersionId(),
        scope.sourceSetRevision(),
        scope.sourceSetHash(),
        authorityRevision,
        Timestamp.valueOf(now),
        Timestamp.valueOf(now));
  }

  private Authority findAuthority(ScopeRow scope, FormIssueInputResolver.IssueRef ref) {
    List<Authority> rows =
        jdbcTemplate.query(
            """
            select authority, authority_revision, scope_id from form_resource_authorities
             where source_config_id = ? and project_id = ? and resource_type = ?
               and resource_id = ? and template_key = ?
            """,
            (rs, rowNum) ->
                new Authority(
                    rs.getString("authority"),
                    nullableLong(rs, "authority_revision"),
                    rs.getLong("scope_id")),
            scope.sourceConfigId(),
            ref.projectId(),
            RESOURCE_TYPE,
            ref.issueIid(),
            scope.templateKey());
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /** 项目 + 模板只能由一个范围决定首次身份；出现多个范围时返回空，交由消费侧待登记。 */
  private Map<Long, Map<String, ScopeRow>> uniqueScopesByProject(List<ScopeRow> scopes) {
    Map<Long, Map<String, List<ScopeRow>>> grouped = new HashMap<>();
    Set<Long> projects = new HashSet<>();
    for (ScopeRow scope : scopes) {
      for (Long projectId : scope.projectIds()) {
        projects.add(projectId);
        grouped
            .computeIfAbsent(projectId, key -> new LinkedHashMap<>())
            .computeIfAbsent(scope.templateKey(), key -> new ArrayList<>())
            .add(scope);
      }
    }
    Map<Long, Map<String, ScopeRow>> unique = new HashMap<>();
    for (Long projectId : projects) {
      Map<String, ScopeRow> byTemplate = new LinkedHashMap<>();
      for (Map.Entry<String, List<ScopeRow>> entry : grouped.get(projectId).entrySet()) {
        if (entry.getValue().size() == 1) {
          byTemplate.put(entry.getKey(), entry.getValue().getFirst());
        }
      }
      if (!byTemplate.isEmpty()) {
        unique.put(projectId, byTemplate);
      }
    }
    return unique;
  }

  private List<ScopeRow> loadScopes(String sourceInstance) {
    return jdbcTemplate.query(
        """
        select id, template_key, source_config_id, source_instance,
               canonical_gitlab_base_url, project_ids::text as project_ids, status,
               current_version_id, source_set_revision, source_set_hash
          from form_template_scopes
         where template_key in (?, ?)
           and source_instance = ?
           and resource_type = ?
           and status in ('ACTIVE', 'RETIRED')
         order by case status when 'ACTIVE' then 0 else 1 end, id
        """,
        (rs, rowNum) ->
            new ScopeRow(
                rs.getLong("id"),
                rs.getString("template_key"),
                rs.getLong("source_config_id"),
                rs.getString("source_instance"),
                rs.getString("canonical_gitlab_base_url"),
                parseProjectIds(rs.getString("project_ids")),
                rs.getString("status"),
                nullableLong(rs, "current_version_id"),
                nullableLong(rs, "source_set_revision"),
                rs.getString("source_set_hash")),
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        FormTemplateSpecCatalog.ISSUE_REPLY,
        sourceInstance,
        RESOURCE_TYPE);
  }

  private static Long nullableLong(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
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

  private record Authority(String authority, Long revision, long scopeId) {}

  private record ScopeRow(
      long scopeId,
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      Set<Long> projectIds,
      String status,
      Long currentVersionId,
      Long sourceSetRevision,
      String sourceSetHash) {}
}
