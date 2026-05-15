package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
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
  private final String lockOwner = UUID.randomUUID().toString();

  public FactBuildTaskService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
  }

  public FactBuildResponse runGuarded(
      String scope, boolean full, Supplier<FactBuildResponse> action) {
    String safeScope = normalizeScope(scope);
    try (Connection connection = dataSource.getConnection()) {
      if (!tryAcquireLock(connection)) {
        String message = "已有事实构建任务正在执行，请稍后再试";
        recordSkipped(safeScope, full, message);
        return new FactBuildResponse(safeScope, full, 0, message);
      }
      Long taskId = startTask(safeScope, full);
      try {
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

  public int enqueueMirrorRefreshTasks(GitlabSyncConfig config, boolean full) {
    return enqueueMirrorRefreshTasks(config, full, null);
  }

  public int enqueueMirrorRefreshTasks(GitlabSyncConfig config, boolean full, Long syncRunId) {
    if (config == null || config.getId() == null) {
      return 0;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return enqueueFactRefreshTask(config.getId(), sourceInstance, "ISSUE", full, syncRunId)
        + enqueueFactRefreshTask(config.getId(), sourceInstance, "MERGE_REQUEST", full, syncRunId)
        + enqueueFactRefreshTask(config.getId(), sourceInstance, "INTEGRATION_TEST", full, syncRunId);
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

  public QueuedFactBuildTask claimNextQueuedTaskForRun(Long syncRunId, String owner, int leaseSeconds) {
    if (syncRunId == null) {
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
        String.valueOf(syncRunId));
    return tasks.isEmpty() ? null : tasks.getFirst();
  }

  public void finishQueuedTask(Long taskId, String status, int affectedRows, String message, String errorMessage) {
    finishTask(taskId, status, affectedRows, message, errorMessage);
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

  private Long startTask(String scope, boolean full) {
    return jdbcTemplate.queryForObject(
        """
        insert into fact_build_tasks(
          run_id, scope, full_build, status, trigger_type, lock_owner, started_at, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        UUID.randomUUID().toString(),
        scope,
        full,
        STATUS_RUNNING,
        TRIGGER_MANUAL,
        lockOwner);
  }

  private int enqueueFactRefreshTask(Long configId, String sourceInstance, String factType, boolean full, Long syncRunId) {
    String scope = factScope(factType, sourceInstance);
    String runId = syncRunId == null ? UUID.randomUUID().toString() : String.valueOf(syncRunId);
    if (syncRunId != null) {
      return enqueueFactRefreshTaskForRun(configId, sourceInstance, factType, scope, full, runId);
    }
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
           where config_id = ?
             and source_instance = ?
             and fact_type = ?
             and full_build = ?
             and trigger_type = ?
             and status in (?, ?)
        )
        """,
        runId,
        scope,
        configId,
        sourceInstance,
        factType,
        full,
        STATUS_PENDING,
        TRIGGER_MIRROR_SYNC,
        DEFAULT_MAX_RETRY_COUNT,
        configId,
        sourceInstance,
        factType,
        full,
        TRIGGER_MIRROR_SYNC,
        STATUS_PENDING,
        STATUS_RUNNING);
  }

  private int enqueueFactRefreshTaskForRun(
      Long configId,
      String sourceInstance,
      String factType,
      String scope,
      boolean full,
      String runId) {
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
        runId,
        scope,
        configId,
        sourceInstance,
        factType,
        full,
        STATUS_PENDING,
        TRIGGER_MIRROR_SYNC,
        DEFAULT_MAX_RETRY_COUNT,
        runId,
        factType,
        TRIGGER_MIRROR_SYNC,
        STATUS_PENDING,
        STATUS_RUNNING);
  }

  private void recordSkipped(String scope, boolean full, String message) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(
          run_id, scope, full_build, status, trigger_type, lock_owner, affected_rows, message,
          started_at, finished_at, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, 0, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp)
        """,
        UUID.randomUUID().toString(),
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
        rs.getLong("config_id"),
        rs.getString("source_instance"),
        rs.getString("fact_type"),
        rs.getString("scope"),
        rs.getBoolean("full_build"),
        rs.getInt("retry_count"),
        rs.getInt("max_retry_count"),
        toLocalDateTime(rs.getTimestamp("lease_until")));
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
