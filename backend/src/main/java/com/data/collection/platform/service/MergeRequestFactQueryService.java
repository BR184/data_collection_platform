package com.data.collection.platform.service;

import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.service.read.VersionedFactSql;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class MergeRequestFactQueryService extends AbstractFactQueryService {
  private final JdbcTemplate jdbcTemplate;
  private final SqlQueryMonitor sqlQueryMonitor;

  public MergeRequestFactQueryService(JdbcTemplate jdbcTemplate, SqlQueryMonitor sqlQueryMonitor) {
    this.jdbcTemplate = jdbcTemplate;
    this.sqlQueryMonitor = sqlQueryMonitor;
  }

  public <T> List<T> query(FactReadSelection selection, String selectSql, Map<String, String> filters, RowMapper<T> rowMapper) {
    StringBuilder sql = new StringBuilder(selectSql);
    List<Object> args = new ArrayList<>();
    applyCommonFilters(sql, args, filters == null ? Map.of() : filters);
    return queryWithMonitoring(selection, "merge-request-fact-filter-query", sql.toString(), args, rowMapper);
  }

  public <T> List<T> query(FactReadSelection selection, String sql, List<Object> args, RowMapper<T> rowMapper) {
    return queryWithMonitoring(selection, "merge-request-fact-query", sql, args, rowMapper);
  }

  public long count(FactReadSelection selection, String sql, List<Object> args) {
    VersionedFactSql.Query query = VersionedFactSql.bind(selection, sql, args);
    long startedAt = sqlQueryMonitor.start();
    try {
      Long total = jdbcTemplate.queryForObject(query.sql(), Long.class, query.arguments().toArray());
      return total == null ? 0L : total;
    } finally {
      sqlQueryMonitor.logIfSlow("merge-request-fact-count", sql, args, startedAt);
    }
  }

  private <T> List<T> queryWithMonitoring(
      FactReadSelection selection, String operation, String sql, List<Object> args, RowMapper<T> rowMapper) {
    VersionedFactSql.Query query = VersionedFactSql.bind(selection, sql, args);
    long startedAt = sqlQueryMonitor.start();
    try {
      return jdbcTemplate.query(query.sql(), rowMapper, query.arguments().toArray());
    } finally {
      sqlQueryMonitor.logIfSlow(operation, sql, args, startedAt);
    }
  }

  private void applyCommonFilters(StringBuilder sql, List<Object> args, Map<String, String> filters) {
    appendEq(sql, args, "project_id", filters.get("projectId"), Long::parseLong);
    appendContains(sql, args, "project_name", filters.get("projectName"));
    appendContains(sql, args, "repository_name", filters.get("repositoryName"));
    appendContains(sql, args, "target_branch", filters.get("targetBranch"));
    appendContains(sql, args, "module_name", filters.get("moduleName"));
    appendContains(sql, args, "author_name", filters.get("author"));
    appendEq(
        sql,
        args,
        "source_instance",
        filters.get("sourceInstance"),
        GitlabSourceInstanceSupport::normalizeSourceInstance);
    appendEq(sql, args, "merge_request_iid", filters.get("mergeRequestIid"), Long::parseLong);
    appendDateFrom(sql, args, "merged_at_source", filters.get("mergedAtStart"));
    appendDateTo(sql, args, "merged_at_source", filters.get("mergedAtEnd"));
  }
}
