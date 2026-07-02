package com.data.collection.platform.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GitlabFactSourceQueryExecutor {
  private final JdbcTemplate jdbcTemplate;
  private final SqlQueryMonitor sqlQueryMonitor;

  GitlabFactSourceQueryExecutor(JdbcTemplate jdbcTemplate, SqlQueryMonitor sqlQueryMonitor) {
    this.jdbcTemplate = jdbcTemplate;
    this.sqlQueryMonitor = sqlQueryMonitor;
  }

  <T> List<T> query(
      String operation,
      String sourceInstance,
      String baseSql,
      String incrementalPredicate,
      LocalDateTime changedSince,
      RowMapper<T> rowMapper) {
    return query(operation, sourceInstance, baseSql, incrementalPredicate, changedSince, List.of(), rowMapper);
  }

  <T> List<T> query(
      String operation,
      String sourceInstance,
      String baseSql,
      String incrementalPredicate,
      LocalDateTime changedSince,
      List<Object> extraArgs,
      RowMapper<T> rowMapper) {
    String sql = buildSourceSql(baseSql, sourceInstance, incrementalPredicate, changedSince);
    Timestamp changedSinceArg = changedSince == null ? null : Timestamp.valueOf(changedSince);
    java.util.ArrayList<Object> args = new java.util.ArrayList<>();
    if (changedSinceArg != null) {
      args.add(changedSinceArg);
    }
    args.addAll(extraArgs == null ? List.of() : extraArgs);
    long startedAt = sqlQueryMonitor.start();
    try {
      return jdbcTemplate.query(sql, rowMapper, args.toArray());
    } finally {
      sqlQueryMonitor.logIfSlow(operation, sql, args, startedAt);
    }
  }

  String buildSourceSql(
      String baseSql,
      String sourceInstance,
      String incrementalPredicate,
      LocalDateTime changedSince) {
    return baseSql + (changedSince == null ? "" : " " + incrementalPredicate);
  }
}
