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
  private static final String LEGACY_CROWN_CAD_REPOSITORY = "CrownCAD";
  private static final String LEGACY_DGM_REPOSITORY = "DGM";
  private static final String LEGACY_CROWN_CAD_FALLBACK_BASE_URL = "http://172.22.10.233";
  private static final String LEGACY_CROWN_CAD_PROJECT_PATH = "cloudcad/crowncad";
  private static final String LEGACY_DGM_MERGE_REQUEST_BASE_URL =
      "http://172.22.10.100/KernelGroup/DGM/merge_requests";
  private final JdbcTemplate jdbcTemplate;
  private final String gitlabWebBaseUrl;
  private final Map<ProjectPathCacheKey, Optional<String>> projectPathCache = new ConcurrentHashMap<>();
  private final Map<String, Optional<String>> sourceBaseUrlCache = new ConcurrentHashMap<>();
  private final Map<LegacyProjectWebUrlCacheKey, Optional<String>> legacyProjectWebUrlCache =
      new ConcurrentHashMap<>();

  public GitlabResourceLinkService(JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.gitlabWebBaseUrl = properties.getWebBaseUrl();
  }

  public String issueUrl(Long projectId, Integer issueIid) {
    return issueUrl(null, projectId, issueIid);
  }

  public String issueUrl(String sourceInstance, Long projectId, Integer issueIid) {
    return resourceUrl(sourceInstance, projectId, issueIid, "issues");
  }

  public String mergeRequestUrl(Long projectId, Integer mergeRequestIid) {
    return mergeRequestUrl(null, projectId, mergeRequestIid);
  }

  public String mergeRequestUrl(String sourceInstance, Long projectId, Integer mergeRequestIid) {
    return resourceUrl(sourceInstance, projectId, mergeRequestIid, "merge_requests");
  }

  public String legacyCodeReviewMergeRequestUrl(
      String sourceInstance, String repositoryName, Integer mergeRequestIid) {
    if (mergeRequestIid == null || mergeRequestIid <= 0) {
      return null;
    }
    String normalizedSource = TextQuerySupport.trimToNull(sourceInstance);
    String normalizedRepository = TextQuerySupport.trimToNull(repositoryName);
    if (equalsIgnoreCase(normalizedSource, "dgm")
        || equalsIgnoreCase(normalizedRepository, LEGACY_DGM_REPOSITORY)) {
      return LEGACY_DGM_MERGE_REQUEST_BASE_URL + "/" + mergeRequestIid;
    }
    if (normalizedRepository != null
        && !equalsIgnoreCase(normalizedRepository, LEGACY_CROWN_CAD_REPOSITORY)) {
      Optional<String> projectWebUrl = legacyProjectWebUrl(normalizedSource, normalizedRepository);
      if (projectWebUrl.isPresent()) {
        return projectWebUrl.get() + "/merge_requests/" + mergeRequestIid;
      }
    }
    String baseUrl = baseUrl(normalizedSource);
    if (!StringUtils.hasText(baseUrl)) {
      baseUrl = LEGACY_CROWN_CAD_FALLBACK_BASE_URL;
    }
    return baseUrl + "/" + LEGACY_CROWN_CAD_PROJECT_PATH + "/merge_requests/" + mergeRequestIid;
  }

  public void clearCache() {
    sourceBaseUrlCache.clear();
    projectPathCache.clear();
    legacyProjectWebUrlCache.clear();
  }

  private String resourceUrl(String sourceInstance, Long projectId, Integer iid, String resourcePath) {
    String baseUrl = baseUrl(sourceInstance);
    if (!StringUtils.hasText(baseUrl) || projectId == null || iid == null) {
      return null;
    }
    return projectPath(sourceInstance, projectId)
        .map(path -> baseUrl + "/" + path + "/-/" + resourcePath + "/" + iid)
        .orElse(null);
  }

  private Optional<String> projectPath(String sourceInstance, Long projectId) {
    return projectPathCache.computeIfAbsent(
        new ProjectPathCacheKey(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, projectId), this::loadProjectPath);
  }

  private String baseUrl(String sourceInstance) {
    Optional<String> sourceBaseUrl =
        sourceBaseUrlCache.computeIfAbsent(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, this::loadSourceBaseUrl);
    if (sourceBaseUrl.isPresent()) {
      return sourceBaseUrl.get();
    }
    return normalizeBaseUrl(gitlabWebBaseUrl);
  }

  private Optional<String> loadSourceBaseUrl(String sourceInstance) {
    try {
      String configured =
          jdbcTemplate.queryForObject(
              """
              select nullif(btrim(web_base_url), '')
                from gitlab_sync_configs
               order by case when source_instance = 'default' then 0 else 1 end, id
               limit 1
              """,
              String.class);
      return Optional.ofNullable(normalizeBaseUrl(configured));
    } catch (DataAccessException ignored) {
      return Optional.empty();
    }
  }

  private Optional<String> loadProjectPath(ProjectPathCacheKey key) {
    return loadProjectPath(key.projectId(), "ods_gitlab_projects", "ods_gitlab_namespaces");
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

  private Optional<String> legacyProjectWebUrl(String sourceInstance, String repositoryName) {
    return legacyProjectWebUrlCache.computeIfAbsent(
        new LegacyProjectWebUrlCacheKey(
            sourceInstance == null ? "" : sourceInstance.toLowerCase(), repositoryName.toLowerCase()),
        key -> loadLegacyProjectWebUrl(sourceInstance, repositoryName));
  }

  private Optional<String> loadLegacyProjectWebUrl(String sourceInstance, String repositoryName) {
    try {
      String preferredTable =
          StringUtils.hasText(sourceInstance) ? sourceInstance.trim().toLowerCase() + ".git_project" : "";
      String webUrl =
          jdbcTemplate.queryForObject(
              """
              select nullif(btrim(raw_payload ->> 'web_url'), '')
                from legacy_mysql_imported_rows
               where regexp_replace(lower(table_name), '^[^.]+\\.', '') = 'git_project'
                 and lower(coalesce(raw_payload ->> 'name', '')) = lower(?)
                 and nullif(btrim(raw_payload ->> 'web_url'), '') is not null
               order by case when lower(table_name) = ? then 0 else 1 end, table_name
               limit 1
              """,
              String.class,
              repositoryName,
              preferredTable);
      return Optional.ofNullable(normalizeBaseUrl(webUrl));
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
    String normalized = withScheme.replaceAll("/+$", "");
    if ("http://localhost".equalsIgnoreCase(normalized) || "https://localhost".equalsIgnoreCase(normalized)) {
      return null;
    }
    return normalized;
  }

  private boolean equalsIgnoreCase(String left, String right) {
    return left != null && right != null && left.equalsIgnoreCase(right);
  }

  private record ProjectPathCacheKey(String sourceInstance, Long projectId) {}

  private record LegacyProjectWebUrlCacheKey(String sourceInstance, String repositoryName) {}
}
