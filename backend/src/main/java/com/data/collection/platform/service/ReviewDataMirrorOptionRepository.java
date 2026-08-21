package com.data.collection.platform.service;

import com.data.collection.platform.common.SqlIdentifierSupport;
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
  private static final long LEGACY_CC_REVIEW_PROJECT_ID = 9L;
  private static final long LEGACY_DGM_REVIEW_PROJECT_ID = 79L;

  private final JdbcTemplate jdbcTemplate;

  public ReviewDataMirrorOptionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<String> loadLabelProjectNames() {
    // 评审录入里的“项目名称”来自 GitLab 标签，而不是 GitLab 项目表。
    // 标签格式为“项目：xxx”或“项目:xxx”，下拉只展示并提交 xxx。
    return loadProjectLabelValues();
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
    // 对齐老平台 GitLabApiTool.getAllModules：只读取 CC(9) 与 DGM(79) 的全角“模块：”标签。
    // 录入候选与列表筛选候选相互独立；这里不解析工具箱、ASCII 冒号或连字符。
    return queryDistinctMirrorTable(
        "labels",
        tableName ->
            """
            select btrim(substring(title from 4)) as module_name
              from %s
             where coalesce(mirror_deleted, false) = false
               and project_id in (%d, %d)
               and title like '模块：%%'
               and nullif(btrim(substring(title from 4)), '') is not null
             group by btrim(substring(title from 4))
             order by lower(btrim(substring(title from 4)))
            """
                .formatted(tableName, LEGACY_CC_REVIEW_PROJECT_ID, LEGACY_DGM_REVIEW_PROJECT_ID));
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
    return queryDistinct(sqlFactory.apply(SqlIdentifierSupport.quoteIdentifier(mirrorTableName)), mirrorTableName);
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

  private List<String> loadProjectLabelValues() {
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
      for (String value : IssueFactNormalizationRules.parseLegacyLabelMap(List.of(title)).getOrDefault("项目", List.of())) {
        values.add(TextQuerySupport.normalizeDisplay(value));
      }
    }
    return List.copyOf(values);
  }

}
