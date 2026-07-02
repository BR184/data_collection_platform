package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
    return queryDistinctMirrorTable(
        "projects",
        tableName ->
            """
            select name
              from %s
             where coalesce(mirror_deleted, false) = false
               and nullif(trim(name), '') is not null
             order by coalesce(last_activity_at, created_at, updated_at) desc nulls last
            """
                .formatted(tableName));
  }

  public List<String> loadLabelProjectNames() {
    // 评审录入里的“项目名称”来自 GitLab 标签，而不是 GitLab 项目表。
    // 标签格式为“项目：xxx”或“项目:xxx”，下拉只展示并提交 xxx。
    return loadLegacyLabelValues("项目");
  }

  public List<String> loadUserNames() {
    return queryDistinctMirrorTable(
        "users",
        tableName ->
            """
            select name
              from %s
             where coalesce(mirror_deleted, false) = false
               and nullif(trim(name), '') is not null
             order by coalesce(last_activity_on, created_at, updated_at) desc nulls last
            """
                .formatted(tableName));
  }

  public List<String> loadModuleNames() {
    return loadLegacyLabelValues("模块");
  }

  public List<String> loadMilestoneTitles() {
    return queryDistinctMirrorTable(
        "milestones",
        tableName ->
            """
            select title
              from %s
             where coalesce(mirror_deleted, false) = false
               and nullif(trim(title), '') is not null
             order by title
            """
                .formatted(tableName));
  }

  private List<String> queryDistinctMirrorTable(
      String sourceTableName, Function<String, String> sqlFactory) {
    String mirrorTableName =
        GitlabSourceInstanceSupport.buildMirrorTableName(sourceTableName);
    if (!mirrorTableExists(mirrorTableName)) {
      return List.of();
    }
    return queryDistinct(sqlFactory.apply(quoteIdentifier(mirrorTableName)), mirrorTableName);
  }

  private boolean mirrorTableExists(String tableName) {
    try {
      Boolean exists = jdbcTemplate.queryForObject("select to_regclass(?) is not null", Boolean.class, tableName);
      return Boolean.TRUE.equals(exists);
    } catch (DataAccessException error) {
      log.debug("Review data mirror option table {} is unavailable", tableName, error);
      return false;
    }
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
        queryDistinctMirrorTable(
            "labels",
            tableName ->
                """
                select title
                  from %s
                 where coalesce(mirror_deleted, false) = false
                   and nullif(trim(title), '') is not null
                 order by title
                """
                    .formatted(tableName));
    Set<String> values = new LinkedHashSet<>();
    for (String title : labelTitles) {
      for (String value : IssueFactNormalizationRules.parseLegacyLabelMap(List.of(title)).getOrDefault(groupName, List.of())) {
        values.add(normalizeLabelValue(groupName, value));
      }
    }
    return List.copyOf(values);
  }

  private String normalizeLabelValue(String groupName, String value) {
    if ("模块".equals(groupName)) {
      return ReviewDataModuleNameSupport.normalize(value);
    }
    return TextQuerySupport.normalizeDisplay(value);
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
