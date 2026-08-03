package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
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

  /** 领取指定事实运行下一项到期的投影任务。 */
  public QueuedFactProjectionTask claimNext(
      long factRunId, String owner, int leaseSeconds) {
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
             where id = (
               select id
                 from fact_projection_refresh_tasks
                where fact_run_id = ?
                  and status in ('QUEUED', 'RETRY_WAITING')
                  and run_after <= current_timestamp
                order by id
                for update skip locked
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
            owner,
            Math.max(1, leaseSeconds),
            factRunId);
    return tasks.isEmpty() ? null : tasks.getFirst();
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

  /** 按 owner fencing 完成投影任务。 */
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
            """,
            task.id(),
            task.leaseOwner());
    if (updated != 1) {
      throw new IllegalStateException("事实投影任务租约已失效：" + task.id());
    }
    publicationFenceService.advanceAfterProjectionTask(task.id());
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
             returning status, run_after
            """,
            (resultSet, rowNum) ->
                new FailureDisposition(
                    resultSet.getString("status"),
                    toLocalDateTime(resultSet.getTimestamp("run_after"))),
            errorMessage,
            task.id(),
            task.leaseOwner());
    if (results.size() != 1) {
      throw new IllegalStateException("事实投影任务租约已失效：" + task.id());
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
