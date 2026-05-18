package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunTableStateDiagnostics;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunTableDiagnosticsService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunTableDiagnosticsService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> tableDiagnostics(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<SyncRunTableStateDiagnostics> tables = loadTableDiagnostics(config, sourceInstance);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configId", config.getId());
    response.put("sourceInstance", sourceInstance);
    response.put("generatedAt", LocalDateTime.now().toString());
    response.put("status", diagnosticsStatus(config, sourceInstance));
    response.put("message", "Unified sync run table diagnostics");
    response.put("tableCount", countStates(config, sourceInstance));
    response.put("dirtyTableCount", countDirtyStates(config, sourceInstance));
    response.put("pendingTaskCount", countTasks(config, sourceInstance, "QUEUED"));
    response.put("runningTaskCount", countTasks(config, sourceInstance, "RUNNING"));
    response.put("retryingTaskCount", countTasks(config, sourceInstance, "RETRYING"));
    response.put("failedTaskCount", countTasks(config, sourceInstance, "FAILED"));
    response.put("timedOutTaskCount", countTasks(config, sourceInstance, "TIMEOUT"));
    response.put("tables", tables);
    return response;
  }

  public List<String> retryableTables(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return jdbcTemplate.queryForList(
        """
        select state.source_table
          from sync_run_table_states state
          left join lateral (
              select task.status
                from sync_run_table_tasks task
               where task.config_id = state.config_id
                 and task.source_instance = state.source_instance
                 and task.source_table = state.source_table
               order by task.created_at desc, task.id desc
               limit 1
          ) latest on true
         where state.config_id = ?
           and state.source_instance = ?
           and state.sync_enabled = true
           and (state.dirty_flag = true or latest.status in ('FAILED', 'TIMEOUT'))
           and not exists (
              select 1
                from sync_run_table_tasks active
               where active.config_id = state.config_id
                 and active.source_instance = state.source_instance
                 and active.source_table = state.source_table
                 and active.status in ('QUEUED', 'RUNNING', 'RETRYING')
           )
         order by state.source_table asc
        """,
        String.class,
        config.getId(),
        sourceInstance);
  }

  private List<SyncRunTableStateDiagnostics> loadTableDiagnostics(GitlabSyncConfig config, String sourceInstance) {
    return jdbcTemplate.query(
        """
        select state.source_table,
               state.mirror_table,
               state.primary_key_columns,
               state.updated_at_column,
               state.row_strategy,
               state.sync_enabled,
               state.dirty_flag,
               state.dirty_reason,
               blocking.external_run_id as blocking_run_id,
               state.last_full_verified_at,
               state.last_success_at,
               state.last_watermark_at,
               state.last_cursor_pk,
               state.source_row_count,
               state.mirror_row_count,
               state.schema_fingerprint,
               state.last_error,
               state.retry_count,
               latest.task_type as latest_task_type,
               latest.status as latest_task_status,
               latest.run_after as latest_task_run_after,
               latest.heartbeat_at as latest_task_heartbeat_at,
               latest.lease_until as latest_task_lease_until,
               latest.rows_scanned as latest_task_rows_scanned,
               latest.rows_applied as latest_task_rows_applied,
               latest.last_error as latest_task_error
          from sync_run_table_states state
          left join lateral (
              select task.*, run.run_id as external_run_id
                from sync_run_table_tasks task
                join sync_runs run on run.id = task.run_id
               where task.config_id = state.config_id
                 and task.source_instance = state.source_instance
                 and task.source_table = state.source_table
                 and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
               order by case task.status when 'RUNNING' then 0 when 'RETRYING' then 1 else 2 end,
                        task.started_at nulls last,
                        task.created_at desc,
                        task.id desc
               limit 1
          ) blocking on true
          left join lateral (
              select task.*
                from sync_run_table_tasks task
               where task.config_id = state.config_id
                 and task.source_instance = state.source_instance
                 and task.source_table = state.source_table
               order by task.created_at desc, task.id desc
               limit 1
          ) latest on true
         where state.config_id = ?
           and state.source_instance = ?
         order by state.dirty_flag desc,
                  state.updated_at desc nulls last,
                  state.source_table asc
        """,
        (rs, rowNum) -> {
          Long sourceRows = nullableLong(rs.getObject("source_row_count"));
          Long mirrorRows = nullableLong(rs.getObject("mirror_row_count"));
          return new SyncRunTableStateDiagnostics(
              rs.getString("source_table"),
              rs.getString("mirror_table"),
              rs.getString("primary_key_columns"),
              rs.getString("updated_at_column"),
              rs.getString("row_strategy"),
              rs.getBoolean("sync_enabled"),
              rs.getBoolean("dirty_flag"),
              rs.getString("dirty_reason"),
              rs.getString("blocking_run_id"),
              toDateTime(rs.getTimestamp("last_full_verified_at")),
              toDateTime(rs.getTimestamp("last_success_at")),
              toDateTime(rs.getTimestamp("last_watermark_at")),
              rs.getString("last_cursor_pk"),
              sourceRows,
              mirrorRows,
              rs.getString("schema_fingerprint"),
              rs.getString("last_error"),
              nullableInt(rs.getObject("retry_count")),
              driftSummary(sourceRows, mirrorRows),
              rs.getString("latest_task_type"),
              rs.getString("latest_task_status"),
              toDateTime(rs.getTimestamp("latest_task_run_after")),
              toDateTime(rs.getTimestamp("latest_task_heartbeat_at")),
              toDateTime(rs.getTimestamp("latest_task_lease_until")),
              nullableLong(rs.getObject("latest_task_rows_scanned")),
              nullableLong(rs.getObject("latest_task_rows_applied")),
              rs.getString("latest_task_error"));
        },
        config.getId(),
        sourceInstance);
  }

  private String diagnosticsStatus(GitlabSyncConfig config, String sourceInstance) {
    List<String> statuses =
        jdbcTemplate.queryForList(
            """
            select status
              from sync_runs
             where config_id = ?
               and source_instance = ?
               and status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
             order by case status when 'RUNNING' then 0 when 'CANCELLING' then 1 else 2 end,
                      created_at asc,
                      id asc
             limit 1
            """,
            String.class,
            config.getId(),
            sourceInstance);
    return statuses == null || statuses.isEmpty() ? "IDLE" : statuses.getFirst();
  }

  private int countStates(GitlabSyncConfig config, String sourceInstance) {
    return count(
        "select count(*) from sync_run_table_states where config_id = ? and source_instance = ?",
        config.getId(),
        sourceInstance);
  }

  private int countDirtyStates(GitlabSyncConfig config, String sourceInstance) {
    return count(
        "select count(*) from sync_run_table_states where config_id = ? and source_instance = ? and dirty_flag = true",
        config.getId(),
        sourceInstance);
  }

  private int countTasks(GitlabSyncConfig config, String sourceInstance, String status) {
    return count(
        """
        select count(*)
          from sync_run_table_tasks
         where config_id = ?
           and source_instance = ?
           and status = ?
        """,
        config.getId(),
        sourceInstance,
        status);
  }

  private int count(String sql, Object... args) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
  }

  private static String driftSummary(Long sourceRows, Long mirrorRows) {
    if (sourceRows == null || mirrorRows == null) {
      return "row count unknown";
    }
    long delta = sourceRows - mirrorRows;
    if (delta == 0) {
      return "source and mirror row counts match";
    }
    return "source=" + sourceRows + ", mirror=" + mirrorRows + ", delta=" + delta;
  }

  private static LocalDateTime toDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private static Long nullableLong(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static Integer nullableInt(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }
}
