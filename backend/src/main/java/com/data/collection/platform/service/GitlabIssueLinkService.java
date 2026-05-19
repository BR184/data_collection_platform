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
    if (!StringUtils.hasText(gitlabWebBaseUrl) || projectId == null || issueIid == null) {
      return null;
    }
    return projectPath(projectId)
        .map(path -> gitlabWebBaseUrl.replaceAll("/+$", "") + "/" + path + "/-/issues/" + issueIid)
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
              select nullif(btrim(
                       coalesce(
                         nullif(to_jsonb(p)->>'path_with_namespace', ''),
                         nullif(to_jsonb(p)->>'full_path', ''),
                         nullif(to_jsonb(ns)->>'full_path', '') || '/' || nullif(p.path, ''),
                         nullif(ns.path, '') || '/' || nullif(p.path, ''),
                         nullif(p.path, '')
                       )
                     ), '') as project_path
                from ods_gitlab_projects p
                left join ods_gitlab_namespaces ns
                  on ns.id = p.namespace_id
                 and coalesce(ns.mirror_deleted, false) = false
               where p.id = ?
                 and coalesce(p.mirror_deleted, false) = false
               limit 1
              """,
              String.class,
              projectId);
      return Optional.ofNullable(TextQuerySupport.trimToNull(path));
    } catch (DataAccessException ignored) {
      return Optional.empty();
    }
  }
}
