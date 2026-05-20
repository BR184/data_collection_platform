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
public class GitlabIssueLinkService {
  private final JdbcTemplate jdbcTemplate;
  private final String gitlabWebBaseUrl;
  private final Map<Long, Optional<String>> projectPathCache = new ConcurrentHashMap<>();

  public GitlabIssueLinkService(JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.gitlabWebBaseUrl = properties.getWebBaseUrl();
  }

  public String issueUrl(Long projectId, Integer issueIid) {
    String baseUrl = normalizeBaseUrl(gitlabWebBaseUrl);
    if (!StringUtils.hasText(baseUrl) || projectId == null || issueIid == null) {
      return null;
    }
    return projectPath(projectId)
        .map(path -> baseUrl + "/" + path + "/-/issues/" + issueIid)
        .orElse(null);
  }

  private Optional<String> projectPath(Long projectId) {
    return projectPathCache.computeIfAbsent(projectId, this::loadProjectPath);
  }

  private Optional<String> loadProjectPath(Long projectId) {
    try {
      String path =
          jdbcTemplate.queryForObject(
              """
              with recursive project_row as (
                select p.id,
                       nullif(btrim(p.path), '') as project_path,
                       p.namespace_id,
                       nullif(btrim(to_jsonb(p)->>'path_with_namespace'), '') as path_with_namespace,
                       nullif(btrim(to_jsonb(p)->>'full_path'), '') as full_path
                  from ods_gitlab_projects p
                 where p.id = ?
                   and coalesce(p.mirror_deleted, false) = false
                 limit 1
              ),
              namespace_chain as (
                select ns.id,
                       nullif(btrim(ns.path), '') as namespace_path,
                       nullif(to_jsonb(ns)->>'parent_id', '')::bigint as parent_id,
                       0 as depth
                  from ods_gitlab_namespaces ns
                  join project_row p on p.namespace_id = ns.id
                 where coalesce(ns.mirror_deleted, false) = false
                union all
                select parent.id,
                       nullif(btrim(parent.path), '') as namespace_path,
                       nullif(to_jsonb(parent)->>'parent_id', '')::bigint as parent_id,
                       child.depth + 1 as depth
                  from ods_gitlab_namespaces parent
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
              """,
              String.class,
              projectId);
      return Optional.ofNullable(TextQuerySupport.trimToNull(path));
    } catch (DataAccessException ignored) {
      return Optional.empty();
    }
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
