package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.HashMap;
import java.util.List;
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
  private static final List<String> FACT_DOMAINS = List.of("all", "issue", "merge-request", "integration-test");

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
    GuardScope requested = GuardScope.parse(scope);
    return runningScopes.keySet().stream()
        .map(GuardScope::parse)
        .anyMatch(runningScope -> runningScope.conflictsWith(requested));
  }

  private String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    return scope.trim().toLowerCase(Locale.ROOT).replace('_', '-');
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

  private record GuardScope(String domain, String owner) {
    static GuardScope parse(String scope) {
      String[] parts = scope.split(":");
      if (parts.length == 1) {
        return new GuardScope(parts[0], "");
      }
      String first = parts[0];
      String last = parts[parts.length - 1];
      if (FACT_DOMAINS.contains(first)) {
        return new GuardScope(first, join(parts, 1, parts.length));
      }
      if (FACT_DOMAINS.contains(last)) {
        return new GuardScope(last, join(parts, 0, parts.length - 1));
      }
      return new GuardScope(first, join(parts, 1, parts.length));
    }

    boolean conflictsWith(GuardScope requested) {
      if (isGlobalAll() || requested.isGlobalAll()) {
        return true;
      }
      if (!owner.equals(requested.owner)) {
        return false;
      }
      return isAll() || requested.isAll() || domain.equals(requested.domain);
    }

    private boolean isAll() {
      return "all".equals(domain);
    }

    private boolean isGlobalAll() {
      return isAll() && owner.isBlank();
    }

    private static String join(String[] parts, int startInclusive, int endExclusive) {
      return String.join(":", java.util.Arrays.copyOfRange(parts, startInclusive, endExclusive));
    }
  }
}
