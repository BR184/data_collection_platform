package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class IssueFactQueryService extends AbstractFactQueryService {
  private final JdbcTemplate jdbcTemplate;

  public IssueFactQueryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public <T> List<T> query(String selectSql, Map<String, String> filters, RowMapper<T> rowMapper) {
    StringBuilder sql = new StringBuilder(selectSql);
    List<Object> args = new ArrayList<>();
    applyCommonFilters(sql, args, filters == null ? Map.of() : filters);
    return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
  }

  public <T> List<T> query(String sql, List<Object> args, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, rowMapper, args.toArray());
  }

  public long count(String sql, List<Object> args) {
    Long total = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
    return total == null ? 0L : total;
  }

  private void applyCommonFilters(StringBuilder sql, List<Object> args, Map<String, String> filters) {
    appendEq(sql, args, "project_id", filters.get("projectId"), Long::parseLong);
    appendContains(sql, args, "project_name", filters.get("projectName"));
    appendContains(sql, args, "milestone_title", filters.get("milestoneTitle"));
    appendContains(sql, args, "testing_phase", filters.get("testingPhase"));
    appendContains(sql, args, "module_names", filters.get("moduleName"));
    appendContains(sql, args, "function_name", filters.get("functionName"));
    appendEq(sql, args, "severity_level", filters.get("severityLevel"), value -> value);
    appendEq(sql, args, "priority_level", filters.get("priorityLevel"), value -> value);
  }
}
