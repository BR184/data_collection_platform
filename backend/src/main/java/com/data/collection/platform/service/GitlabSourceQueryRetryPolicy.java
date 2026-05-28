package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class GitlabSourceQueryRetryPolicy {
  private final GitlabMirrorProperties properties;

  GitlabSourceQueryRetryPolicy(GitlabMirrorProperties properties) {
    this.properties = properties;
  }

  <T> T executeWithRetry(String operation, Supplier<T> supplier) {
    int attempts = Math.max(1, properties.getExternalQueryRetryAttempts());
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return supplier.get();
      } catch (RuntimeException e) {
        lastFailure = e;
        if (attempt >= attempts || !isRetryableExternalFailure(e)) {
          throw e;
        }
        long retryDelayMs = computeRetryDelayMs(attempt);
        log.warn(
            "Transient GitLab external query failure, operation={}, attempt={}/{}, retryDelayMs={}, message={}",
            operation,
            attempt,
            attempts,
            retryDelayMs,
            e.getMessage());
        sleepBeforeRetry(retryDelayMs);
      }
    }
    throw lastFailure;
  }

  long computeRetryDelayMs(int attempt) {
    long baseDelayMs = Math.max(0, properties.getExternalQueryRetryDelayMs());
    if (baseDelayMs <= 0) {
      return 0;
    }
    long maxDelayMs = Math.max(baseDelayMs, properties.getExternalQueryRetryMaxDelayMs());
    int exponent = Math.max(0, Math.min(10, attempt - 1));
    long exponentialDelay = baseDelayMs * (1L << exponent);
    long cappedDelay = Math.min(exponentialDelay, maxDelayMs);
    if (cappedDelay >= maxDelayMs) {
      return maxDelayMs;
    }
    long jitterBound = Math.max(1, cappedDelay / 2);
    long jitter = ThreadLocalRandom.current().nextLong(jitterBound + 1);
    return Math.min(maxDelayMs, cappedDelay + jitter);
  }

  boolean isRetryableExternalFailure(RuntimeException e) {
    String message = flattenMessage(e);
    if (message.isBlank()) {
      return false;
    }
    if (message.contains("ERROR:")
        || message.contains("FATAL:")
        || message.toLowerCase(Locale.ROOT).contains("syntax error")) {
      return false;
    }
    String lowerMessage = message.toLowerCase(Locale.ROOT);
    return lowerMessage.contains("timeout")
        || lowerMessage.contains("timed out")
        || lowerMessage.contains("connection reset")
        || lowerMessage.contains("connection refused")
        || lowerMessage.contains("could not connect")
        || lowerMessage.contains("connection has been closed")
        || lowerMessage.contains("closed connection")
        || lowerMessage.contains("broken pipe")
        || lowerMessage.contains("i/o error")
        || lowerMessage.contains("io exception")
        || lowerMessage.contains("network")
        || lowerMessage.contains("temporarily unavailable");
  }

  private void sleepBeforeRetry(long retryDelayMs) {
    if (retryDelayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(retryDelayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BizException("GitLab external query retry interrupted");
    }
  }

  private String flattenMessage(Throwable throwable) {
    StringBuilder message = new StringBuilder();
    Throwable current = throwable;
    while (current != null) {
      if (current.getMessage() != null) {
        if (!message.isEmpty()) {
          message.append(' ');
        }
        message.append(current.getMessage());
      }
      current = current.getCause();
    }
    return message.toString();
  }
}
