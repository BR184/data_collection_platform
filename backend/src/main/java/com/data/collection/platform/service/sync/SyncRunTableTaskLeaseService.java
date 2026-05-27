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
    return retried + timedOut;
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
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where run_id = ?
           and status = 'QUEUED'
        """,
        runId);
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
