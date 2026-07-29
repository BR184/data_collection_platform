package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SyncRunLeaseService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunLeaseService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 仅为仍由指定执行器持有的活动运行续租。
   *
   * @param runId 运行数据库主键
   * @param leaseOwner 本次领取生成的唯一所有权令牌
   * @param leaseSeconds 新租约时长，最小为 1 秒
   * @return 成功续租时为 1；运行已转移、暂停或终止时为 0
   */
  public int heartbeat(Long runId, String leaseOwner, int leaseSeconds) {
    if (runId == null || leaseOwner == null || leaseOwner.isBlank()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update sync_runs
           set heartbeat_at = current_timestamp,
               lease_until = current_timestamp + (? * interval '1 second'),
               updated_at = current_timestamp
          where id = ?
           and lease_owner = ?
           and status in ('RUNNING', 'RETRYING', 'CANCELLING')
        """,
        Math.max(1, leaseSeconds),
        runId,
        leaseOwner);
  }

  /**
   * 由当前租约持有者提交运行终态并释放租约。
   *
   * <p>该写入是运行级 fencing 边界；过期执行器即使继续返回，也不能覆盖重新领取后的状态。
   *
   * @param run 已填充最终统计与状态的运行快照
   * @return 当前 owner 成功提交时为 1，否则为 0
   */
  public int finishOwnedRun(SyncRun run) {
    if (run == null
        || run.getId() == null
        || run.getLeaseOwner() == null
        || run.getLeaseOwner().isBlank()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update sync_runs
           set status = ?,
               planned_table_count = ?,
               completed_table_count = ?,
               scanned_rows = ?,
               applied_rows = ?,
               finished_at = ?,
               error_message = ?,
               lease_owner = null,
               lease_until = null,
               updated_at = ?
         where id = ?
           and lease_owner = ?
           and status in ('RUNNING', 'RETRYING', 'CANCELLING')
        """,
        run.getStatus().name(),
        run.getPlannedTableCount(),
        run.getCompletedTableCount(),
        run.getScannedRows(),
        run.getAppliedRows(),
        run.getFinishedAt(),
        run.getErrorMessage(),
        run.getUpdatedAt(),
        run.getId(),
        run.getLeaseOwner());
  }

  /**
   * 执行器无法接收入队任务时释放尚未开始的运行，供其他执行器重新领取。
   *
   * @param run 已领取但未开始执行的运行
   * @return 当前 owner 成功释放时为 1，否则为 0
   */
  public int releaseOwnedRun(SyncRun run) {
    if (run == null
        || run.getId() == null
        || run.getLeaseOwner() == null
        || run.getLeaseOwner().isBlank()) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update sync_runs
           set status = 'QUEUED',
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               updated_at = current_timestamp
         where id = ?
           and lease_owner = ?
           and status = 'RUNNING'
        """,
        run.getId(),
        run.getLeaseOwner());
  }

  public int recoverTimedOutRuns() {
    int timedOutRuns =
        jdbcTemplate.update(
            """
            update sync_runs
               set status = 'TIMEOUT',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = coalesce(finished_at, current_timestamp),
                   error_message = coalesce(error_message, 'Sync run lease timed out'),
                   updated_at = current_timestamp
             where status in ('RUNNING', 'RETRYING', 'CANCELLING')
               and lease_until is not null
               and lease_until < current_timestamp
            """);
    if (timedOutRuns > 0) {
      markTimedOutRunTasks();
    }
    return timedOutRuns;
  }

  private void markTimedOutRunTasks() {
    jdbcTemplate.update(
        """
        update sync_run_table_tasks task
           set status = 'TIMEOUT',
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               last_error = coalesce(last_error, 'Parent sync run lease timed out'),
               finished_at = coalesce(task.finished_at, current_timestamp),
               updated_at = current_timestamp
          from sync_runs run
         where task.run_id = run.id
           and run.status = 'TIMEOUT'
           and task.status in ('QUEUED', 'RUNNING', 'RETRYING')
        """);
  }
}
