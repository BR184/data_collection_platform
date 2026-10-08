package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 事实批次提交事务内的执行权屏障。
 *
 * <p>父运行行与任务行都取 {@code for key share}：撤销执行权必须显式取 {@code for update}，因此撤销
 * 会等待在途批次提交，而在途批次的下一次校验会看到撤销后的状态。心跳与非键列更新取
 * {@code for no key update}，与 {@code for key share} 兼容，所以长构建的续租不会被屏障阻塞。
 */
@Service
public class FactTaskExecutionGuard {
  private final JdbcTemplate jdbcTemplate;
  private final FactTaskExecutionContext executionContext;

  public FactTaskExecutionGuard(
      JdbcTemplate jdbcTemplate, FactTaskExecutionContext executionContext) {
    this.jdbcTemplate = jdbcTemplate;
    this.executionContext = executionContext;
  }

  /**
   * 校验当前线程的事实任务仍持有两层执行权。
   *
   * <p>没有任务上下文时直接放行：手工同步构建走全局事实构建锁，不归属 {@code FACT_REFRESH} 运行。
   *
   * @throws FactTaskLeaseLostException 父运行或任务自身已失去执行权
   */
  public void requireCurrentTaskAuthorization() {
    QueuedFactBuildTask task = executionContext.currentTask();
    if (task != null) {
      requireAuthorization(task);
    }
  }

  /**
   * 校验指定任务的两层执行权，并在当前事务内对父运行行与任务行加共享行锁。
   *
   * @param task 已领取的事实任务
   * @throws FactTaskLeaseLostException 任一校验不通过
   */
  public void requireAuthorization(QueuedFactBuildTask task) {
    if (task == null || task.id() == null) {
      throw new IllegalArgumentException("事实任务执行权校验需要已领取的任务");
    }
    if (!parentRunAuthorizes(task.factRunId(), task.factRunLeaseToken())) {
      throw new FactTaskLeaseLostException(task.id(), "父事实运行已失去执行权或正在取消");
    }
    if (!taskLeaseHeld(task)) {
      throw new FactTaskLeaseLostException(task.id(), "事实任务租约已失效");
    }
  }

  private boolean parentRunAuthorizes(Long factRunId, String runLeaseToken) {
    if (factRunId == null || runLeaseToken == null || runLeaseToken.isBlank()) {
      return false;
    }
    List<Long> authorized =
        jdbcTemplate.queryForList(
            """
            select parent_run.id
              from sync_runs parent_run
             where parent_run.id = ?
               and parent_run.run_type = 'FACT_REFRESH'
               and parent_run.status in ('RUNNING', 'RETRYING')
               and parent_run.lease_owner = ?
               and parent_run.cancel_requested = false
               and parent_run.lease_until >= clock_timestamp()
             for key share
            """,
            Long.class,
            factRunId,
            runLeaseToken);
    return authorized.size() == 1;
  }

  private boolean taskLeaseHeld(QueuedFactBuildTask task) {
    if (task.leaseOwner() == null || task.leaseOwner().isBlank()) {
      return false;
    }
    List<Long> held =
        jdbcTemplate.queryForList(
            """
            select task.id
              from fact_build_tasks task
             where task.id = ?
               and task.status = 'RUNNING'
               and task.lock_owner = ?
               and task.lease_until >= clock_timestamp()
             for key share
            """,
            Long.class,
            task.id(),
            task.leaseOwner());
    return held.size() == 1;
  }
}
