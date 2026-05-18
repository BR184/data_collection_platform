package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

@Service
public class SourceConnectionTester {
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorProperties properties;
  private final ExecutorService executorService;
  private final ConcurrentMap<String, FailureCacheEntry> failureCache = new ConcurrentHashMap<>();

  public SourceConnectionTester(GitlabExternalDbService externalDbService, GitlabMirrorProperties properties) {
    this(externalDbService, properties, Executors.newCachedThreadPool(new SourceConnectionThreadFactory()));
  }

  SourceConnectionTester(
      GitlabExternalDbService externalDbService,
      GitlabMirrorProperties properties,
      ExecutorService executorService) {
    this.externalDbService = externalDbService;
    this.properties = properties;
    this.executorService = executorService;
  }

  public void testConnection(GitlabSyncConfig config) {
    String signature = sourceSignature(config);
    rejectRecentFailure(signature);
    try {
      runWithInteractiveTimeout(() -> {
        externalDbService.testConnection(config);
        return null;
      });
      failureCache.remove(signature);
    } catch (RuntimeException error) {
      rememberFailure(signature, error);
      throw error;
    }
  }

  private <T> T runWithInteractiveTimeout(Callable<T> callable) {
    int timeoutSeconds = Math.max(1, properties.getInteractiveConnectionTimeoutSeconds());
    Future<T> future = executorService.submit(callable);
    try {
      return future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new BizException("GitLab source connection timed out after " + timeoutSeconds + " seconds");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new BizException("GitLab source connection test was interrupted");
    } catch (Exception error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new BizException("GitLab source connection failed: " + cause.getMessage());
    }
  }

  private void rejectRecentFailure(String signature) {
    FailureCacheEntry entry = failureCache.get(signature);
    if (entry == null) {
      return;
    }
    int ttlSeconds = Math.max(0, properties.getSourceFailureCacheSeconds());
    if (ttlSeconds <= 0 || Duration.between(entry.failedAt(), Instant.now()).getSeconds() >= ttlSeconds) {
      failureCache.remove(signature, entry);
      return;
    }
    throw new BizException("GitLab source is temporarily unavailable: " + entry.message());
  }

  private void rememberFailure(String signature, RuntimeException error) {
    int ttlSeconds = Math.max(0, properties.getSourceFailureCacheSeconds());
    if (ttlSeconds <= 0) {
      return;
    }
    failureCache.put(signature, new FailureCacheEntry(Instant.now(), error.getMessage()));
  }

  private String sourceSignature(GitlabSyncConfig config) {
    if (config == null) {
      return "missing";
    }
    SourceMode mode = config.getSourceMode() == null ? SourceMode.DOCKER : config.getSourceMode();
    if (mode == SourceMode.DIRECT) {
      return String.join(
          "|",
          "direct",
          Objects.toString(config.getDbHost(), ""),
          Objects.toString(config.getDbPort(), ""),
          Objects.toString(config.getDbName(), ""),
          Objects.toString(config.getDbUsername(), ""),
          Integer.toHexString(Objects.toString(config.getDbPassword(), "").hashCode()));
    }
    return String.join(
        "|",
        "docker",
        Objects.toString(config.getDockerContainerName(), ""),
        Objects.toString(config.getDbName(), ""));
  }

  private record FailureCacheEntry(Instant failedAt, String message) {}

  private static class SourceConnectionThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "gitlab-source-connection-test");
      thread.setDaemon(true);
      return thread;
    }
  }
}
