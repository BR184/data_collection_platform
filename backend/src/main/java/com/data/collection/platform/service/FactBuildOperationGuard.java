package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class FactBuildOperationGuard {
  private final Set<String> runningScopes = new HashSet<>();

  public <T> T run(String scope, Supplier<T> operation) {
    String normalizedScope = normalizeScope(scope);
    synchronized (runningScopes) {
      if (conflicts(normalizedScope)) {
        throw new BizException("当前已有事实重建任务执行中，请等待当前任务完成后再重试");
      }
      runningScopes.add(normalizedScope);
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
    return "all".equals(scope) || runningScopes.contains("all") || runningScopes.contains(scope);
  }

  private String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    return scope.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
