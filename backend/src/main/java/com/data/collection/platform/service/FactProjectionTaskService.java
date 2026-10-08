package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import java.sql.ResultSet;
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
  /** 单轮遗留任务巡检上限；只做有界 SQL 状态移交，不执行统计或记录预热。 */
  private static final int ORPHANED_TASK_PARK_LIMIT = 200;

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
                  and task.manual_disposition = 'NONE'
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
                    parentLeaseToken,
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
                 and task.manual_disposition = 'NONE'
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

  /** 仅由未过期的当前执行身份提交投影任务终态；父运行授权失效时不提交。 */
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
               and exists (
                 select 1
                   from sync_runs parent_run
                  where parent_run.id = fact_projection_refresh_tasks.fact_run_id
                    and parent_run.run_type = 'FACT_REFRESH'
                    and parent_run.status in ('RUNNING', 'RETRYING')
                    and parent_run.lease_owner = ?
                    and parent_run.cancel_requested = false
                    and parent_run.lease_until >= clock_timestamp())
            """,
            task.id(),
            task.leaseToken(),
            task.targetGeneration(),
            task.factRunLeaseToken());
    if (updated != 1) {
      throw new ProjectionTaskLeaseLostException(task.id());
    }
    publicationFenceService.advanceAfterProjectionTask(task.id());
  }

  /** 续租当前任务；过期、已重新领取或父运行授权失效的执行身份不能复活租约。 */
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
               and exists (
                 select 1
                   from sync_runs parent_run
                  where parent_run.id = fact_projection_refresh_tasks.fact_run_id
                    and parent_run.run_type = 'FACT_REFRESH'
                    and parent_run.status in ('RUNNING', 'RETRYING')
                    and parent_run.lease_owner = ?
                    and parent_run.cancel_requested = false
                    and parent_run.lease_until >= clock_timestamp())
            """,
            Math.max(1, leaseSeconds),
            task.id(),
            task.leaseToken(),
            task.targetGeneration(),
            task.factRunLeaseToken())
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
               and exists (
                 select 1
                   from sync_runs parent_run
                  where parent_run.id = fact_projection_refresh_tasks.fact_run_id
                    and parent_run.run_type = 'FACT_REFRESH'
                    and parent_run.status in ('RUNNING', 'RETRYING')
                    and parent_run.lease_owner = ?
                    and parent_run.cancel_requested = false
                    and parent_run.lease_until >= clock_timestamp())
             returning status, run_after
            """,
            (resultSet, rowNum) ->
                new FailureDisposition(
                    resultSet.getString("status"),
                    toLocalDateTime(resultSet.getTimestamp("run_after"))),
            errorMessage,
            task.id(),
            task.leaseToken(),
            task.targetGeneration(),
            task.factRunLeaseToken());
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
               count(*) filter (where status = 'FAILED' and manual_disposition = 'NONE') as failed_tasks,
               count(*) filter (where status = 'QUEUED' and manual_disposition = 'NONE') as queued_tasks,
               count(*) filter (where status = 'RUNNING' and manual_disposition = 'NONE') as running_tasks,
               count(*) filter (where status = 'RETRY_WAITING' and manual_disposition = 'NONE') as retry_waiting_tasks,
               count(*) filter (where manual_disposition = 'REQUIRES_DECISION') as manual_attention_tasks,
               count(*) filter (where manual_disposition = 'CANCELLED') as cancelled_tasks,
               min(run_after) filter (where manual_disposition = 'NONE'
                                        and status in ('QUEUED', 'RETRY_WAITING')
                                        and run_after > current_timestamp) as next_run_after
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
                resultSet.getInt("manual_attention_tasks"),
                resultSet.getInt("cancelled_tasks"),
                toLocalDateTime(resultSet.getTimestamp("next_run_after"))),
        factRunId);
  }

  /**
   * 把父运行已终态、已无自动认领路径的遗留投影任务转为人工待处理。
   *
   * <p>投影状态机不接受 PAUSED，因此停放表达为 {@code FAILED} + {@code manual_disposition}；
   * 原始错误原文优先保留，仅在为空时写入处置原因。不改版本头，也不把投影标记为成功。
   *
   * @return 本轮转为人工待处理的任务数
   */
  @Transactional
  public int parkOrphanedTasksForManualDecision() {
    List<Long> parkedIds =
        jdbcTemplate.queryForList(
            """
            with orphaned as (
              select task.id
                from fact_projection_refresh_tasks task
                left join sync_runs parent_run on parent_run.id = task.fact_run_id
               where task.manual_disposition = 'NONE'
                 and task.status in ('QUEUED', 'RETRY_WAITING')
                 and (parent_run.id is null
                      or parent_run.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT'))
               order by task.id
               for update of task skip locked
               limit ?
            )
            update fact_projection_refresh_tasks task
               set status = 'FAILED',
                   manual_disposition = 'REQUIRES_DECISION',
                   error_message = coalesce(
                       task.error_message,
                       '父事实运行已结束，遗留投影任务不再自动执行，等待维护人员决定继续或取消'),
                   finished_at = coalesce(task.finished_at, clock_timestamp()),
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   updated_at = clock_timestamp()
              from orphaned
             where task.id = orphaned.id
            returning task.id
            """,
            Long.class,
            ORPHANED_TASK_PARK_LIMIT);
    for (Long taskId : parkedIds) {
      publicationFenceService.advanceAfterProjectionTask(taskId);
    }
    return parkedIds.size();
  }

  /**
   * 锁定并读取待人工处置的投影任务。
   *
   * @param taskId 任务主键
   * @return 任务快照；不存在时返回 {@code null}
   */
  public ProjectionTaskSnapshot lockTaskForResolution(long taskId) {
    List<ProjectionTaskSnapshot> tasks =
        jdbcTemplate.query(
            """
            select id, fact_run_id, fact_build_task_id, source_instance, fact_type, scope_type,
                   scope_key, target_generation, status, manual_disposition, error_message,
                   retry_count, max_retry_count, started_at, finished_at, heartbeat_at, lease_until,
                   lease_owner
              from fact_projection_refresh_tasks
             where id = ?
               for update
            """,
            FactProjectionTaskService::mapProjectionTaskSnapshot,
            taskId);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /** 读取指定任务当前的行快照，用于重复命令返回既有归属。 */
  public ProjectionTaskSnapshot findTask(long taskId) {
    List<ProjectionTaskSnapshot> tasks =
        jdbcTemplate.query(
            """
            select id, fact_run_id, fact_build_task_id, source_instance, fact_type, scope_type,
                   scope_key, target_generation, status, manual_disposition, error_message,
                   retry_count, max_retry_count, started_at, finished_at, heartbeat_at, lease_until,
                   lease_owner
              from fact_projection_refresh_tasks
             where id = ?
            """,
            FactProjectionTaskService::mapProjectionTaskSnapshot,
            taskId);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /** 把投影任务记为人工取消：保留原错误与范围，不标成功、不推进发布水位。 */
  public int cancelTaskByHumanDecision(long taskId, String message) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_projection_refresh_tasks
               set status = 'FAILED',
                   manual_disposition = 'CANCELLED',
                   error_message = coalesce(error_message, ?),
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = coalesce(finished_at, clock_timestamp()),
                   updated_at = clock_timestamp()
             where id = ? and lease_owner is null
            """,
            message,
            taskId);
    if (updated == 1) {
      publicationFenceService.advanceAfterProjectionTask(taskId);
    }
    return updated;
  }

  /**
   * 把投影任务原行移交给新运行继续：保留 {@code fact_build_task_id}、scope 与目标 generation。
   *
   * <p>重新开始一轮自动预算（重试计数与回收计数归零），执行权与人工处置标记一起清除；本轮完成后
   * 该行重新具备被活动父运行领取的资格。唯一键与既有 ON CONFLICT 身份保持不变。
   */
  public int handOverToRun(long taskId, long newRunId, int maxRetryCount) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_projection_refresh_tasks
               set fact_run_id = ?,
                   status = 'QUEUED',
                   manual_disposition = 'NONE',
                   error_message = null,
                   retry_count = 0,
                   recovery_count = 0,
                   max_retry_count = ?,
                   run_after = clock_timestamp(),
                   lease_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   started_at = null,
                   finished_at = null,
                   updated_at = clock_timestamp()
             where id = ? and lease_owner is null
            """,
            newRunId,
            Math.max(1, maxRetryCount),
            taskId);
    if (updated == 1) {
      publicationFenceService.advanceAfterProjectionTask(taskId);
    }
    return updated;
  }

  private static ProjectionTaskSnapshot mapProjectionTaskSnapshot(ResultSet resultSet, int rowNum)
      throws java.sql.SQLException {
    return new ProjectionTaskSnapshot(
        resultSet.getLong("id"),
        resultSet.getLong("fact_run_id"),
        resultSet.getLong("fact_build_task_id"),
        resultSet.getString("source_instance"),
        resultSet.getString("fact_type"),
        resultSet.getString("scope_type"),
        resultSet.getString("scope_key"),
        resultSet.getLong("target_generation"),
        resultSet.getString("status"),
        resultSet.getString("manual_disposition"),
        resultSet.getString("error_message"),
        resultSet.getInt("retry_count"),
        resultSet.getInt("max_retry_count"),
        toLocalDateTime(resultSet.getTimestamp("started_at")),
        toLocalDateTime(resultSet.getTimestamp("finished_at")),
        toLocalDateTime(resultSet.getTimestamp("heartbeat_at")),
        toLocalDateTime(resultSet.getTimestamp("lease_until")),
        resultSet.getString("lease_owner"));
  }

  private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  public record FailureDisposition(String status, LocalDateTime runAfter) {}

  /**
   * 投影任务的完整行快照，用于人工处置命令的校验、诊断与移交。
   *
   * @param leaseOwner 当前执行权持有者；为空表示执行权已撤销
   */
  public record ProjectionTaskSnapshot(
      long id,
      long factRunId,
      long factBuildTaskId,
      String sourceInstance,
      String factType,
      String scopeType,
      String scopeKey,
      long targetGeneration,
      String status,
      String manualDisposition,
      String errorMessage,
      int retryCount,
      int maxRetryCount,
      LocalDateTime startedAt,
      LocalDateTime finishedAt,
      LocalDateTime heartbeatAt,
      LocalDateTime leaseUntil,
      String leaseOwner) {}

  private record RecoveredTask(long id, String status, LocalDateTime runAfter) {}

  public record RunTaskSummary(
      int totalTasks,
      int successTasks,
      int failedTasks,
      int queuedTasks,
      int runningTasks,
      int retryWaitingTasks,
      int manualAttentionTasks,
      int cancelledTasks,
      LocalDateTime nextRunAfter) {
    /** 自动路径是否仍有可推进的工作；人工停放不属于活动。 */
    public boolean hasActiveTasks() {
      return queuedTasks > 0 || runningTasks > 0 || retryWaitingTasks > 0;
    }

    /** 是否存在已撤销执行权、等待维护人员决定继续或取消的任务。 */
    public boolean awaitsManualDecision() {
      return manualAttentionTasks > 0;
    }
  }
}
