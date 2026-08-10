package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.Set;

/** 保留 GitLab 来源访问失败的可重试语义和底层原因。 */
public final class GitlabSourceAccessException extends BizException {
  private static final Set<String> RETRYABLE_SQL_STATES =
      Set.of("55P03", "57014", "57P01", "57P02", "57P03");

  private final boolean retryable;

  private GitlabSourceAccessException(String message, Throwable cause, boolean retryable) {
    super(message);
    if (cause != null) {
      initCause(cause);
    }
    this.retryable = retryable;
  }

  /** 根据底层异常和外部进程错误文本建立保留语义的来源访问异常。 */
  public static GitlabSourceAccessException from(String message, Throwable cause) {
    return new GitlabSourceAccessException(
        message, cause, isTransientCause(cause) || isTransientMessage(message));
  }

  /** 建立调用方已确定语义的来源访问异常。 */
  public static GitlabSourceAccessException of(
      String message, Throwable cause, boolean retryable) {
    return new GitlabSourceAccessException(message, cause, retryable);
  }

  /** 返回该来源失败是否允许同步状态机退避后重试。 */
  public boolean isRetryable() {
    return retryable;
  }

  private static boolean isTransientCause(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof SQLTransientException
          || current instanceof SQLRecoverableException
          || current instanceof SQLTimeoutException
          || current instanceof SocketTimeoutException
          || current instanceof ConnectException
          || current instanceof SocketException) {
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

  private static boolean isRetryableSqlState(String sqlState) {
    if (sqlState == null || sqlState.length() < 2) {
      return false;
    }
    String stateClass = sqlState.substring(0, 2);
    return RETRYABLE_SQL_STATES.contains(sqlState)
        || stateClass.equals("08")
        || stateClass.equals("40")
        || stateClass.equals("53");
  }

  private static boolean isTransientMessage(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    if (message.contains("ERROR:")
        || message.contains("FATAL:")
        || lower.contains("syntax error")) {
      return false;
    }
    return lower.contains("timeout")
        || lower.contains("timed out")
        || lower.contains("connection reset")
        || lower.contains("connection refused")
        || lower.contains("could not connect")
        || lower.contains("connection has been closed")
        || lower.contains("closed connection")
        || lower.contains("broken pipe")
        || lower.contains("i/o error")
        || lower.contains("io exception")
        || lower.contains("network")
        || lower.contains("temporarily unavailable");
  }
}
