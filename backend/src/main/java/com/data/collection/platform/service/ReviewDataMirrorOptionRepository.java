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
    Set<String> modules = new LinkedHashSet<>();
    for (String title : labelTitles) {
      modules.addAll(IssueFactNormalizationRules.normalizeModuleNames(List.of(title)));
    }
    return List.copyOf(modules);
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
}
