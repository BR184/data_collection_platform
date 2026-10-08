package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.FactManualDisposition;
import com.data.collection.platform.entity.FactTaskWaitReason;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class FactBuildTaskService {
  static final String BUSY_MESSAGE = "已有事实构建任务正在执行，请稍后再试";
  private static final long FACT_BUILD_LOCK_KEY = 2026043001L;
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_QUEUED = "QUEUED";
  private static final String STATUS_RETRY_WAITING = "RETRY_WAITING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_SKIPPED = "SKIPPED";
  /** 人工停放：已撤销执行权、等待维护人员决定的事实任务状态。 */
  public static final String STATUS_PAUSED = "PAUSED";
  private static final String DISPOSITION_REQUIRES_DECISION =
      FactManualDisposition.REQUIRES_DECISION.name();
  /** 单轮遗留任务巡检上限；只做有界 SQL 状态移交，不执行事实工作。 */
  private static final int ORPHANED_TASK_PARK_LIMIT = 200;
  private static final String TRIGGER_MANUAL = "MANUAL";
  private static final String TRIGGER_MIRROR_SYNC = "MIRROR_SYNC";
  private static final int DEFAULT_MAX_RETRY_COUNT = 3;
  private static final int MAX_ASSIGNMENT_TASKS_PER_PASS = 8;

  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;
  private final SyncRunEventRecorder eventRecorder;
  private final String lockOwner = UUID.randomUUID().toString();

  public FactBuildTaskService(
      JdbcTemplate jdbcTemplate,
      DataSource dataSource,
      SyncRunEventRecorder eventRecorder) {
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
    this.eventRecorder = eventRecorder;
  }

  public FactBuildResponse runGuarded(
      String scope, boolean full, Supplier<FactBuildResponse> action) {
    return runGuarded(scope, full, null, action);
  }

  /**
   * 在全局事实构建锁下执行操作，并将任务状态关联到可选的同步运行。
   *
   * @param scope 事实构建范围，用于诊断和任务展示
   * @param full 是否全量构建
   * @param syncRunId 所属同步运行编号；为空时创建独立手工任务记录
   * @param action 获得锁后执行的事实构建逻辑
   * @return 事实构建结果
   */
  public FactBuildResponse runGuarded(
      String scope, boolean full, Long syncRunId, Supplier<FactBuildResponse> action) {
    String safeScope = normalizeScope(scope);
    try (Connection connection = dataSource.getConnection()) {
      if (!tryAcquireLock(connection)) {
        recordSkipped(safeScope, full, syncRunId, BUSY_MESSAGE);
        if (syncRunId != null) {
          throw new BizException(BUSY_MESSAGE);
        }
        return new FactBuildResponse(safeScope, full, 0, BUSY_MESSAGE);
      }
      Long taskId = startTask(safeScope, full, syncRunId);
      try {
        // 构建逻辑自管事务（全量分批提交、增量小事务）；此处只负责互斥锁与任务状态记账。
        FactBuildResponse response = action.get();
        finishTask(taskId, STATUS_SUCCESS, response.affectedRows(), response.message(), null);
        return response;
      } catch (RuntimeException error) {
        finishTask(taskId, STATUS_FAILED, 0, "事实构建失败", error.getMessage());
        throw error;
      } finally {
        releaseLock(connection);
      }
    } catch (DataAccessException error) {
      throw error;
    } catch (RuntimeException error) {
      throw error;
    } catch (Exception error) {
      throw new IllegalStateException("事实构建任务锁处理失败", error);
    }
  }

  static boolean wasSkippedBecauseBusy(FactBuildResponse response) {
    return response != null && BUSY_MESSAGE.equals(response.message());
  }

  /**
   * 为指定事实刷新运行创建其支持的事实任务。
   *
   * @param config 事实来源配置
   * @param full 是否执行全量事实构建
   * @param factRunId 所属 {@code FACT_REFRESH} 运行 ID
   * @return 实际新建的任务数
   */
  public int enqueueFullFactRefreshTasks(GitlabSyncConfig config, Long factRunId) {
    if (config == null || config.getId() == null) {
      return 0;
    }
    if (factRunId == null || factRunId <= 0L) {
      throw new IllegalArgumentException("事实刷新任务必须归属 FACT_REFRESH 运行");
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    int queued = 0;
    List<FactType> supportedFactTypes = GitlabFactDependencyCatalog.supportedFactTypes(config);
    for (FactType factType : supportedFactTypes) {
      queued += enqueueFactRefreshTask(
          config.getId(), sourceInstance, factType.name(), factRunId);
    }
    return queued;
  }

  /**
   * 把该来源尚未发布到最新变化版本的根分配为有界事实任务。
   *
   * <p>待发布权威是 {@code fact_change_heads}（每根每事实族一行，行数有界）：领取条件即
   * {@code published_version < latest_change_version}，不再依赖目标的归属状态列。同一来源同时
   * 只允许一个事实刷新运行，运行租约本身就是领取凭据，因此失败运行不会留下需要释放的归属。
   *
   * <p>一个任务只对应一个事实类型的一个根 ID 批次，不创建任务内游标。
   */
  @Transactional
  public int assignPendingSourceTargetBatches(
      GitlabSyncConfig config,
      Long factRunId,
      int requestedBatchSize) {
    if (config == null || config.getId() == null || factRunId == null) {
      throw new IllegalArgumentException("目标事实任务必须包含配置和事实运行");
    }
    int batchSize = Math.max(1, Math.min(1000, requestedBatchSize));
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    int assignedTasks = 0;
    for (FactType factType : GitlabFactDependencyCatalog.supportedFactTypes(config)) {
      List<Long> rootIds;
      while (assignedTasks < MAX_ASSIGNMENT_TASKS_PER_PASS
          && !(rootIds = lockPendingRootIds(
                  config.getId(), sourceInstance, factType, batchSize))
              .isEmpty()) {
        Long taskId =
            insertTargetBatchTask(config.getId(), sourceInstance, factType, factRunId);
        int assigned = assignRoots(sourceInstance, factType, taskId, rootIds);
        if (assigned != rootIds.size()) {
          throw new IllegalStateException("事实任务根批次写入不完整：" + taskId);
        }
        assignedTasks++;
      }
    }
    return assignedTasks;
  }

  /** 返回任务当前归属的稳定根 ID（根批次关联表，任务终态即随任务收敛）。 */
  public List<Long> loadAssignedRootIds(QueuedFactBuildTask task) {
    return task == null || task.id() == null ? List.of() : loadAssignedRootIds(task.id());
  }

  /** 按任务主键返回当前归属的稳定根 ID。 */
  public List<Long> loadAssignedRootIds(long taskId) {
    return jdbcTemplate.queryForList(
        """
        select root_id
          from fact_build_task_roots
         where task_id = ?
         order by root_id
        """,
        Long.class,
        taskId);
  }

  /**
   * 取该事实族尚未发布到最新变化版本、且尚未被在途任务领取的根；同事务内锁行，保证与根批次写入原子。
   *
   * <p>{@code fact_build_task_roots} 就是领取记录（任务进入终态即删除行），因此排除它即可保证
   * 同一批根不会在同一轮或后续轮次被重复派发。
   */
  private List<Long> lockPendingRootIds(
      Long configId, String sourceInstance, FactType factType, int batchSize) {
    return jdbcTemplate.queryForList(
        """
        select head.root_id
          from fact_change_heads head
         where head.source_instance = ?
           and head.fact_type = ?
           and head.published_version < head.latest_change_version
           and exists (
             select 1
               from source_fact_publication_states state
              where state.config_id = ?
                and state.source_instance = head.source_instance
                and state.fact_type = head.fact_type
                and state.readiness_status = 'READY')
           and not exists (
             select 1
               from fact_build_task_roots claimed
              where claimed.source_instance = head.source_instance
                and claimed.fact_type = head.fact_type
                and claimed.root_id = head.root_id)
         order by head.root_id
         limit ?
         for update of head skip locked
        """,
        Long.class,
        sourceInstance,
        factType.name(),
        configId,
        batchSize);
  }

  private Long insertTargetBatchTask(
      Long configId, String sourceInstance, FactType factType, Long factRunId) {
    return jdbcTemplate.queryForObject(
        """
        insert into fact_build_tasks(
          run_id, scope, config_id, source_instance, fact_type, full_build,
          status, trigger_type, retry_count, max_retry_count, run_after,
          created_at, updated_at)
        values (?, ?, ?, ?, ?, false, ?, ?, 0, ?, current_timestamp,
                current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        String.valueOf(factRunId),
        factScope(factType.name(), sourceInstance) + "-target-batch",
        configId,
        sourceInstance,
        factType.name(),
        STATUS_QUEUED,
        TRIGGER_MIRROR_SYNC,
        DEFAULT_MAX_RETRY_COUNT);
  }

  private int assignRoots(
      String sourceInstance, FactType factType, Long taskId, List<Long> rootIds) {
    String values =
        String.join(", ", java.util.Collections.nCopies(rootIds.size(), "(?, ?, ?, ?)"));
    List<Object> args = new java.util.ArrayList<>(rootIds.size() * 4);
    for (Long rootId : rootIds) {
      args.add(taskId);
      args.add(sourceInstance);
      args.add(factType.name());
      args.add(rootId);
    }
    return jdbcTemplate.update(
        """
        insert into fact_build_task_roots(task_id, source_instance, fact_type, root_id)
        values %s
        on conflict (task_id, root_id) do nothing
        """.formatted(values),
        args.toArray());
  }

  /** 任务进入终态后释放根批次关联；待发布权威在版本头上，无需保留归属。 */
  public void releaseTaskRoots(Long taskId) {
    if (taskId == null) {
      return;
    }
    jdbcTemplate.update("delete from fact_build_task_roots where task_id = ?", taskId);
  }

  public int recoverTimedOutQueuedTasks() {
    List<RecoveredFactTask> requeued =
        jdbcTemplate.query(
            """
            update fact_build_tasks
               set status = ?,
                   retry_count = retry_count + 1,
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   run_after = current_timestamp,
                   message = 'Fact refresh task lease timed out; retry queued',
                   updated_at = current_timestamp
             where trigger_type = ?
               and status = ?
               and manual_disposition = 'NONE'
               and lease_until < current_timestamp
               and retry_count < max_retry_count
               and not exists (
                 select 1
                   from sync_runs run
                  where run.id::text = fact_build_tasks.run_id
                    and run.run_type = 'FACT_REFRESH'
                    and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
               )
             returning id, run_id, config_id, source_instance, retry_count
            """,
            (rs, rowNum) -> mapRecoveredFactTask(rs),
            STATUS_RETRY_WAITING,
            TRIGGER_MIRROR_SYNC,
            STATUS_RUNNING);
    for (RecoveredFactTask task : requeued) {
      eventRecorder.record(
          task.runId(),
          task.configId(),
          task.sourceInstance(),
          "FACT_BUILD_TIMEOUT_RETRY",
          "事实构建任务租约超时，自动重试（第 " + task.attempt() + " 次）");
    }
    List<RecoveredFactTask> exhausted =
        jdbcTemplate.query(
            """
            update fact_build_tasks
               set status = ?,
                   error_message = 'Fact refresh task lease timed out',
                   finished_at = current_timestamp,
                   updated_at = current_timestamp
             where trigger_type = ?
               and status = ?
               and manual_disposition = 'NONE'
               and lease_until < current_timestamp
               and retry_count >= max_retry_count
               and not exists (
                 select 1
                   from sync_runs run
                  where run.id::text = fact_build_tasks.run_id
                    and run.run_type = 'FACT_REFRESH'
                    and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
               )
             returning id, run_id, config_id, source_instance, retry_count
            """,
            (rs, rowNum) -> mapRecoveredFactTask(rs),
            STATUS_FAILED,
            TRIGGER_MIRROR_SYNC,
            STATUS_RUNNING);
    for (RecoveredFactTask task : exhausted) {
      releaseTaskRoots(task.taskId());
      eventRecorder.record(
          task.runId(),
          task.configId(),
          task.sourceInstance(),
          "FACT_BUILD_TIMEOUT_FAILED",
          "事实构建任务租约超时且自动重试已达上限，标记失败");
    }
    return requeued.size() + exhausted.size();
  }

  /**
   * 把失去父运行授权、已无任何自动认领路径的遗留任务转为人工待处理。
   *
   * <p>只处理排队与等待重试中的任务：租约仍有效的执行中任务由执行者按提交屏障自行收尾，避免与
   * 在途批次竞争同一行。转换保留持有根与原始错误原文，不改版本头、不推进已发布版本；自动路径
   * 依据 {@code PAUSED} 状态与 {@code manual_disposition} 不再派发这些任务。
   *
   * <p>处置结论只落在任务行上：父运行可能已经被清理，向它追加事件会触发外键失败并连带中止本事务。
   *
   * @return 本轮转为人工待处理的任务数
   */
  @Transactional
  public int parkOrphanedTasksForManualDecision() {
    return jdbcTemplate.update(
        """
        with orphaned as (
          select task.id
            from fact_build_tasks task
           where task.trigger_type = ?
             and task.manual_disposition = 'NONE'
             and task.status in (?, ?)
             and not exists (
               select 1
                 from sync_runs run
                where run.id::text = task.run_id
                  and run.run_type = 'FACT_REFRESH'
                  and run.status not in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
             )
           order by task.id
           for update of task skip locked
           limit ?
        )
        update fact_build_tasks task
           set status = ?,
               manual_disposition = ?,
               wait_reason = null,
               message = '父事实运行已结束，遗留任务停止自动派发，等待维护人员决定继续或取消',
               lock_owner = null,
               heartbeat_at = null,
               lease_until = null,
               updated_at = clock_timestamp()
          from orphaned
         where task.id = orphaned.id
        """,
        TRIGGER_MIRROR_SYNC,
        STATUS_QUEUED,
        STATUS_RETRY_WAITING,
        ORPHANED_TASK_PARK_LIMIT,
        STATUS_PAUSED,
        DISPOSITION_REQUIRES_DECISION);
  }

  /**
   * 续期当前 owner 持有的运行中事实任务租约。
   *
   * @return false 表示租约已易主（任务被回收重派），调用方应立即中止构建
   */
  public boolean renewTaskLease(QueuedFactBuildTask task, int leaseSeconds) {
    if (task == null || task.id() == null || task.leaseOwner() == null) {
      return false;
    }
    int updated =
        jdbcTemplate.update(
            """
            update fact_build_tasks
               set heartbeat_at = current_timestamp,
                   lease_until = current_timestamp + (? * interval '1 second'),
                   updated_at = current_timestamp
             where id = ?
               and status = 'RUNNING'
               and lock_owner = ?
            """,
            Math.max(1, leaseSeconds),
            task.id(),
            task.leaseOwner());
    return updated == 1;
  }

  private RecoveredFactTask mapRecoveredFactTask(ResultSet rs) throws java.sql.SQLException {    Long configId = rs.getObject("config_id") == null ? null : rs.getLong("config_id");
    return new RecoveredFactTask(
        rs.getLong("id"),
        parseEventRunId(rs.getString("run_id")),
        configId,
        rs.getString("source_instance"),
        rs.getInt("retry_count"));
  }

  private Long parseEventRunId(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private record RecoveredFactTask(
      long taskId, Long runId, Long configId, String sourceInstance, int attempt) {}


  /**
   * 认领指定事实刷新运行的下一项持久任务。
   *
   * <p>认领本身即父运行执行权的一次校验：任务所属运行必须仍由 {@code runLeaseToken} 持有、处于活动
   * 状态、未请求取消且租约未过期。批次提交还会在各自事务内再次校验父运行与任务两层执行权。
   *
   * @param factRunId 所属 {@code FACT_REFRESH} 运行 ID
   * @param runLeaseToken 父运行的执行令牌
   * @param owner 当前 worker 标识
   * @param leaseSeconds 租约秒数，最小按 1 秒处理
   * @return 已认领任务；父运行不再授权或没有待执行任务时返回 {@code null}
   */
  public QueuedFactBuildTask claimNextQueuedTaskForFactRun(
      Long factRunId, String runLeaseToken, String owner, int leaseSeconds) {
    if (factRunId == null || runLeaseToken == null || runLeaseToken.isBlank()) {
      return null;
    }
    String leaseToken = runLeaseToken;
    List<QueuedFactBuildTask> tasks = jdbcTemplate.query(
        """
        update fact_build_tasks
           set status = ?,
               lock_owner = ?,
               heartbeat_at = current_timestamp,
               lease_until = current_timestamp + (? * interval '1 second'),
               started_at = coalesce(started_at, current_timestamp),
               updated_at = current_timestamp
         where id = (
           select task.id
             from fact_build_tasks task
            where task.status in (?, ?)
              and task.trigger_type = ?
              and task.manual_disposition = 'NONE'
              and task.run_id = ?
              and task.run_after <= current_timestamp
              and exists (
                select 1
                  from sync_runs parent_run
                 where parent_run.id::text = task.run_id
                   and parent_run.run_type = 'FACT_REFRESH'
                   and parent_run.status in ('RUNNING', 'RETRYING')
                   and parent_run.lease_owner = ?
                   and parent_run.cancel_requested = false
                   and parent_run.lease_until >= clock_timestamp()
              )
            order by task.created_at asc, task.id asc
            for update skip locked
            limit 1
         )
         returning *
        """,
        (rs, rowNum) -> mapQueuedTask(rs, leaseToken),
        STATUS_RUNNING,
        owner,
        Math.max(1, leaseSeconds),
        STATUS_QUEUED,
        STATUS_RETRY_WAITING,
        TRIGGER_MIRROR_SYNC,
        String.valueOf(factRunId),
        leaseToken);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /**
   * 按 owner fencing 记录一次事实任务失败，并在未达到上限时安排指数退避重试。
   *
   * @return 新任务状态和下次可执行时间
   */
  @Transactional
  public FailureDisposition failOwnedTask(QueuedFactBuildTask task, String errorMessage) {
    if (task == null || task.id() == null || task.leaseOwner() == null) {
      throw new IllegalArgumentException("事实任务失败处理需要任务 ID 和租约 owner");
    }
    List<FailureDisposition> result =
        jdbcTemplate.query(
            """
            update fact_build_tasks
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
                   message = case
                       when retry_count + 1 < max_retry_count then '事实刷新失败，等待重试'
                       else '事实刷新达到自动重试上限'
                     end,
                   error_message = ?,
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = case
                       when retry_count + 1 < max_retry_count then null
                       else current_timestamp
                     end,
                   updated_at = current_timestamp
             where id = ?
               and status = 'RUNNING'
               and lock_owner = ?
             returning status, run_after
            """,
            (resultSet, rowNum) ->
                new FailureDisposition(
                    resultSet.getString("status"),
                    toLocalDateTime(resultSet.getTimestamp("run_after"))),
            errorMessage,
            task.id(),
            task.leaseOwner());
    if (result.size() != 1) {
      throw new IllegalStateException("事实任务租约已失效：" + task.id());
    }
    FailureDisposition disposition = result.getFirst();
    if (STATUS_FAILED.equals(disposition.status())) {
      releaseTaskRoots(task.id());
    }
    return disposition;
  }

  /**
   * 依赖尚未就绪时把任务退回排队，记录等待原因并让出执行权。
   *
   * <p>等待不生成失败、不消耗重试预算：任务回到 {@code QUEUED}、保留原错误原文，仅把 {@code run_after}
   * 推迟到未来并写入等待原因。父运行不再授权时拒绝转入等待（由巡检收敛为人工待处理），避免无授权的
   * 任务长期停留在等待态。
   *
   * @param task 当前持有租约的事实任务
   * @param deferSeconds 推迟秒数，必须为正
   * @return 下次最早可执行时间
   * @throws FactTaskLeaseLostException 任务租约或父运行授权已失效
   */
  @Transactional
  public LocalDateTime deferOwnedTask(QueuedFactBuildTask task, int deferSeconds) {
    if (task == null || task.id() == null || task.leaseOwner() == null) {
      throw new IllegalArgumentException("事实任务等待处理需要任务 ID 和租约 owner");
    }
    if (deferSeconds <= 0) {
      throw new IllegalArgumentException("事实任务等待时间必须为正");
    }
    List<LocalDateTime> deferred =
        jdbcTemplate.query(
            """
            update fact_build_tasks
               set status = ?,
                   wait_reason = ?,
                   message = '事实来源依赖代际尚未就绪，等待收敛后自动继续',
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   run_after = current_timestamp + (? * interval '1 second'),
                   updated_at = current_timestamp
             where id = ?
               and status = 'RUNNING'
               and lock_owner = ?
               and exists (
                 select 1
                   from sync_runs parent_run
                  where parent_run.id::text = fact_build_tasks.run_id
                    and parent_run.run_type = 'FACT_REFRESH'
                    and parent_run.status in ('RUNNING', 'RETRYING')
                    and parent_run.lease_owner = ?
                    and parent_run.cancel_requested = false)
             returning run_after
            """,
            (resultSet, rowNum) -> toLocalDateTime(resultSet.getTimestamp("run_after")),
            STATUS_QUEUED,
            FactTaskWaitReason.DEPENDENCY_SETTLING.name(),
            Math.max(1, deferSeconds),
            task.id(),
            task.leaseOwner(),
            task.factRunLeaseToken());
    if (deferred.size() != 1) {
      throw new FactTaskLeaseLostException(task.id(), "等待处理时任务租约或父运行授权已失效");
    }
    return deferred.getFirst();
  }

  /** 汇总一个 FACT_REFRESH 运行下的事实批次状态。 */
  public RunTaskSummary summarizeFactRun(Long factRunId) {
    if (factRunId == null) {
      return RunTaskSummary.empty();
    }
    return jdbcTemplate.queryForObject(
        """
        select count(*) as total_tasks,
               count(*) filter (where status = 'SUCCESS') as success_tasks,
               count(*) filter (where status = 'FAILED' and manual_disposition = 'NONE') as failed_tasks,
               count(*) filter (where status = 'QUEUED' and manual_disposition = 'NONE'
                                  and wait_reason is null) as queued_tasks,
               count(*) filter (where status = 'RUNNING' and manual_disposition = 'NONE') as running_tasks,
               count(*) filter (where status = 'RETRY_WAITING' and manual_disposition = 'NONE') as retry_waiting_tasks,
               count(*) filter (where status = 'QUEUED' and manual_disposition = 'NONE'
                                  and wait_reason is not null) as dependency_waiting_tasks,
               count(*) filter (where manual_disposition = 'REQUIRES_DECISION') as manual_attention_tasks,
               count(*) filter (where manual_disposition = 'CANCELLED') as cancelled_tasks,
               min(run_after) filter (where manual_disposition = 'NONE'
                                        and status in ('QUEUED', 'RETRY_WAITING')
                                        and run_after > current_timestamp) as next_run_after,
               coalesce(sum(affected_rows) filter (where status = 'SUCCESS'), 0) as affected_rows
          from fact_build_tasks
         where run_id = ? and trigger_type = ?
        """,
        (resultSet, rowNum) ->
            new RunTaskSummary(
                resultSet.getInt("total_tasks"),
                resultSet.getInt("success_tasks"),
                resultSet.getInt("failed_tasks"),
                resultSet.getInt("queued_tasks"),
                resultSet.getInt("running_tasks"),
                resultSet.getInt("retry_waiting_tasks"),
                resultSet.getInt("dependency_waiting_tasks"),
                resultSet.getInt("manual_attention_tasks"),
                resultSet.getInt("cancelled_tasks"),
                toLocalDateTime(resultSet.getTimestamp("next_run_after")),
                resultSet.getLong("affected_rows")),
        String.valueOf(factRunId),
        TRIGGER_MIRROR_SYNC);
  }

  /** 判断来源是否仍有尚未发布到最新变化版本的根；权威读法是版本头，与历史目标规模无关。 */
  public boolean hasUnpublishedTargets(Long configId, String sourceInstance) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from fact_change_heads head
                join source_fact_publication_states state
                  on state.config_id = ?
                 and state.source_instance = head.source_instance
                 and state.fact_type = head.fact_type
                 and state.readiness_status = 'READY'
               where head.source_instance = ?
                 and head.published_version < head.latest_change_version
            )
            """,
            Boolean.class,
            configId,
            sourceInstance);
    return Boolean.TRUE.equals(exists);
  }

  /**
   * 锁定并读取待人工处置的事实任务。
   *
   * <p>处置命令必须先持有任务行锁再校验归属与状态，否则与巡检停放、新运行接管并发交错时会重复处置。
   *
   * @param taskId 任务主键
   * @return 任务快照；任务不存在时返回 {@code null}
   */
  public FactTaskSnapshot lockTaskForResolution(long taskId) {
    List<FactTaskSnapshot> tasks =
        jdbcTemplate.query(
            """
            select id, run_id, config_id, source_instance, fact_type, scope, full_build, status,
                   manual_disposition, error_message, message, retry_count, max_retry_count,
                   started_at, finished_at, lock_owner
              from fact_build_tasks
             where id = ?
               for update
            """,
            FactBuildTaskService::mapFactTaskSnapshot,
            taskId);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /** 按 {@code resumed_from_task_id} 查找既有接管任务，使重复的继续命令返回同一个新运行。 */
  public FactTaskSnapshot findResumedTask(long taskId) {
    List<FactTaskSnapshot> tasks =
        jdbcTemplate.query(
            """
            select id, run_id, config_id, source_instance, fact_type, scope, full_build, status,
                   manual_disposition, error_message, message, retry_count, max_retry_count,
                   started_at, finished_at, lock_owner
              from fact_build_tasks
             where resumed_from_task_id = ?
             order by id desc
             limit 1
            """,
            FactBuildTaskService::mapFactTaskSnapshot,
            taskId);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /** 把任务当前归属的根全部释放；人工取消后这些根可被后续合法运行重新领取。 */
  public int cancelTaskByHumanDecision(long taskId, String message) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_build_tasks
               set status = 'SKIPPED',
                   manual_disposition = 'CANCELLED',
                   message = ?,
                   lock_owner = null,
                   heartbeat_at = null,
                   lease_until = null,
                   finished_at = coalesce(finished_at, current_timestamp),
                   updated_at = current_timestamp
             where id = ? and lock_owner is null
            """,
            message,
            taskId);
    if (updated == 1) {
      releaseTaskRoots(taskId);
    }
    return updated;
  }

  /**
   * 建立承接原意图的新 QUEUED 任务并把原任务持有的根原子转交给它。
   *
   * <p>根只在 {@code fact_build_task_roots} 内移动，同一事务提交，因此外部不会观察到根处于"无人持有"
   * 的中间态；发布权威仍是版本头，任务自身不构成第二套控制面。
   *
   * @param source 原任务快照
   * @param newRunId 承接该意图的新运行
   * @param full 是否按该事实族全量重建
   * @param rootIds 原任务持有的根；为空表示按当前待发布根或全量意图恢复
   * @return 新任务主键
   */
  public long insertResumedTask(
      FactTaskSnapshot source, Long newRunId, boolean full, List<Long> rootIds) {
    List<Long> boundedRoots = rootIds == null ? List.of() : rootIds.stream().distinct().sorted().toList();
    String payloadJson =
        boundedRoots.isEmpty()
            ? null
            : "{\"rootIds\":" + boundedRoots.toString().replace(" ", "") + "}";
    Long newTaskId =
        jdbcTemplate.queryForObject(
            """
            insert into fact_build_tasks(
              run_id, scope, config_id, source_instance, fact_type, full_build, status, trigger_type,
              retry_count, max_retry_count, run_after, payload_json, resumed_from_task_id,
              created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, ?, ?,
                    current_timestamp, current_timestamp)
            returning id
            """,
            Long.class,
            String.valueOf(newRunId),
            source.scope(),
            source.configId(),
            source.sourceInstance(),
            source.factType(),
            full,
            STATUS_QUEUED,
            TRIGGER_MANUAL,
            DEFAULT_MAX_RETRY_COUNT,
            payloadJson,
            source.id());
    if (!boundedRoots.isEmpty()) {
      jdbcTemplate.update(
          "update fact_build_task_roots set task_id = ? where task_id = ?",
          newTaskId,
          source.id());
    }
    return newTaskId;
  }

  /** 把该事实族当前待发布的根按有界批次登记到指定任务，用于"当前待发布根"继续模式。 */
  public int assignPendingRootsToTask(
      Long configId, String sourceInstance, FactType factType, long taskId, int batchSize) {
    List<Long> rootIds =
        lockPendingRootIds(
            configId, sourceInstance, factType, Math.max(1, Math.min(1000, batchSize)));
    if (rootIds.isEmpty()) {
      return 0;
    }
    return assignRoots(sourceInstance, factType, taskId, rootIds);
  }

  /** 原任务转人工继续后的终态记录；保留原错误与时间，原运行编号不变。 */
  public int markTaskResumed(long taskId, String message) {
    return jdbcTemplate.update(
        """
        update fact_build_tasks
           set status = 'SKIPPED',
               manual_disposition = 'RESUMED',
               message = ?,
               lock_owner = null,
               heartbeat_at = null,
               lease_until = null,
               finished_at = coalesce(finished_at, current_timestamp),
               updated_at = current_timestamp
         where id = ? and lock_owner is null
        """,
        message,
        taskId);
  }

  /**
   * 统计来源仍未发布到最新变化版本的根数量。
   *
   * @param factType 为空时统计该来源全部事实族
   */
  public long countUnpublishedTargets(Long configId, String sourceInstance, String factType) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      return 0L;
    }
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from fact_change_heads head
              join source_fact_publication_states state
                on state.config_id = ?
               and state.source_instance = head.source_instance
               and state.fact_type = head.fact_type
               and state.readiness_status = 'READY'
             where head.source_instance = ?
               and head.published_version < head.latest_change_version
               and (?::text is null or head.fact_type = ?)
            """,
            Long.class,
            configId,
            sourceInstance,
            factType,
            factType);
    return count == null ? 0L : count;
  }

  public void markQueuedTaskFullBuild(Long taskId) {
    if (taskId == null) {
      return;
    }
    jdbcTemplate.update(
        """
        update fact_build_tasks
           set full_build = true,
               updated_at = current_timestamp
         where id = ?
        """,
        taskId);
  }

  private static FactTaskSnapshot mapFactTaskSnapshot(ResultSet resultSet, int rowNum)
      throws java.sql.SQLException {
    return new FactTaskSnapshot(
        resultSet.getLong("id"),
        resultSet.getString("run_id"),
        resultSet.getObject("config_id") == null ? null : resultSet.getLong("config_id"),
        resultSet.getString("source_instance"),
        resultSet.getString("fact_type"),
        resultSet.getString("scope"),
        resultSet.getBoolean("full_build"),
        resultSet.getString("status"),
        resultSet.getString("manual_disposition"),
        resultSet.getString("error_message"),
        resultSet.getString("message"),
        resultSet.getInt("retry_count"),
        resultSet.getInt("max_retry_count"),
        resultSet.getTimestamp("started_at") == null
            ? null
            : resultSet.getTimestamp("started_at").toLocalDateTime(),
        resultSet.getTimestamp("finished_at") == null
            ? null
            : resultSet.getTimestamp("finished_at").toLocalDateTime(),
        resultSet.getString("lock_owner"));
  }

  public FactBuildTaskResponse latest(String scope) {
    String safeScope = TextQuerySupport.trimToNull(scope);
    List<FactBuildTaskResponse> rows =
        safeScope == null
            ? jdbcTemplate.query(
                """
                select *
                  from fact_build_tasks
                 order by created_at desc, id desc
                 limit 1
                """,
                (rs, rowNum) -> mapTask(rs))
            : jdbcTemplate.query(
                """
                select *
                  from fact_build_tasks
                 where scope = ?
                 order by created_at desc, id desc
                 limit 1
                """,
                (rs, rowNum) -> mapTask(rs),
                normalizeScope(safeScope));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public boolean hasSuccessfulFullBuild(String sourceInstance, String factType) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    String normalizedFactType = factType == null ? "" : factType.trim().toUpperCase(Locale.ROOT);
    String normalizedScope = factScope(normalizedFactType, normalizedSource);
    String normalizedAllScope = factScope("all", normalizedSource);
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from fact_build_tasks
             where status = ?
               and full_build = true
               and (
                    (source_instance = ? and upper(coalesce(fact_type, '')) = ?)
                 or lower(coalesce(scope, '')) = ?
                 or lower(coalesce(scope, '')) = ?
               )
            """,
            Long.class,
            STATUS_SUCCESS,
            normalizedSource,
            normalizedFactType,
            normalizedScope,
            normalizedAllScope);
    return count != null && count > 0;
  }

  private Long startTask(String scope, boolean full, Long syncRunId) {
    return jdbcTemplate.queryForObject(
        """
        insert into fact_build_tasks(
          run_id, scope, full_build, status, trigger_type, lock_owner, started_at, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        syncRunId == null ? UUID.randomUUID().toString() : String.valueOf(syncRunId),
        scope,
        full,
        STATUS_RUNNING,
        TRIGGER_MANUAL,
        lockOwner);
  }

  private int enqueueFactRefreshTask(
      Long configId,
      String sourceInstance,
      String factType,
      Long factRunId) {
    String scope = factScope(factType, sourceInstance);
    return insertFactRefreshTask(
        configId,
        sourceInstance,
        factType,
        scope,
        factRunId);
  }

  private int insertFactRefreshTask(
      Long configId,
      String sourceInstance,
      String factType,
      String scope,
      Long factRunId) {
    return jdbcTemplate.update(
        """
        insert into fact_build_tasks(
          run_id, scope, config_id, source_instance, fact_type, full_build, status, trigger_type,
          retry_count, max_retry_count, run_after, created_at, updated_at
        )
        select ?, ?, ?, ?, ?, true, ?, ?, 0, ?, current_timestamp, current_timestamp, current_timestamp
        where not exists (
          select 1
            from fact_build_tasks
           where run_id = ?
             and fact_type = ?
             and trigger_type = ?
        )
        """,
        String.valueOf(factRunId),
        scope,
        configId,
        sourceInstance,
        factType,
        STATUS_QUEUED,
        TRIGGER_MIRROR_SYNC,
        DEFAULT_MAX_RETRY_COUNT,
        String.valueOf(factRunId),
        factType,
        TRIGGER_MIRROR_SYNC);
  }

  private void recordSkipped(String scope, boolean full, Long syncRunId, String message) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(
          run_id, scope, full_build, status, trigger_type, lock_owner, affected_rows, message,
          started_at, finished_at, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp)
        """,
        syncRunId == null ? UUID.randomUUID().toString() : String.valueOf(syncRunId),
        scope,
        full,
        STATUS_SKIPPED,
        TRIGGER_MANUAL,
        lockOwner,
        message);
  }

  private void finishTask(
      Long taskId, String status, int affectedRows, String message, String errorMessage) {
    jdbcTemplate.update(
        """
        update fact_build_tasks
           set status = ?,
               affected_rows = ?,
               message = ?,
               error_message = ?,
               lock_owner = null,
               heartbeat_at = null,
               lease_until = null,
               finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = ?
        """,
        status,
        affectedRows,
        message,
        errorMessage,
        taskId);
  }

  private QueuedFactBuildTask mapQueuedTask(ResultSet rs, String factRunLeaseToken)
      throws java.sql.SQLException {
    return new QueuedFactBuildTask(
        rs.getLong("id"),
        parseFactRunId(rs.getString("run_id")),
        factRunLeaseToken,
        rs.getLong("config_id"),
        rs.getString("source_instance"),
        rs.getString("fact_type"),
        rs.getString("scope"),
        rs.getBoolean("full_build"),
        rs.getInt("retry_count"),
        rs.getInt("max_retry_count"),
        rs.getString("lock_owner"),
        toLocalDateTime(rs.getTimestamp("lease_until")));
  }

  private Long parseFactRunId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("事实刷新任务缺少 FACT_REFRESH 运行 ID");
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException error) {
      throw new IllegalStateException("事实刷新任务包含非法 FACT_REFRESH 运行 ID: " + value, error);
    }
  }

  private boolean tryAcquireLock(Connection connection) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
      statement.setLong(1, FACT_BUILD_LOCK_KEY);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private void releaseLock(Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
      statement.setLong(1, FACT_BUILD_LOCK_KEY);
      statement.execute();
    } catch (Exception error) {
      log.warn("Failed to release fact build advisory lock", error);
    }
  }

  private FactBuildTaskResponse mapTask(ResultSet rs) throws java.sql.SQLException {
    return new FactBuildTaskResponse(
        rs.getLong("id"),
        rs.getString("run_id"),
        rs.getString("scope"),
        rs.getBoolean("full_build"),
        rs.getString("status"),
        rs.getString("trigger_type"),
        rs.getString("lock_owner"),
        rs.getInt("affected_rows"),
        rs.getString("message"),
        rs.getString("error_message"),
        toLocalDateTime(rs.getTimestamp("started_at")),
        toLocalDateTime(rs.getTimestamp("finished_at")),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  public record FailureDisposition(String status, LocalDateTime runAfter) {
    public boolean retryWaiting() {
      return STATUS_RETRY_WAITING.equals(status);
    }

    public boolean failed() {
      return STATUS_FAILED.equals(status);
    }
  }

  /**
   * 事实任务的完整行快照，用于人工处置命令的校验、诊断与接管。
   *
   * @param runId 原事实运行编号的字符串形式；手工路径可能是 UUID，因此不转为数字
   * @param manualDisposition 人工处置状态码
   * @param lockOwner 当前任务执行权持有者；为空表示执行权已撤销
   */
  public record FactTaskSnapshot(
      long id,
      String runId,
      Long configId,
      String sourceInstance,
      String factType,
      String scope,
      boolean full,
      String status,
      String manualDisposition,
      String errorMessage,
      String message,
      int retryCount,
      int maxRetryCount,
      LocalDateTime startedAt,
      LocalDateTime finishedAt,
      String lockOwner) {}

  public record RunTaskSummary(
      int totalTasks,
      int successTasks,
      int failedTasks,
      int queuedTasks,
      int runningTasks,
      int retryWaitingTasks,
      int dependencyWaitingTasks,
      int manualAttentionTasks,
      int cancelledTasks,
      LocalDateTime nextRunAfter,
      long affectedRows) {
    private static RunTaskSummary empty() {
      return new RunTaskSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0L);
    }

    /** 自动路径是否仍有可推进的工作；等待重试与依赖等待同属活动，人工停放不属于活动。 */
    public boolean hasActiveTasks() {
      return queuedTasks > 0
          || runningTasks > 0
          || retryWaitingTasks > 0
          || dependencyWaitingTasks > 0;
    }

    /** 是否存在已撤销执行权、等待维护人员决定继续或取消的任务。 */
    public boolean awaitsManualDecision() {
      return manualAttentionTasks > 0;
    }
  }

  private String normalizeScope(String scope) {
    String normalized = TextQuerySupport.trimToNull(scope);
    if (normalized == null) {
      return "all";
    }
    normalized = normalized.trim().toLowerCase(java.util.Locale.ROOT);
    int sourceSeparator = normalized.indexOf(':');
    if (sourceSeparator > 0 && sourceSeparator < normalized.length() - 1) {
      String source = GitlabSourceInstanceSupport.normalizeSourceInstance(normalized.substring(0, sourceSeparator));
      String scoped = normalizeBaseScope(normalized.substring(sourceSeparator + 1).replace('_', '-'));
      return source + ":" + scoped;
    }
    return normalizeBaseScope(normalized.replace('_', '-'));
  }

  private String normalizeBaseScope(String normalized) {
    return switch (normalized) {
      case "merge_request" -> "merge-request";
      case "issue", "merge-request", "integration-test", "all" -> normalized;
      default -> "all";
    };
  }

  private String factScope(String factType, String sourceInstance) {
    String baseScope = switch (factType.toUpperCase(Locale.ROOT)) {
      case "ISSUE" -> "issue";
      case "MERGE_REQUEST" -> "merge-request";
      case "INTEGRATION_TEST" -> "integration-test";
      default -> "all";
    };
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    return GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE.equals(normalizedSource)
        ? baseScope
        : normalizedSource + ":" + baseScope;
  }
}
