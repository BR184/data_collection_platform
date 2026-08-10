package com.data.collection.platform.service.sync;

import com.data.collection.platform.service.GitlabSourceAccessException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/** 将表任务异常分类为可退避重试或确定性失败。 */
@Component
public class SyncTableTaskFailurePolicy {
  private static final int BASE_DELAY_SECONDS = 5;
  private static final int MAX_DELAY_SECONDS = 300;
  private static final Set<String> RETRYABLE_SQL_STATES =
      Set.of("55P03", "57014", "57P01", "57P02", "57P03");

  /**
   * 根据异常语义和已消费重试次数给出确定决策。
   *
   * @param error 本次任务异常
   * @param retryCount 已完成的重试次数
   * @param maxRetryCount 允许的最大重试次数
   * @param now 受控决策时间
   */
  public Decision evaluate(
      Throwable error,
      int retryCount,
      int maxRetryCount,
      LocalDateTime now) {
    if (error == null
        || now == null
        || retryCount >= Math.max(0, maxRetryCount)
        || !isTransient(error)) {
      return Decision.terminal();
    }
    int exponent = Math.min(Math.max(0, retryCount), 10);
    long delaySeconds =
        Math.min(MAX_DELAY_SECONDS, BASE_DELAY_SECONDS * (1L << exponent));
    return new Decision(true, now.plusSeconds(delaySeconds));
  }

  private boolean isTransient(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof GitlabSourceAccessException sourceFailure
          && sourceFailure.isRetryable()) {
        return true;
      }
      if (current instanceof TransientDataAccessException
          || current instanceof RecoverableDataAccessException
          || current instanceof CannotGetJdbcConnectionException
          || current instanceof QueryTimeoutException
          || current instanceof SQLTransientException
          || current instanceof SQLRecoverableException
          || current instanceof SQLTimeoutException
          || current instanceof SocketTimeoutException) {
        return true;
      }
      if (current instanceof SQLException sqlException
          && isRetryableSqlState(sqlException.getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean isRetryableSqlState(String sqlState) {
    if (sqlState == null || sqlState.length() < 2) {
      return false;
    }
    String stateClass = sqlState.substring(0, 2);
    return RETRYABLE_SQL_STATES.contains(sqlState)
        || stateClass.equals("08")
        || stateClass.equals("40")
        || stateClass.equals("53");
  }

  /** 表任务失败后的状态转换决策。 */
  public record Decision(boolean retryable, LocalDateTime runAfter) {
    private static Decision terminal() {
      return new Decision(false, null);
    }
  }
}
