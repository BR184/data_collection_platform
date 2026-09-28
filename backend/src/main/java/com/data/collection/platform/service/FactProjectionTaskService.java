package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 事实投影任务的领取、fencing、重试和运行级汇总入口。 */
@Service
public class FactProjectionTaskService {
  private final JdbcTemplate jdbcTemplate;
  private final com.data.collection.platform.service.sync.SyncRunPublicationFenceService
      publicationFenceService;

  public FactProjectionTaskService(
      JdbcTemplate jdbcTemplate,
      com.data.collection.platform.service.sync.SyncRunPublicationFenceService
          publicationFenceService) {
    this.jdbcTemplate = jdbcTemplate;
    this.publicationFenceService = publicationFenceService;
  }

  /** 仅在父运行仍由当前执行者持有时，领取一个到期投影任务。 */
  @Transactional
  public QueuedFactProjectionTask claimNext(
      long factRunId, String parentLeaseToken, int leaseSeconds) {
    if (factRunId <= 0L || parentLeaseToken == null || parentLeaseToken.isBlank()) {
      return null;
    }
    recoverExpiredTasks(factRunId);
    String leaseToken = "projection-" + UUID.randomUUID();
    List<QueuedFactProjectionTask> tasks =
        jdbcTemplate.query(
            """
            update fact_projection_refresh_tasks
               set status = 'RUNNING',
                   lease_owner = ?,
                   heartbeat_at = current_timestamp,
                   lease_until = current_timestamp + (? * interval '1 second'),
                   started_at = coalesce(started_at, current_timestamp),
                   updated_at = current_timestamp
             where fact_projection_refresh_tasks.id = (
               select task.id
                 from fact_projection_refresh_tasks task
                 join sync_runs parent_run on parent_run.id = task.fact_run_id
                where task.fact_run_id = ?
                  and task.status in ('QUEUED', 'RETRY_WAITING')
                  and task.run_after <= current_timestamp
                  and parent_run.status = 'RUNNING'
                  and parent_run.lease_owner = ?
                  and parent_run.lease_until >= clock_timestamp()
                order by task.id
                for update of task skip locked
                limit 1
             )
             returning *
            """,
            (resultSet, rowNum) ->
                new QueuedFactProjectionTask(
                    resultSet.getLong("id"),
                    resultSet.getLong("fact_run_id"),
                    resultSet.getLong("fact_build_task_id"),
                    new FactProjectionScope(
                        resultSet.getString("source_instance"),
                        FactType.valueOf(resultSet.getString("fact_type")),
                        ProjectionScopeType.valueOf(resultSet.getString("scope_type")),
                        resultSet.getString("scope_key")),
                    resultSet.getLong("target_generation"),
                    resultSet.getInt("retry_count"),
                    resultSet.getInt("max_retry_count"),
                    resultSet.getString("lease_owner"),
                    toLocalDateTime(resultSet.getTimestamp("lease_until"))),
            leaseToken,
            Math.max(1, leaseSeconds),
            factRunId,
            parentLeaseToken);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /**
   * 原子回收失租或没有租约期限的 RUNNING 任务，并推进有限重试预算。
   * 父运行已经终止或正在取消时，任务直接失败，避免留下无人再领取的活动记录。
   *
   * @return 被收敛的任务数
   */
  @Transactional
  public int recoverExpiredTasks() {
    return recoverExpiredTasks(null);
  }

  private int recoverExpiredTasks(Long factRunId) {
    List<RecoveredTask> recovered =
        jdbcTemplate.query(
            """
            with expired as (
              select task.id, parent_run.status as parent_status
                from fact_projection_refresh_tasks task
                join sync_runs parent_run on parent_run.id = task.fact_run_id
               where task.status = 'RUNNING'
                 and (task.lease_until is null or task.lease_until < clock_timestamp())
                  and (?::bigint is null or task.fact_run_id = ?)
               order by task.id
               for update of task skip locked
            )
            update fact_projection_refresh_tasks task
               set retry_count = task.retry_count + 1,
                   recovery_count = task.recovery_count + 1,
                   status = case
                     when expired.parent_status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'CANCELLING')
                       then 'FAILED'
                     when task.retry_count + 1 < task.max_retry_count
                       then 'RETRY_WAITING'
                     else 'FAILED'
                   end,
                   run_after = case
                     when expired.parent_status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'CANCELLING')
                       then task.run_after
                     when task.retry_count + 1 < task.max_retry_count
                       then clock_timestamp()
                            + (power(2, least(task.retry_count, 6)) * interval '1 second')
                     else task.run_after
                   end,
                   error_message = case
                     when expired.parent_status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'CANCELLING')
                       then '父事实运行已终止，失租投影任务不再重试'
                     else '事实投影任务租约过期，已由调度回收'
                   end,
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = case
                     when expired.parent_status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT', 'CANCELLING')
                       then clock_timestamp()
                     when task.retry_count + 1 < task.max_retry_count
                       then null
                     else clock_timestamp()
                   end,
                   updated_at = clock_timestamp()
              from expired
             where task.id = expired.id
            returning task.id, task.status, task.run_after
            """,
            (resultSet, rowNum) ->
                new RecoveredTask(
                    resultSet.getLong("id"),
                    resultSet.getString("status"),
                    toLocalDateTime(resultSet.getTimestamp("run_after"))),
            factRunId,
            factRunId);
    for (RecoveredTask task : recovered) {
      publicationFenceService.advanceAfterProjectionTask(task.id());
    }
    return recovered.size();
  }

  /** 返回当前稳定范围 generation；范围尚未发布时返回 0。 */
  public long currentGeneration(FactProjectionScope scope) {
    Long generation =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(generation), 0)
              from fact_projection_generations
             where source_instance = ? and fact_type = ? and scope_type = ? and scope_key = ?
            """,
            Long.class,
            scope.sourceInstance(),
            scope.factType().name(),
            scope.scopeType().name(),
            scope.scopeKey());
    return generation == null ? 0L : generation;
  }

  /** 仅由未过期的当前执行身份提交投影任务终态。 */
  @Transactional
  public void finishOwned(QueuedFactProjectionTask task, String message) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_projection_refresh_tasks
               set status = 'SUCCESS',
                   error_message = null,
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where id = ? and status = 'RUNNING' and lease_owner = ?
               and target_generation = ? and lease_until >= clock_timestamp()
            """,
            task.id(),
            task.leaseToken(),
            task.targetGeneration());
    if (updated != 1) {
      throw new ProjectionTaskLeaseLostException(task.id());
    }
    publicationFenceService.advanceAfterProjectionTask(task.id());
  }

  /** 续租当前任务；过期或已重新领取的执行身份不能复活租约。 */
  public boolean renewOwned(QueuedFactProjectionTask task, int leaseSeconds) {
    if (task == null || task.leaseToken() == null || task.leaseToken().isBlank()) {
      return false;
    }
    return jdbcTemplate.update(
            """
            update fact_projection_refresh_tasks
               set heartbeat_at = clock_timestamp(),
                   lease_until = clock_timestamp() + (? * interval '1 second'),
                   updated_at = clock_timestamp()
             where id = ? and status = 'RUNNING' and lease_owner = ?
               and target_generation = ? and lease_until >= clock_timestamp()
            """,
            Math.max(1, leaseSeconds),
            task.id(),
            task.leaseToken(),
            task.targetGeneration())
        == 1;
  }

  /** 失败时复用原任务退避重试，达到上限后明确失败。 */
  @Transactional
  public FailureDisposition failOwned(
      QueuedFactProjectionTask task, String errorMessage) {
    List<FailureDisposition> results =
        jdbcTemplate.query(
            """
            update fact_projection_refresh_tasks
               set retry_count = retry_count + 1,
                   status = case
                       when retry_count + 1 < max_retry_count then 'RETRY_WAITING'
                       else 'FAILED'
                     end,
                   run_after = case
                       when retry_count + 1 < max_retry_count
                         then current_timestamp
                              + (power(2, least(retry_count, 6)) * interval '1 second')
                       else run_after
                     end,
                   error_message = ?,
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = case
                       when retry_count + 1 < max_retry_count then null
                       else current_timestamp
                     end,
                   updated_at = current_timestamp
             where id = ? and status = 'RUNNING' and lease_owner = ?
               and target_generation = ? and lease_until >= clock_timestamp()
             returning status, run_after
            """,
            (resultSet, rowNum) ->
                new FailureDisposition(
                    resultSet.getString("status"),
                    toLocalDateTime(resultSet.getTimestamp("run_after"))),
            errorMessage,
            task.id(),
            task.leaseToken(),
            task.targetGeneration());
    if (results.size() != 1) {
      throw new ProjectionTaskLeaseLostException(task.id());
    }
    publicationFenceService.advanceAfterProjectionTask(task.id());
    return results.getFirst();
  }

  /** 汇总指定 FACT_REFRESH 运行的投影任务。 */
  public RunTaskSummary summarize(long factRunId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) as total_tasks,
               count(*) filter (where status = 'SUCCESS') as success_tasks,
               count(*) filter (where status = 'FAILED') as failed_tasks,
               count(*) filter (where status = 'QUEUED') as queued_tasks,
               count(*) filter (where status = 'RUNNING') as running_tasks,
               count(*) filter (where status = 'RETRY_WAITING') as retry_waiting_tasks,
               min(run_after) filter (where status = 'RETRY_WAITING') as next_run_after
          from fact_projection_refresh_tasks
         where fact_run_id = ?
        """,
        (resultSet, rowNum) ->
            new RunTaskSummary(
                resultSet.getInt("total_tasks"),
                resultSet.getInt("success_tasks"),
                resultSet.getInt("failed_tasks"),
                resultSet.getInt("queued_tasks"),
                resultSet.getInt("running_tasks"),
                resultSet.getInt("retry_waiting_tasks"),
                toLocalDateTime(resultSet.getTimestamp("next_run_after"))),
        factRunId);
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  public record FailureDisposition(String status, LocalDateTime runAfter) {}

  private record RecoveredTask(long id, String status, LocalDateTime runAfter) {}

  public record RunTaskSummary(
      int totalTasks,
      int successTasks,
      int failedTasks,
      int queuedTasks,
      int runningTasks,
      int retryWaitingTasks,
      LocalDateTime nextRunAfter) {
    public boolean hasActiveTasks() {
      return queuedTasks > 0 || runningTasks > 0 || retryWaitingTasks > 0;
    }
  }
}
