package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GitlabResourceLinkService {
  private final JdbcTemplate jdbcTemplate;
  private final String gitlabWebBaseUrl;
  private final Map<Long, Optional<String>> projectPathCache = new ConcurrentHashMap<>();
  private volatile java.util.List<String> projectMirrorTables;

  public GitlabResourceLinkService(JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.gitlabWebBaseUrl = properties.getWebBaseUrl();
  }

  public String issueUrl(Long projectId, Integer issueIid) {
    return resourceUrl(projectId, issueIid, "issues");
  }

  public String mergeRequestUrl(Long projectId, Integer mergeRequestIid) {
    return resourceUrl(projectId, mergeRequestIid, "merge_requests");
  }

  private String resourceUrl(Long projectId, Integer iid, String resourcePath) {
    String baseUrl = normalizeBaseUrl(gitlabWebBaseUrl);
    if (!StringUtils.hasText(baseUrl) || projectId == null || iid == null) {
      return null;
    }
    return projectPath(projectId)
        .map(path -> baseUrl + "/" + path + "/-/" + resourcePath + "/" + iid)
        .orElse(null);
  }

  private Optional<String> projectPath(Long projectId) {
    return projectPathCache.computeIfAbsent(projectId, this::loadProjectPath);
  }

  private Optional<String> loadProjectPath(Long projectId) {
    Optional<String> legacyPath = loadProjectPath(projectId, "ods_gitlab_projects", "ods_gitlab_namespaces");
    if (legacyPath.isPresent()) {
      return legacyPath;
    }
    for (String projectTable : projectMirrorTables()) {
      String namespaceTable = namespaceTableFor(projectTable);
      Optional<String> path = loadProjectPath(projectId, projectTable, namespaceTable);
      if (path.isPresent()) {
        return path;
      }
    }
    return Optional.empty();
  }

  private Optional<String> loadProjectPath(Long projectId, String projectTable, String namespaceTable) {
    try {
      String path =
          jdbcTemplate.queryForObject(
              quotedProjectPathSql(projectTable, namespaceTable),
              String.class,
              projectId);
      return Optional.ofNullable(TextQuerySupport.trimToNull(path));
    } catch (DataAccessException ignored) {
      return Optional.empty();
    }
  }

  private String quotedProjectPathSql(String projectTable, String namespaceTable) {
    String quotedProjectTable = quoteIdentifier(projectTable);
    String quotedNamespaceTable = quoteIdentifier(namespaceTable);
    return """
              with recursive project_row as (
                select p.id,
                       nullif(btrim(p.path), '') as project_path,
                       p.namespace_id,
                       nullif(btrim(to_jsonb(p)->>'path_with_namespace'), '') as path_with_namespace,
                       nullif(btrim(to_jsonb(p)->>'full_path'), '') as full_path
                  from %s p
                 where p.id = ?
                   and coalesce(p.mirror_deleted, false) = false
                 limit 1
              ),
              namespace_chain as (
                select ns.id,
                       nullif(btrim(ns.path), '') as namespace_path,
                       nullif(to_jsonb(ns)->>'parent_id', '')::bigint as parent_id,
                       0 as depth
                  from %s ns
                  join project_row p on p.namespace_id = ns.id
                 where coalesce(ns.mirror_deleted, false) = false
                union all
                select parent.id,
                       nullif(btrim(parent.path), '') as namespace_path,
                       nullif(to_jsonb(parent)->>'parent_id', '')::bigint as parent_id,
                       child.depth + 1 as depth
                  from %s parent
                  join namespace_chain child on child.parent_id = parent.id
                 where coalesce(parent.mirror_deleted, false) = false
                   and child.depth < 20
              ),
              namespace_path as (
                select string_agg(namespace_path, '/' order by depth desc) as full_path
                  from namespace_chain
                 where namespace_path is not null
              )
              select nullif(btrim(
                       coalesce(
                         p.path_with_namespace,
                         p.full_path,
                         nullif(concat_ws('/', nullif(np.full_path, ''), p.project_path), ''),
                         p.project_path
                       )
                     ), '') as project_path
                from project_row p
                left join namespace_path np on true
              """
        .formatted(quotedProjectTable, quotedNamespaceTable, quotedNamespaceTable);
  }

  private java.util.List<String> projectMirrorTables() {
    java.util.List<String> cached = projectMirrorTables;
    if (cached != null) {
      return cached;
    }
    try {
      java.util.List<String> tables =
          jdbcTemplate.queryForList(
              """
              select tablename
                from pg_tables
               where schemaname = 'public'
                 and tablename like 'ods_gitlab\\_%\\_projects' escape '\\'
                 and tablename <> 'ods_gitlab_projects'
               order by tablename
              """,
              String.class);
      projectMirrorTables = tables == null ? java.util.List.of() : tables;
    } catch (DataAccessException ignored) {
      projectMirrorTables = java.util.List.of();
    }
    return projectMirrorTables;
  }

  private String namespaceTableFor(String projectTable) {
    return projectTable.replaceFirst("_projects$", "_namespaces");
  }

  private String quoteIdentifier(String identifier) {
    if (!identifier.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("Invalid table identifier: " + identifier);
    }
    return "\"" + identifier + "\"";
  }

  private String normalizeBaseUrl(String baseUrl) {
    String trimmed = TextQuerySupport.trimToNull(baseUrl);
    if (trimmed == null) {
      return null;
    }
    String withScheme = trimmed.matches("(?i)^https?://.*") ? trimmed : "http://" + trimmed;
    return withScheme.replaceAll("/+$", "");
  }
}
