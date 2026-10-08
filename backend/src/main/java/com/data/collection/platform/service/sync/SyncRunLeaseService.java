package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncRunLeaseService {
  private final JdbcTemplate jdbcTemplate;
  private final GitlabMirrorProperties properties;

  public SyncRunLeaseService(JdbcTemplate jdbcTemplate, GitlabMirrorProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  /**
   * 仅为仍由指定执行器持有、且租约尚未过期的活动运行续租。
   *
   * <p>已被回收或租约过期的运行不接受续租，过期令牌不能借此复活执行权；取消中的运行允许收尾续期，
   * 但本方法不改变运行状态，不授予新的写入语义。
   *
   * @param runId 运行数据库主键
   * @param leaseOwner 本次领取生成的唯一所有权令牌
   * @param leaseSeconds 新租约时长，最小为 1 秒
   * @return 成功续租时为 1；运行已转移、暂停、终止或租约已失效时为 0
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
           and lease_until is not null
           and lease_until >= current_timestamp
        """,
        Math.max(1, leaseSeconds),
        runId,
        leaseOwner);
  }

  /**
   * 由当前租约持有者提交运行终态并释放租约。
   *
   * <p>该写入是运行级 fencing 边界；过期执行器即使继续返回，也不能覆盖重新领取后的状态。租约已失效时
   * 拒绝写入，避免被回收的运行在回收后又被旧执行器改写。运行已处于 CANCELLING 时只接受取消收尾
   * （{@code CANCELLED}），不允许把取消中的运行改写成成功，取消语义由取消路径或执行器收尾决定。
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
           and lease_until is not null
           and lease_until >= current_timestamp
           and (status <> 'CANCELLING' or ? = 'CANCELLED')
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
        run.getLeaseOwner(),
        run.getStatus().name());
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

  /**
   * 把当前拥有的事实运行转入 PAUSED 或 RETRYING，并释放执行租约。
   *
   * @param run 当前事实运行
   * @param status 只允许 PAUSED 或 RETRYING
   * @param runAfter 下次最早领取时间；为空时立即可再次领取
   * @param message 当前等待或失败原因
   * @return owner fencing 成功时返回 1
   */
  public int deferOwnedRun(
      SyncRun run,
      com.data.collection.platform.entity.sync.SyncRunStatus status,
      java.time.LocalDateTime runAfter,
      String message) {
    if (run == null
        || run.getId() == null
        || run.getLeaseOwner() == null
        || (status != com.data.collection.platform.entity.sync.SyncRunStatus.PAUSED
            && status != com.data.collection.platform.entity.sync.SyncRunStatus.RETRYING)) {
      return 0;
    }
    return jdbcTemplate.update(
        """
        update sync_runs
           set status = ?,
               run_after = coalesce(?, current_timestamp),
               error_message = ?,
               lease_owner = null,
               lease_until = null,
               heartbeat_at = null,
               finished_at = null,
               updated_at = current_timestamp
         where id = ?
           and lease_owner = ?
           and status in ('RUNNING', 'RETRYING')
        """,
        status.name(),
        runAfter,
        message,
        run.getId(),
        run.getLeaseOwner());
  }

  /**
   * 回收异常运行并在同一事务内撤销其子任务执行权。
   *
   * <p>只处理两类按协议不应存在的行：租约已过期的活动运行，以及声称活动（{@code RUNNING}/
   * {@code CANCELLING}）却没有租约、且心跳与更新时间都超过租约窗口的运行。合法释放租约的
   * {@code RETRYING}/{@code PAUSED} 与排队等待不在此列——排队或等待再久也不记为超时。
   *
   * <p>运行状态与子任务的撤销在同一事务提交，因此同域新运行只会在旧执行权完全撤销之后才被放行；
   * 撤销过程中任一步失败则整体回滚，旧行继续保持占用，不会留下"已忽略旧阻塞行、旧执行者稍后收拾"
   * 的中间态。
   *
   * @return 本轮回收的运行数
   */
  @Transactional
  public int recoverTimedOutRuns() {
    int expiredLeaseRuns =
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
    int leaseLessActiveRuns =
        jdbcTemplate.update(
            """
            update sync_runs
               set status = 'TIMEOUT',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   finished_at = coalesce(finished_at, current_timestamp),
                   error_message = coalesce(error_message, 'Sync run held no lease while active'),
                   updated_at = current_timestamp
             where status in ('RUNNING', 'CANCELLING')
               and lease_until is null
               and coalesce(heartbeat_at, updated_at)
                     < current_timestamp - (? * interval '1 second')
            """,
            Math.max(1, properties.getHeartbeatTimeoutSeconds()));
    int timedOutRuns = expiredLeaseRuns + leaseLessActiveRuns;
    if (timedOutRuns > 0) {
      markTimedOutRunTasks();
    }
    return timedOutRuns;
  }

  /** 撤销已判定超时运行的全部在途表任务执行权；运行与任务同事务提交。 */
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
