package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
public class ReviewDataMirrorOptionRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataMirrorOptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<String> loadProjectNames() {
    return queryDistinct(
        """
        select name
          from ods_gitlab_projects
         where coalesce(mirror_deleted, false) = false
           and nullif(trim(name), '') is not null
         order by coalesce(last_activity_at, created_at, updated_at) desc nulls last
        """,
        "ods_gitlab_projects");
  }

  public List<String> loadLabelProjectNames() {
    // 评审录入里的“项目名称”来自 GitLab 标签，而不是 GitLab 项目表。
    // 标签格式为“项目：xxx”或“项目:xxx”，下拉只展示并提交 xxx。
    return loadLegacyLabelValues("项目");
  }

  public List<String> loadUserNames() {
    return queryDistinct(
        """
        select name
          from ods_gitlab_users
         where coalesce(mirror_deleted, false) = false
           and nullif(trim(name), '') is not null
         order by coalesce(last_activity_on, created_at, updated_at) desc nulls last
        """,
        "ods_gitlab_users");
  }

  public List<String> loadModuleNames() {
    return loadLegacyLabelValues("模块");
  }

  public List<String> loadMilestoneTitles() {
    return queryDistinct(
        """
        select title
          from ods_gitlab_milestones
         where coalesce(mirror_deleted, false) = false
           and nullif(trim(title), '') is not null
         order by title
        """,
        "ods_gitlab_milestones");
  }

  private List<String> queryDistinct(String sql, String sourceName) {
    try {
      return jdbcTemplate.queryForList(sql, String.class);
    } catch (DataAccessException error) {
      log.debug("Review data mirror option source {} is unavailable", sourceName, error);
      return List.of();
    }
  }

  private List<String> loadLegacyLabelValues(String groupName) {
    List<String> labelTitles =
        queryDistinct(
            """
            select title
              from ods_gitlab_labels
             where coalesce(mirror_deleted, false) = false
               and nullif(trim(title), '') is not null
             order by title
            """,
            "ods_gitlab_labels");
    Set<String> values = new LinkedHashSet<>();
    for (String title : labelTitles) {
      values.addAll(IssueFactNormalizationRules.parseLegacyLabelMap(List.of(title)).getOrDefault(groupName, List.of()));
    }
    return List.copyOf(values);
  }
}
