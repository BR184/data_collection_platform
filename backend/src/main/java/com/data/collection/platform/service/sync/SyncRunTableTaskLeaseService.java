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
               set status = 'RETRYING',
                   retry_count = retry_count + 1,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   last_error = '表任务租约超时，已进入退避重试',
                   run_after = current_timestamp
                       + (least(300, 5 * power(2, retry_count)) * interval '1 second'),
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
                and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
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

  /** 领取指定运行中已到期的排队或重试表任务，并建立 owner 租约。 */
  public SyncRunTableTask claimNextRunnableTask(Long runId, String owner, int leaseSeconds) {
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
                and candidate.status in ('QUEUED', 'RETRYING')
                and candidate.run_after <= current_timestamp
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

  /**
   * 把当前 owner 持有的瞬时失败任务原子转为同任务退避重试。
   *
   * <p>该转换不修改扫描游标、水位或完成时间；只有有效租约且尚有重试额度时成功。
   */
  public boolean deferOwnedTask(
      Long taskId,
      String owner,
      Long rowsScanned,
      Long rowsApplied,
      LocalDateTime runAfter,
      String errorMessage) {
    if (taskId == null
        || owner == null
        || owner.isBlank()
        || runAfter == null) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'RETRYING',
                   retry_count = retry_count + 1,
                   rows_scanned = coalesce(?, rows_scanned),
                   rows_applied = coalesce(?, rows_applied),
                   run_after = ?,
                   last_error = ?,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = null,
                   updated_at = current_timestamp
             where id = ?
               and lease_owner = ?
               and status = 'RUNNING'
               and lease_until >= current_timestamp
               and retry_count < max_retry_count
            """,
            rowsScanned,
            rowsApplied,
            runAfter,
            errorMessage,
            taskId,
            owner)
        == 1;
  }

  /**
   * 仅由当前租约所有者续期正在执行的表任务。
   *
   * @param taskId 表任务 ID
   * @param owner 当前 worker 所有者标识
   * @param leaseSeconds 新租约时长
   * @return 仍拥有租约并成功续期时返回 true
   */
  public boolean renewLease(Long taskId, String owner, int leaseSeconds) {
    if (taskId == null || owner == null || owner.isBlank()) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set heartbeat_at = current_timestamp,
                   lease_until = current_timestamp + (? * interval '1 second'),
                   updated_at = current_timestamp
             where id = ?
               and lease_owner = ?
               and status = 'RUNNING'
            """,
            Math.max(1, leaseSeconds),
            taskId,
            owner)
        == 1;
  }

  /**
   * 仅允许当前租约所有者完成表任务，防止超时后的旧 worker 覆盖新状态。
   *
   * @return 状态转换成功时返回 true
   */
  public boolean finishOwnedTask(
      Long taskId,
      String owner,
      Long rowsScanned,
      Long rowsApplied,
      String status,
      String errorMessage) {
    if (taskId == null || owner == null || owner.isBlank()) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = ?,
                   rows_scanned = coalesce(?, rows_scanned),
                   rows_applied = coalesce(?, rows_applied),
                   last_error = ?,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where lease_owner = ?
               and id = ?
               and status = 'RUNNING'
               and lease_until >= current_timestamp
            """,
            status,
            rowsScanned,
            rowsApplied,
            errorMessage,
            owner,
            taskId)
        == 1;
  }

  /**
   * 在同一对账任务行上提交一页游标并重新排队。
   *
   * <p>扫描量和删除量按页累加；owner 与有效租约共同构成 fencing，旧 worker 不能推进游标。
   *
   * @param taskId 对账任务 ID
   * @param owner 当前租约所有者
   * @param nextCursor 下一页主键游标，必须非空
   * @param rowsScanned 本页验证的镜像主键数
   * @param rowsApplied 本页写入 tombstone 的行数
   * @return 当前 owner 成功提交时返回 {@code true}
   */
  public boolean requeueOwnedReconciliationTask(
      Long taskId,
      String owner,
      String nextCursor,
      long rowsScanned,
      long rowsApplied) {
    if (taskId == null
        || owner == null
        || owner.isBlank()
        || nextCursor == null
        || nextCursor.isBlank()
        || rowsScanned < 0L
        || rowsApplied < 0L) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = 'QUEUED',
                   cursor_pk = ?,
                   page_number = page_number + 1,
                   rows_scanned = rows_scanned + ?,
                   rows_applied = rows_applied + ?,
                   run_after = current_timestamp,
                   last_error = null,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = null,
                   updated_at = current_timestamp
             where id = ?
               and task_stage = 'RECONCILE'
               and status = 'RUNNING'
               and lease_owner = ?
               and lease_until >= current_timestamp
            """,
            nextCursor,
            rowsScanned,
            rowsApplied,
            taskId,
            owner)
        == 1;
  }

  public boolean finishOwnedTask(
      Long taskId,
      String owner,
      Long rowsScanned,
      Long rowsApplied,
      String status,
      String errorMessage,
      LocalDateTime cursorUpdatedAt,
      String cursorPk) {
    if (taskId == null || owner == null || owner.isBlank()) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set status = ?,
                   rows_scanned = coalesce(?, rows_scanned),
                   rows_applied = coalesce(?, rows_applied),
                   cursor_updated_at = coalesce(?, cursor_updated_at),
                   cursor_pk = coalesce(?, cursor_pk),
                   last_error = ?,
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where id = ?
               and lease_owner = ?
               and status = 'RUNNING'
               and lease_until >= current_timestamp
            """,
            status,
            rowsScanned,
            rowsApplied,
            cursorUpdatedAt,
            cursorPk,
            errorMessage,
            taskId,
            owner)
        == 1;
  }

  /** 在当前事务中锁定并确认任务仍归指定 worker 所有。 */
  public void lockOwnedTask(Long taskId, String owner) {
    try {
      jdbcTemplate.queryForObject(
          """
          select id
            from sync_run_table_tasks
           where id = ?
             and lease_owner = ?
             and status = 'RUNNING'
             and lease_until >= current_timestamp
           for update
          """,
          Long.class,
          taskId,
          owner);
    } catch (EmptyResultDataAccessException error) {
      throw new SyncTaskLeaseLostException(taskId);
    }
  }

  /** 为当前所有者持久化本轮固定扫描上界。 */
  public boolean initializeOwnedScanUpperBound(
      Long taskId, String owner, LocalDateTime scanUpperBoundAt) {
    if (taskId == null || owner == null || owner.isBlank() || scanUpperBoundAt == null) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set scan_upper_bound_at = coalesce(scan_upper_bound_at, ?),
                   updated_at = current_timestamp
             where id = ?
               and lease_owner = ?
               and status = 'RUNNING'
               and lease_until >= current_timestamp
            """,
            scanUpperBoundAt,
            taskId,
            owner)
        == 1;
  }

  /** 为当前所有者持久化本轮固定单调主键上界。 */
  public boolean initializeOwnedScanUpperBoundPk(
      Long taskId, String owner, String scanUpperBoundPk) {
    if (taskId == null
        || owner == null
        || owner.isBlank()
        || scanUpperBoundPk == null
        || scanUpperBoundPk.isBlank()) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update sync_run_table_tasks
               set scan_upper_bound_pk = coalesce(scan_upper_bound_pk, ?),
                   updated_at = current_timestamp
             where id = ?
               and lease_owner = ?
               and status = 'RUNNING'
               and lease_until >= current_timestamp
            """,
            scanUpperBoundPk,
            taskId,
            owner)
        == 1;
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
    task.setTaskStage(com.data.collection.platform.entity.sync.SyncRunTableTaskStage.valueOf(rs.getString("task_stage")));
    task.setParentTaskId(rs.getObject("parent_task_id") == null ? null : rs.getLong("parent_task_id"));
    task.setWatermarkAt(toDateTime(rs.getTimestamp("watermark_at")));
    task.setCursorUpdatedAt(toDateTime(rs.getTimestamp("cursor_updated_at")));
    task.setCursorPk(rs.getString("cursor_pk"));
    task.setScanUpperBoundAt(toDateTime(rs.getTimestamp("scan_upper_bound_at")));
    task.setScanUpperBoundPk(rs.getString("scan_upper_bound_pk"));
    task.setPageNumber(rs.getObject("page_number") == null ? null : rs.getInt("page_number"));
    task.setLookupScopeJson(rs.getString("lookup_scope_json"));
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
