package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在事实事务内推进稳定范围 generation 并创建唯一投影任务。 */
@Service
public class FactProjectionGenerationService {
  private static final int DEFAULT_MAX_RETRY_COUNT = 3;

  private final JdbcTemplate jdbcTemplate;

  public FactProjectionGenerationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 推进全部受影响范围，并把目标 generation 绑定到当前事实批次。
   *
   * <p>调用者必须位于事实 DML 的同一事务；任一范围任务创建失败会使事实替换一起回滚。
   */
  public void advanceAndQueue(
      QueuedFactBuildTask task, Set<FactProjectionScope> affectedScopes) {
    if (task == null || task.id() == null || task.factRunId() == null) {
      throw new IllegalArgumentException("投影发布必须关联事实运行和事实任务");
    }
    if (affectedScopes == null || affectedScopes.isEmpty()) {
      return;
    }
    for (FactProjectionScope scope : new java.util.TreeSet<>(affectedScopes)) {
      if (!scope.sourceInstance().equals(task.sourceInstance())
          || !scope.factType().name().equals(task.factType())) {
        throw new IllegalArgumentException("投影范围与事实任务身份不一致：" + scope);
      }
      long generation = advanceGeneration(scope);
      queueProjectionTask(task, scope, generation);
    }
  }

  private long advanceGeneration(FactProjectionScope scope) {
    Long generation =
        jdbcTemplate.queryForObject(
            """
            insert into fact_projection_generations(
                source_instance, fact_type, scope_type, scope_key, generation, updated_at)
            values (?, ?, ?, ?, 1, current_timestamp)
            on conflict (source_instance, fact_type, scope_type, scope_key) do update
               set generation = fact_projection_generations.generation + 1,
                   updated_at = current_timestamp
            returning generation
            """,
            Long.class,
            scope.sourceInstance(),
            scope.factType().name(),
            scope.scopeType().name(),
            scope.scopeKey());
    if (generation == null || generation <= 0L) {
      throw new IllegalStateException("无法推进事实投影 generation：" + scope);
    }
    return generation;
  }

  private void queueProjectionTask(
      QueuedFactBuildTask task, FactProjectionScope scope, long generation) {
    jdbcTemplate.update(
        """
        insert into fact_projection_refresh_tasks(
            fact_run_id, fact_build_task_id, source_instance, fact_type,
            scope_type, scope_key, target_generation, status,
            retry_count, max_retry_count, run_after, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?, current_timestamp,
                current_timestamp, current_timestamp)
        on conflict (fact_build_task_id, scope_type, scope_key) do update
           set target_generation = greatest(
                   fact_projection_refresh_tasks.target_generation,
                   excluded.target_generation),
               status = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then 'QUEUED'
                   else fact_projection_refresh_tasks.status
                 end,
               lease_owner = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then null
                   else fact_projection_refresh_tasks.lease_owner
                 end,
               lease_until = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then null
                   else fact_projection_refresh_tasks.lease_until
                 end,
               heartbeat_at = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then null
                   else fact_projection_refresh_tasks.heartbeat_at
                 end,
               run_after = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then current_timestamp
                   else fact_projection_refresh_tasks.run_after
                 end,
               finished_at = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then null
                   else fact_projection_refresh_tasks.finished_at
                 end,
               error_message = case
                   when fact_projection_refresh_tasks.target_generation < excluded.target_generation
                     then null
                   else fact_projection_refresh_tasks.error_message
                 end,
               updated_at = current_timestamp
        """,
        task.factRunId(),
        task.id(),
        scope.sourceInstance(),
        scope.factType().name(),
        scope.scopeType().name(),
        scope.scopeKey(),
        generation,
        DEFAULT_MAX_RETRY_COUNT);
  }
}
