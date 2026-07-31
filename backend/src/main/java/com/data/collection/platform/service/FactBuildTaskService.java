package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.common.exception.BizException;
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

@Service
@Slf4j
public class FactBuildTaskService {
  static final String BUSY_MESSAGE = "已有事实构建任务正在执行，请稍后再试";
  private static final long FACT_BUILD_LOCK_KEY = 2026043001L;
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_TIMEOUT = "TIMEOUT";
  private static final String STATUS_SKIPPED = "SKIPPED";
  private static final String TRIGGER_MANUAL = "MANUAL";
  private static final String TRIGGER_MIRROR_SYNC = "MIRROR_SYNC";
  private static final int DEFAULT_MAX_RETRY_COUNT = 3;

  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;
  private final FactPublicationTransaction publicationTransaction;
  private final String lockOwner = UUID.randomUUID().toString();

  public FactBuildTaskService(
      JdbcTemplate jdbcTemplate,
      DataSource dataSource,
      FactPublicationTransaction publicationTransaction) {
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
    this.publicationTransaction = publicationTransaction;
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
        FactBuildResponse response = publicationTransaction.execute(action);
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
  public int enqueueFactRefreshTasks(GitlabSyncConfig config, boolean full, Long factRunId) {
    if (config == null || config.getId() == null) {
      return 0;
    }
    if (factRunId == null || factRunId <= 0L) {
      throw new IllegalArgumentException("事实刷新任务必须归属 FACT_REFRESH 运行");
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    int queued = 0;
    List<String> supportedFactTypes = GitlabFactRefreshRequirements.supportedFactTypes(config);
    if (supportedFactTypes.contains(GitlabFactRefreshRequirements.FACT_TYPE_ISSUE)) {
      queued += enqueueFactRefreshTask(config.getId(), sourceInstance, "ISSUE", full, factRunId);
    }
    if (supportedFactTypes.contains(GitlabFactRefreshRequirements.FACT_TYPE_MERGE_REQUEST)) {
      queued += enqueueFactRefreshTask(config.getId(), sourceInstance, "MERGE_REQUEST", full, factRunId);
    }
    if (supportedFactTypes.contains(GitlabFactRefreshRequirements.FACT_TYPE_INTEGRATION_TEST)) {
      queued += enqueueFactRefreshTask(config.getId(), sourceInstance, "INTEGRATION_TEST", full, factRunId);
    }
    return queued;
  }

  public int recoverTimedOutQueuedTasks() {
    int retried = jdbcTemplate.update(
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
        """,
        STATUS_PENDING,
        TRIGGER_MIRROR_SYNC,
        STATUS_RUNNING);
    int timedOut = jdbcTemplate.update(
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
        """,
        STATUS_TIMEOUT,
        TRIGGER_MIRROR_SYNC,
        STATUS_RUNNING);
    return retried + timedOut;
  }

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
            where status = ?
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
        STATUS_PENDING,
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
            where status = ?
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
        STATUS_PENDING,
        TRIGGER_MIRROR_SYNC,
        String.valueOf(factRunId));
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  public void finishQueuedTask(Long taskId, String status, int affectedRows, String message, String errorMessage) {
    finishTask(taskId, status, affectedRows, message, errorMessage);
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
      boolean full,
      Long factRunId) {
    String scope = factScope(factType, sourceInstance);
    return insertFactRefreshTask(
        configId,
        sourceInstance,
        factType,
        scope,
        full,
        factRunId);
  }

  private int insertFactRefreshTask(
      Long configId,
      String sourceInstance,
      String factType,
      String scope,
      boolean full,
      Long factRunId) {
    return jdbcTemplate.update(
        """
        insert into fact_build_tasks(
          run_id, scope, config_id, source_instance, fact_type, full_build, status, trigger_type,
          retry_count, max_retry_count, run_after, created_at, updated_at
        )
        select ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, current_timestamp, current_timestamp
        where not exists (
          select 1
            from fact_build_tasks
           where run_id = ?
             and fact_type = ?
             and trigger_type = ?
             and status in (?, ?)
        )
        """,
        String.valueOf(factRunId),
        scope,
        configId,
        sourceInstance,
        factType,
        full,
        STATUS_PENDING,
        TRIGGER_MIRROR_SYNC,
        DEFAULT_MAX_RETRY_COUNT,
        String.valueOf(factRunId),
        factType,
        TRIGGER_MIRROR_SYNC,
        STATUS_PENDING,
        STATUS_RUNNING);
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
