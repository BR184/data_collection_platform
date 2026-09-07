package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
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
   * 把同一来源全部历史运行尚未发布的版本化目标分配为有界事实任务。
   *
   * <p>一个任务只对应一个事实类型的一个根 ID 批次，不创建任务内游标。已由其他交错运行覆盖的
   * 目标直接按版本头结算为已发布。
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
      settleCoveredTargets(sourceInstance, factType);
      List<Long> rootIds;
        while (assignedTasks < MAX_ASSIGNMENT_TASKS_PER_PASS
            && !(rootIds = lockPendingRootIds(
              config.getId(), sourceInstance, factType, batchSize)).isEmpty()) {
        Long taskId = insertTargetBatchTask(
            config.getId(), sourceInstance, factType, factRunId);
        int assigned = assignRoots(
            sourceInstance, factType, factRunId, taskId, rootIds);
        if (assigned == 0) {
          throw new IllegalStateException("事实目标批次归属发生并发变化");
        }
        assignedTasks++;
      }
    }
    return assignedTasks;
  }

  /** 返回领取时仍归属当前事实任务的稳定根 ID。 */
  public List<Long> loadAssignedRootIds(QueuedFactBuildTask task) {
    if (task == null || task.id() == null) {
      return List.of();
    }
    return jdbcTemplate.queryForList(
        """
        select distinct target.root_id
          from sync_run_fact_targets target
          join fact_change_heads head
            on head.source_instance = target.source_instance
           and head.fact_type = target.fact_type
           and head.root_id = target.root_id
         where target.assigned_fact_build_task_id = ?
           and target.assigned_fact_run_id = ?
           and target.source_instance = ?
           and target.fact_type = ?
           and target.publication_status = 'QUEUED'
           and head.published_version < target.change_version
         order by target.root_id
        """,
        Long.class,
        task.id(),
        task.factRunId(),
        task.sourceInstance(),
        task.factType());
  }

  private void settleCoveredTargets(String sourceInstance, FactType factType) {
    jdbcTemplate.update(
        """
        update sync_run_fact_targets target
           set publication_status = 'PUBLISHED',
               published_version = head.published_version,
               published_by_fact_build_task_id = head.published_by_fact_build_task_id,
               published_at = current_timestamp,
               updated_at = current_timestamp
          from fact_change_heads head
         where target.source_instance = ?
           and target.fact_type = ?
           and target.publication_status <> 'PUBLISHED'
           and head.source_instance = target.source_instance
           and head.fact_type = target.fact_type
           and head.root_id = target.root_id
           and head.published_version >= target.change_version
        """,
        sourceInstance,
        factType.name());
  }

  private List<Long> lockPendingRootIds(
      Long configId, String sourceInstance, FactType factType, int batchSize) {
    return jdbcTemplate.queryForList(
        """
        select root_id
          from sync_run_fact_targets
         where source_instance = ?
           and fact_type = ?
           and publication_status = 'PENDING'
           and assigned_fact_build_task_id is null
           and exists (
             select 1
               from source_fact_publication_states state
              where state.config_id = ?
                and state.source_instance = sync_run_fact_targets.source_instance
                and state.fact_type = sync_run_fact_targets.fact_type
                and state.readiness_status = 'READY')
         group by root_id
         order by root_id
         limit ?
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
      String sourceInstance,
      FactType factType,
      Long factRunId,
      Long taskId,
      List<Long> rootIds) {
    String placeholders = String.join(", ", java.util.Collections.nCopies(rootIds.size(), "?"));
    List<Object> args = new java.util.ArrayList<>(4 + rootIds.size());
    args.add(factRunId);
    args.add(taskId);
    args.add(sourceInstance);
    args.add(factType.name());
    args.addAll(rootIds);
    return jdbcTemplate.update(
        """
        update sync_run_fact_targets
           set publication_status = 'QUEUED',
               assigned_fact_run_id = ?,
               assigned_fact_build_task_id = ?,
               updated_at = current_timestamp
         where source_instance = ?
           and fact_type = ?
           and publication_status = 'PENDING'
           and assigned_fact_build_task_id is null
           and root_id in (%s)
        """.formatted(placeholders),
        args.toArray());
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

  private RecoveredFactTask mapRecoveredFactTask(ResultSet rs) throws java.sql.SQLException {
    Long configId = rs.getObject("config_id") == null ? null : rs.getLong("config_id");
    return new RecoveredFactTask(
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
      Long runId, Long configId, String sourceInstance, int attempt) {}

  public QueuedFactBuildTask claimNextQueuedTask(String owner, int leaseSeconds) {
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
           select id
             from fact_build_tasks
            where status in (?, ?)
              and trigger_type = ?
              and run_after <= current_timestamp
              and not exists (
                select 1
                  from sync_runs run
                 where run.id::text = fact_build_tasks.run_id
                   and run.run_type = 'FACT_REFRESH'
                   and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
              )
            order by created_at asc, id asc
            for update skip locked
            limit 1
         )
         returning *
        """,
        (rs, rowNum) -> mapQueuedTask(rs),
        STATUS_RUNNING,
        owner,
        Math.max(1, leaseSeconds),
        STATUS_QUEUED,
        STATUS_RETRY_WAITING,
        TRIGGER_MIRROR_SYNC);
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  /**
   * 认领指定事实刷新运行的下一项持久任务。
   *
   * @param factRunId 所属 {@code FACT_REFRESH} 运行 ID
   * @param owner 当前 worker 标识
   * @param leaseSeconds 租约秒数，最小按 1 秒处理
   * @return 已认领任务；没有待执行任务时返回 {@code null}
   */
  public QueuedFactBuildTask claimNextQueuedTaskForFactRun(
      Long factRunId, String owner, int leaseSeconds) {
    if (factRunId == null) {
      return null;
    }
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
           select id
             from fact_build_tasks
            where status in (?, ?)
              and trigger_type = ?
              and run_id = ?
              and run_after <= current_timestamp
            order by created_at asc, id asc
            for update skip locked
            limit 1
         )
         returning *
        """,
        (rs, rowNum) -> mapQueuedTask(rs),
        STATUS_RUNNING,
        owner,
        Math.max(1, leaseSeconds),
        STATUS_QUEUED,
        STATUS_RETRY_WAITING,
        TRIGGER_MIRROR_SYNC,
        String.valueOf(factRunId));
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
    return result.getFirst();
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
               count(*) filter (where status = 'FAILED') as failed_tasks,
               count(*) filter (where status = 'QUEUED') as queued_tasks,
               count(*) filter (where status = 'RUNNING') as running_tasks,
               count(*) filter (where status = 'RETRY_WAITING') as retry_waiting_tasks,
               min(run_after) filter (where status = 'RETRY_WAITING') as next_run_after,
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
                toLocalDateTime(resultSet.getTimestamp("next_run_after")),
                resultSet.getLong("affected_rows")),
        String.valueOf(factRunId),
        TRIGGER_MIRROR_SYNC);
  }

  /** 判断来源是否仍有尚未被版本头覆盖的持久目标。 */
  public boolean hasUnpublishedTargets(Long configId, String sourceInstance) {
    if (configId == null || sourceInstance == null || sourceInstance.isBlank()) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from sync_run_fact_targets target
                join fact_change_heads head
                  on head.source_instance = target.source_instance
                 and head.fact_type = target.fact_type
                 and head.root_id = target.root_id
                join source_fact_publication_states state
                  on state.config_id = ?
                 and state.source_instance = target.source_instance
                 and state.fact_type = target.fact_type
                 and state.readiness_status = 'READY'
               where target.source_instance = ?
                 and target.publication_status <> 'PUBLISHED'
                 and head.published_version < target.change_version
            )
            """,
            Boolean.class,
            configId,
            sourceInstance);
    return Boolean.TRUE.equals(exists);
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

  private QueuedFactBuildTask mapQueuedTask(ResultSet rs) throws java.sql.SQLException {
    return new QueuedFactBuildTask(
        rs.getLong("id"),
        parseFactRunId(rs.getString("run_id")),
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

  public record RunTaskSummary(
      int totalTasks,
      int successTasks,
      int failedTasks,
      int queuedTasks,
      int runningTasks,
      int retryWaitingTasks,
      LocalDateTime nextRunAfter,
      long affectedRows) {
    private static RunTaskSummary empty() {
      return new RunTaskSummary(0, 0, 0, 0, 0, 0, null, 0L);
    }

    public boolean hasActiveTasks() {
      return queuedTasks > 0 || runningTasks > 0 || retryWaitingTasks > 0;
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
