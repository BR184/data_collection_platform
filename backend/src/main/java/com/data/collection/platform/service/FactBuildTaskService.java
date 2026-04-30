package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
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
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_SKIPPED = "SKIPPED";
  private static final String TRIGGER_MANUAL = "MANUAL";

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
    return switch (normalized) {
      case "merge_request" -> "merge-request";
      case "issue", "merge-request", "all" -> normalized;
      default -> "all";
    };
  }
}
