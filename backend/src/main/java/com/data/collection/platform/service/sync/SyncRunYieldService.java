package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在已提交分页边界判断后台镜像运行是否应为前台数据刷新让行。 */
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
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from sync_runs
             where id <> ?
               and exclusive_scope = ?
               and status = 'QUEUED'
               and run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH')
            """,
            Integer.class,
            run.getId(),
            run.getExclusiveScope());
    return count != null && count > 0;
  }

  /**
   * 在所有当前分页完成后暂停后台运行；更新时再次确认前台等待者仍存在。
   *
   * @param run 待暂停的后台运行
   * @return 成功持久化暂停状态时返回 true
   */
  public boolean pauseIfRequested(SyncRun run) {
    if (run == null
        || run.getId() == null
        || run.getExclusiveScope() == null
        || !isYieldableBackgroundRun(run.getRunType())) {
      return false;
    }
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
                    and waiting.run_type in ('INCREMENTAL_SYNC', 'TABLE_REFRESH')
               )
            """,
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
        || runType == SyncRunType.COMPENSATION_SCAN
        || runType == SyncRunType.FULL_COMPENSATION_SCAN;
  }
}
