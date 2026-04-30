package com.data.collection.platform.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SqlQueryMonitor {
  private final long slowThresholdMs;

  public SqlQueryMonitor(
      @Value("${platform.query.slow-threshold-ms:1000}") long slowThresholdMs) {
    this.slowThresholdMs = slowThresholdMs;
  }

  long start() {
    return System.nanoTime();
  }

  void logIfSlow(String operation, String sql, List<Object> args, long startedAtNanos) {
    long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
    if (elapsedMs < slowThresholdMs) {
      return;
    }
    log.warn(
        "Slow SQL detected, operation={}, elapsedMs={}, argCount={}, sql={}",
        operation,
        elapsedMs,
        args == null ? 0 : args.size(),
        compact(sql));
  }

  private String compact(String sql) {
    if (sql == null) {
      return "";
    }
    String compact = sql.replaceAll("\\s+", " ").trim();
    return compact.length() <= 500 ? compact : compact.substring(0, 500) + "...";
  }
}
