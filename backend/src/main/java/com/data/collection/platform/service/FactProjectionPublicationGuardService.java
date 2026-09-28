package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactProjectionTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在快照最终写入事务内验证投影任务租约与目标 generation。 */
@Service
public class FactProjectionPublicationGuardService {
  private final JdbcTemplate jdbcTemplate;
  private final FactProjectionExecutionContext executionContext;
  private final TransactionTemplate writeTransaction;

  public FactProjectionPublicationGuardService(
      JdbcTemplate jdbcTemplate,
      FactProjectionExecutionContext executionContext,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.executionContext = executionContext;
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  /**
   * 普通页面缓存沿用当前写入事务；投影 worker 则必须持有 generation 与任务行锁直至写入结束。
   *
   * @param writeSnapshot 实际快照插入或更新
   */
  public void writeSnapshot(Runnable writeSnapshot) {
    QueuedFactProjectionTask task = executionContext.currentTask();
    if (task == null) {
      writeSnapshot.run();
      return;
    }
    writeTransaction.executeWithoutResult(
        status -> {
          Long generation =
              jdbcTemplate.query(
                  """
                  select generation
                    from fact_projection_generations
                   where source_instance = ? and fact_type = ?
                     and scope_type = ? and scope_key = ?
                   for share
                  """,
                  resultSet -> resultSet.next() ? resultSet.getLong("generation") : null,
                  task.scope().sourceInstance(),
                  task.scope().factType().name(),
                  task.scope().scopeType().name(),
                  task.scope().scopeKey());
          if (generation == null || generation < task.targetGeneration()) {
            throw new IllegalStateException("事实投影任务目标 generation 尚未提交：" + task.id());
          }
          if (generation > task.targetGeneration()) {
            throw new ProjectionTaskSupersededException(task.id());
          }
          Integer currentTask =
              jdbcTemplate.query(
                  """
                  select id
                    from fact_projection_refresh_tasks
                   where id = ? and status = 'RUNNING' and lease_owner = ?
                     and target_generation = ? and lease_until >= clock_timestamp()
                   for update
                  """,
                  resultSet -> resultSet.next() ? resultSet.getInt("id") : null,
                  task.id(),
                  task.leaseToken(),
                  task.targetGeneration());
          if (currentTask == null) {
            throw new ProjectionTaskLeaseLostException(task.id());
          }
          writeSnapshot.run();
        });
  }
}
