package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FactBuildOperationGuard {
  private final Map<String, Long> runningScopes = new HashMap<>();
  private final LongSupplier currentTimeMillis;

  @Value("${platform.fact-build.guard-timeout-ms:7200000}")
  private long guardTimeoutMs = 7_200_000L;

  public FactBuildOperationGuard() {
    this(System::currentTimeMillis);
  }

  FactBuildOperationGuard(long guardTimeoutMs, LongSupplier currentTimeMillis) {
    this(currentTimeMillis);
    this.guardTimeoutMs = guardTimeoutMs;
  }

  private FactBuildOperationGuard(LongSupplier currentTimeMillis) {
    this.currentTimeMillis = currentTimeMillis;
  }

  public <T> T run(String scope, Supplier<T> operation) {
    String normalizedScope = normalizeScope(scope);
    synchronized (runningScopes) {
      removeExpiredScopes();
      if (conflicts(normalizedScope)) {
        throw new BizException("当前已有事实重建任务执行中，请等待当前任务完成后再重试");
      }
      runningScopes.put(normalizedScope, currentTimeMillis.getAsLong());
    }
    try {
      return operation.get();
    } finally {
      synchronized (runningScopes) {
        runningScopes.remove(normalizedScope);
      }
    }
  }

  private boolean conflicts(String scope) {
    if (runningScopes.isEmpty()) {
      return false;
    }
    String sourcePrefix = sourcePrefix(scope);
    if (isAllScope(scope)) {
      return runningScopes.keySet().stream().anyMatch(runningScope -> sourcePrefix(runningScope).equals(sourcePrefix));
    }
    String allScope = sourcePrefix.isBlank() ? "all" : sourcePrefix + ":all";
    return runningScopes.containsKey(allScope) || runningScopes.containsKey(scope);
  }

  private void removeExpiredScopes() {
    long timeoutMs = Math.max(1_000L, guardTimeoutMs);
    long now = currentTimeMillis.getAsLong();
    runningScopes.entrySet().removeIf(entry -> {
      boolean expired = now - entry.getValue() > timeoutMs;
      if (expired) {
        log.warn("Recovered expired fact build guard scope, scope={}, ageMs={}", entry.getKey(), now - entry.getValue());
      }
      return expired;
    });
  }

  private boolean isAllScope(String scope) {
    return "all".equals(scope) || scope.endsWith(":all");
  }

  private String sourcePrefix(String scope) {
    int separator = scope.indexOf(':');
    return separator < 0 ? "" : scope.substring(0, separator);
  }

  private String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    return scope.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
