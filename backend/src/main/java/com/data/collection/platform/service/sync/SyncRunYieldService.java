package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.entity.sync.SyncRunTableTaskStage;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在已提交分页边界判断镜像运行是否应为更高优先级数据刷新让行。 */
@Service
public class SyncRunYieldService {
  private final JdbcTemplate jdbcTemplate;

  public SyncRunYieldService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 判断当前运行是否存在同源的增量同步或用户单表刷新等待者。
   *
   * @param run 当前运行
   * @return 仅后台全量或补偿运行存在前台等待者时返回 true
   */
  public boolean shouldYield(SyncRun run) {
    if (run == null
        || run.getId() == null
        || run.getExclusiveScope() == null
        || !isYieldableBackgroundRun(run.getRunType())) {
      return false;
    }
    return hasQueuedForegroundWaiter(
        run, "waiting.run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH')");
  }

  /**
   * 在表任务页提交后判断当前运行是否应让行。
   *
   * <p>自动增量只在删除对账页完成后为页面手动刷新让行；快速 SCAN、权威范围和其他运行类型
   * 保持既有优先级。全量与全量补偿继续在任意已提交表任务边界让行。
   *
   * @param run 当前镜像运行
   * @param completedTask 刚完成平台事务提交的表任务
   * @return 当前页边界允许且存在更高优先级等待者时返回 true
   */
  public boolean shouldYieldAfterTableTask(SyncRun run, SyncRunTableTask completedTask) {
    if (run != null && run.getRunType() == SyncRunType.INCREMENTAL_SYNC) {
      if (completedTask == null
          || completedTask.getTaskStage() != SyncRunTableTaskStage.RECONCILE) {
        return false;
      }
      return hasQueuedForegroundWaiter(run, "waiting.run_type = 'TABLE_REFRESH'");
    }
    return shouldYield(run);
  }

  /**
   * 在所有当前分页完成后暂停可让行运行；更新时再次确认前台等待者仍存在。
   *
   * @param run 待暂停的镜像运行
   * @return 成功持久化暂停状态时返回 true
   */
  public boolean pauseIfRequested(SyncRun run) {
    if (run == null
        || run.getId() == null
        || run.getExclusiveScope() == null
        || !isYieldableRun(run.getRunType())) {
      return false;
    }
    String waiterPredicate =
        run.getRunType() == SyncRunType.INCREMENTAL_SYNC
            ? "waiting.run_type = 'TABLE_REFRESH'"
            : "waiting.run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH')";
    int updated =
        jdbcTemplate.update(
            """
            update sync_runs current_run
               set status = 'PAUSED',
                   lease_owner = null,
                   lease_until = null,
                   heartbeat_at = null,
                   updated_at = current_timestamp
             where current_run.id = ?
               and current_run.lease_owner = ?
               and current_run.status = 'RUNNING'
               and exists (
                 select 1
                   from sync_runs waiting
                  where waiting.id <> current_run.id
                    and waiting.exclusive_scope = current_run.exclusive_scope
                    and waiting.status = 'QUEUED'
                    and %s
               )
            """.formatted(waiterPredicate),
            run.getId(),
            run.getLeaseOwner());
    if (updated == 1) {
      run.setStatus(com.data.collection.platform.entity.sync.SyncRunStatus.PAUSED);
      run.setLeaseOwner(null);
      run.setLeaseUntil(null);
      run.setHeartbeatAt(null);
      return true;
    }
    return false;
  }

  private boolean isYieldableBackgroundRun(SyncRunType runType) {
    return runType == SyncRunType.FULL_SYNC
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }

  private boolean isYieldableRun(SyncRunType runType) {
    return runType == SyncRunType.INCREMENTAL_SYNC || isYieldableBackgroundRun(runType);
  }

  private boolean hasQueuedForegroundWaiter(SyncRun run, String waiterPredicate) {
    if (run == null
        || run.getId() == null
        || run.getExclusiveScope() == null
        || waiterPredicate == null
        || waiterPredicate.isBlank()) {
      return false;
    }
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from sync_runs waiting
             where waiting.id <> ?
               and waiting.exclusive_scope = ?
               and waiting.status = 'QUEUED'
               and %s
            """.formatted(waiterPredicate),
            Integer.class,
            run.getId(),
            run.getExclusiveScope());
    return count != null && count > 0;
  }
}
