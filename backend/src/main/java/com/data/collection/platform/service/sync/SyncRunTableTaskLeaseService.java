package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunTableTaskLeaseService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunTableTaskLeaseService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public int recoverTimedOutTasks() {
    int orphaned = terminalizeActiveTasksForTerminalRuns();
    int retried =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'QUEUED',
                   retry_count = retry_count + 1,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   last_error = '表任务租约超时，已重新排队',
                   run_after = current_timestamp,
                   updated_at = current_timestamp
             where status = 'RUNNING'
               and lease_until is not null
               and lease_until < current_timestamp
               and retry_count < max_retry_count
            """);
    int timedOut =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'TIMEOUT',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   last_error = '表任务租约超时',
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where status = 'RUNNING'
               and lease_until is not null
               and lease_until < current_timestamp
               and retry_count >= max_retry_count
            """);
    int resetRegistries = resetRegistrySyncingStatusWithoutActiveTasks();
    return orphaned + retried + timedOut + resetRegistries;
  }

  public boolean isRunCancellationRequested(Long runId) {
    if (runId == null) {
      return false;
    }
    try {
      Boolean cancelled =
          jdbcTemplate.queryForObject(
              """
              select cancel_requested or status in ('CANCELLING', 'CANCELLED')
                from sync_runs
               where id = ?
              """,
              Boolean.class,
              runId);
      return Boolean.TRUE.equals(cancelled);
    } catch (EmptyResultDataAccessException ex) {
      return false;
    }
  }

  public void cancelQueuedTasks(Long runId) {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks
           set status = 'CANCELLED',
               last_error = coalesce(last_error, '同步运行已取消'),
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where run_id = ?
           and status = 'QUEUED'
        """,
        runId);
  }

  public int cancelQueuedAndRetryingTasks(Long runId) {
    return jdbcTemplate.update(
        """
        update sync_run_table_tasks
           set status = 'CANCELLED',
               last_error = coalesce(last_error, '同步运行已取消'),
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where run_id = ?
           and status in ('QUEUED', 'RETRYING')
        """,
        runId);
  }

  public int cancelQueuedRetryingAndStaleRunningTasks(Long runId) {
    int cancelled =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'CANCELLED',
                   last_error = coalesce(last_error, '同步运行已取消'),
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where run_id = ?
               and (
                 status in ('QUEUED', 'RETRYING')
                 or (status = 'RUNNING' and (lease_until is null or lease_until < current_timestamp))
               )
            """,
            runId);
    resetRegistrySyncingStatusWithoutActiveTasks();
    return cancelled;
  }

  public boolean hasLiveRunningTask(Long runId) {
    if (runId == null) {
      return false;
    }
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from sync_run_table_tasks
             where run_id = ?
               and status = 'RUNNING'
               and lease_until is not null
               and lease_until >= current_timestamp
            """,
            Integer.class,
            runId);
    return count != null && count > 0;
  }

  public int cancelActiveTasksForRun(Long runId) {
    int cancelled =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'CANCELLED',
                   last_error = coalesce(last_error, '同步运行已取消'),
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where run_id = ?
               and status in ('QUEUED', 'RUNNING', 'RETRYING')
            """,
            runId);
    resetRegistrySyncingStatusWithoutActiveTasks();
    return cancelled;
  }

  public int terminalizeActiveTasksForRun(Long runId, SyncRunStatus runStatus, String message) {
    if (runId == null || runStatus == null || !SyncRunStateMachine.isTerminal(runStatus)) {
      return 0;
    }
    int updated =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = ?,
                   last_error = coalesce(last_error, ?),
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where run_id = ?
               and status in ('QUEUED', 'RUNNING', 'RETRYING')
            """,
            taskTerminalStatus(runStatus),
            terminalTaskMessage(runStatus, message),
            runId);
    resetRegistrySyncingStatusWithoutActiveTasks();
    return updated;
  }

  public int terminalizeActiveTasksForTerminalRuns() {
    int updated =
        jdbcTemplate.update(
            """
            update sync_run_table_tasks task
               set status = case
                     when run.status = 'TIMEOUT' then 'TIMEOUT'
                     when run.status = 'FAILED' then 'FAILED'
                     else 'CANCELLED'
                   end,
                   last_error = coalesce(task.last_error, 'Parent sync run is already terminal: ' || run.status),
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
              from sync_runs run
             where task.run_id = run.id
               and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
               and run.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'MERGED')
            """);
    if (updated > 0) {
      resetRegistrySyncingStatusWithoutActiveTasks();
    }
    return updated;
  }

  private int resetRegistrySyncingStatusWithoutActiveTasks() {
    return jdbcTemplate.update(
        """
        update sys_table_registry registry
           set sync_status = 'IDLE',
               updated_at = current_timestamp
         where registry.sync_status = 'SYNCING'
           and not exists (
             select 1
               from sync_run_table_tasks task
               join sync_runs run on run.id = task.run_id
              where task.config_id = registry.config_id
                and task.source_table = registry.source_table_name
                and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
                and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
           )
        """);
  }

  private String taskTerminalStatus(SyncRunStatus runStatus) {
    return switch (runStatus) {
      case FAILED -> SyncRunStatus.FAILED.name();
      case TIMEOUT -> SyncRunStatus.TIMEOUT.name();
      default -> SyncRunStatus.CANCELLED.name();
    };
  }

  private String terminalTaskMessage(SyncRunStatus runStatus, String message) {
    if (message != null && !message.isBlank()) {
      return message;
    }
    return "Parent sync run finished with status " + runStatus.name();
  }

  public SyncRunTableTask claimNextQueuedTask(Long runId, String owner, int leaseSeconds) {
    try {
      return jdbcTemplate.queryForObject(
          """
          update sync_run_table_tasks
             set status = 'RUNNING',
                 lease_owner = ?,
                 lease_until = current_timestamp + (? * interval '1 second'),
                 heartbeat_at = current_timestamp,
                 started_at = coalesce(started_at, current_timestamp),
                 updated_at = current_timestamp
           where id = (
             select candidate.id
               from sync_run_table_tasks candidate
              where candidate.run_id = ?
                and candidate.status = 'QUEUED'
              order by candidate.run_after asc, candidate.created_at asc, candidate.id asc
              for update skip locked
              limit 1
           )
           returning *
          """,
          this::mapTask,
          owner,
          Math.max(1, leaseSeconds),
          runId);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  public void finishTask(Long taskId, Long rowsScanned, Long rowsApplied, String status, String errorMessage) {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks
           set status = ?,
               rows_scanned = coalesce(?, rows_scanned),
               rows_applied = coalesce(?, rows_applied),
               last_error = ?,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = ?
        """,
        status,
        rowsScanned,
        rowsApplied,
        errorMessage,
        taskId);
  }

  private SyncRunTableTask mapTask(ResultSet rs, int rowNum) throws SQLException {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(rs.getLong("id"));
    task.setRunId(rs.getLong("run_id"));
    task.setConfigId(rs.getLong("config_id"));
    task.setStateId(rs.getObject("state_id") == null ? null : rs.getLong("state_id"));
    task.setSourceInstance(rs.getString("source_instance"));
    task.setSourceTable(rs.getString("source_table"));
    task.setMirrorTable(rs.getString("mirror_table"));
    task.setTaskType(rs.getString("task_type"));
    task.setStatus(SyncRunStatus.valueOf(rs.getString("status")));
    task.setRowStrategy(rs.getString("row_strategy"));
    task.setWatermarkAt(toDateTime(rs.getTimestamp("watermark_at")));
    task.setCursorUpdatedAt(toDateTime(rs.getTimestamp("cursor_updated_at")));
    task.setCursorPk(rs.getString("cursor_pk"));
    task.setLookupColumn(rs.getString("lookup_column"));
    task.setLookupValue(rs.getString("lookup_value"));
    task.setBatchSize(rs.getInt("batch_size"));
    task.setRunAfter(toDateTime(rs.getTimestamp("run_after")));
    task.setLeaseOwner(rs.getString("lease_owner"));
    task.setLeaseUntil(toDateTime(rs.getTimestamp("lease_until")));
    task.setHeartbeatAt(toDateTime(rs.getTimestamp("heartbeat_at")));
    task.setRetryCount(rs.getInt("retry_count"));
    task.setMaxRetryCount(rs.getInt("max_retry_count"));
    task.setLastError(rs.getString("last_error"));
    task.setRowsScanned(rs.getLong("rows_scanned"));
    task.setRowsApplied(rs.getLong("rows_applied"));
    task.setStartedAt(toDateTime(rs.getTimestamp("started_at")));
    task.setFinishedAt(toDateTime(rs.getTimestamp("finished_at")));
    task.setCreatedAt(toDateTime(rs.getTimestamp("created_at")));
    task.setUpdatedAt(toDateTime(rs.getTimestamp("updated_at")));
    return task;
  }

  private LocalDateTime toDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }
}
