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
    String sql = buildSourceSql(baseSql, sourceInstance, incrementalPredicate, changedSince);
    Timestamp changedSinceArg = changedSince == null ? null : Timestamp.valueOf(changedSince);
    List<Object> args = changedSinceArg == null ? List.of() : List.of(changedSinceArg);
    long startedAt = sqlQueryMonitor.start();
    try {
      return changedSinceArg == null
          ? jdbcTemplate.query(sql, rowMapper)
          : jdbcTemplate.query(sql, rowMapper, changedSinceArg);
    } finally {
      sqlQueryMonitor.logIfSlow(operation, sql, args, startedAt);
    }
  }

  String buildSourceSql(
      String baseSql,
      String sourceInstance,
      String incrementalPredicate,
      LocalDateTime changedSince) {
    return GitlabSourceInstanceSupport.rewriteMirrorTableReferences(baseSql, sourceInstance)
        + (changedSince == null ? "" : " " + incrementalPredicate);
  }
}
