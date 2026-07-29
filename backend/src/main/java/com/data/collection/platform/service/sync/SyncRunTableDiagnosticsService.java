package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.sync.SyncRunTableStateDiagnostics;
import com.data.collection.platform.service.GitlabExternalDbService;
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
  private final GitlabExternalDbService externalDbService;

  public SyncRunTableDiagnosticsService(
      JdbcTemplate jdbcTemplate, GitlabExternalDbService externalDbService) {
    this.jdbcTemplate = jdbcTemplate;
    this.externalDbService = externalDbService;
  }

  public Map<String, Object> tableDiagnostics(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    CurrentRunDiagnostics currentRun = loadCurrentRun(config, sourceInstance);
    List<SyncRunTableStateDiagnostics> tables = loadTableDiagnostics(config, sourceInstance);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configId", config.getId());
    response.put("sourceInstance", sourceInstance);
    response.put("generatedAt", LocalDateTime.now().toString());
    response.put("status", currentRun == null ? "IDLE" : currentRun.status());
    response.put("message", "统一同步表任务诊断");
    response.put("currentRunDbId", currentRun == null ? null : currentRun.id());
    response.put("currentRunId", currentRun == null ? null : currentRun.runId());
    response.put(
        "resolvedWorkerCount", currentRun == null ? null : currentRun.resolvedWorkerCount());
    response.put(
        "directPoolMetrics",
        config.getSourceMode() == SourceMode.DIRECT
            ? externalDbService.directPoolMetrics(config.getId()).orElse(null)
            : null);
    response.put("tableCount", countStates(config, sourceInstance));
    response.put("dirtyTableCount", countDirtyStates(config, sourceInstance));
    response.put("pendingTaskCount", countCurrentRunTasks(currentRun, "QUEUED"));
    response.put("runningTaskCount", countCurrentRunTasks(currentRun, "RUNNING"));
    response.put("retryingTaskCount", countCurrentRunTasks(currentRun, "RETRYING"));
    response.put("failedTaskCount", countCurrentRunTasks(currentRun, "FAILED"));
    response.put("timedOutTaskCount", countCurrentRunTasks(currentRun, "TIMEOUT"));
    response.put("historicalFailedTaskCount", countHistoricalTasks(config, sourceInstance, "FAILED"));
    response.put("historicalTimedOutTaskCount", countHistoricalTasks(config, sourceInstance, "TIMEOUT"));
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
                join sync_runs active_run on active_run.id = active.run_id
               where active.config_id = state.config_id
                 and active.source_instance = state.source_instance
                 and active.source_table = state.source_table
                 and active.status in ('QUEUED', 'RUNNING', 'RETRYING')
                 and active_run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
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
               blocking.id as current_task_id,
               blocking.task_type as current_task_type,
               blocking.task_stage as current_task_stage,
               blocking.status as current_task_status,
               blocking.run_after as current_task_run_after,
               blocking.heartbeat_at as current_task_heartbeat_at,
               blocking.lease_until as current_task_lease_until,
               blocking.retry_count as current_task_retry_count,
               blocking.cursor_updated_at as current_task_cursor_updated_at,
               blocking.cursor_pk as current_task_cursor_pk,
               blocking.rows_scanned as current_task_rows_scanned,
               blocking.rows_applied as current_task_rows_applied,
               blocking.last_error as current_task_error
          from sync_run_table_states state
          left join lateral (
              select task.*, run.run_id as external_run_id
                from sync_run_table_tasks task
                join sync_runs run on run.id = task.run_id
               where task.config_id = state.config_id
                 and task.source_instance = state.source_instance
                 and task.source_table = state.source_table
                 and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
                 and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
               order by case task.status when 'RUNNING' then 0 when 'RETRYING' then 1 else 2 end,
                        task.started_at nulls last,
                        task.created_at desc,
                        task.id desc
               limit 1
          ) blocking on true
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
              nullableLong(rs.getObject("current_task_id")),
              rs.getString("current_task_type"),
              rs.getString("current_task_stage"),
              rs.getString("current_task_status"),
              toDateTime(rs.getTimestamp("current_task_run_after")),
              toDateTime(rs.getTimestamp("current_task_heartbeat_at")),
              toDateTime(rs.getTimestamp("current_task_lease_until")),
              nullableInt(rs.getObject("current_task_retry_count")),
              toDateTime(rs.getTimestamp("current_task_cursor_updated_at")),
              rs.getString("current_task_cursor_pk"),
              nullableLong(rs.getObject("current_task_rows_scanned")),
              nullableLong(rs.getObject("current_task_rows_applied")),
              rs.getString("current_task_error"));
        },
        config.getId(),
        sourceInstance);
  }

  private CurrentRunDiagnostics loadCurrentRun(
      GitlabSyncConfig config, String sourceInstance) {
    List<Map<String, Object>> runs =
        jdbcTemplate.queryForList(
            """
            select id, run_id, status, resolved_worker_count
              from sync_runs
             where config_id = ?
               and source_instance = ?
               and status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
             order by case status
                        when 'RUNNING' then 0
                        when 'CANCELLING' then 1
                        when 'RETRYING' then 2
                        when 'QUEUED' then 3
                        when 'SUBMITTED' then 4
                        else 5
                      end,
                      created_at asc,
                      id asc
             limit 1
            """,
            config.getId(),
            sourceInstance);
    if (runs == null || runs.isEmpty()) {
      return null;
    }
    Map<String, Object> run = runs.getFirst();
    return new CurrentRunDiagnostics(
        nullableLong(run.get("id")),
        stringValue(run.get("run_id")),
        stringValue(run.get("status")),
        nullableInt(run.get("resolved_worker_count")));
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

  private int countCurrentRunTasks(CurrentRunDiagnostics currentRun, String status) {
    if (currentRun == null || currentRun.id() == null) {
      return 0;
    }
    return count(
        """
        select count(*)
          from sync_run_table_tasks
         where run_id = ?
           and status = ?
        """,
        currentRun.id(),
        status);
  }

  private int countHistoricalTasks(
      GitlabSyncConfig config, String sourceInstance, String status) {
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
      return "行数未知";
    }
    long delta = sourceRows - mirrorRows;
    if (delta == 0) {
      return "源表与镜像表行数一致";
    }
    return "源表=" + sourceRows + "，镜像表=" + mirrorRows + "，差值=" + delta;
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

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private record CurrentRunDiagnostics(
      Long id, String runId, String status, Integer resolvedWorkerCount) {}
}
