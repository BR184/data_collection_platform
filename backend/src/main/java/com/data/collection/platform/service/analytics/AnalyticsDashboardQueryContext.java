package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable query scope used by aggregation, drill-down and export operations.
 * Business dimensions remain named parameters so new providers do not require controller changes.
 */
public record AnalyticsDashboardQueryContext(
    Map<String, String> parameters,
    int page,
    int size,
    String sortField,
    String sortOrder,
    ReadMode readMode) {
  public static final int DEFAULT_PAGE = 1;
  public static final int DEFAULT_SIZE = 20;

  public AnalyticsDashboardQueryContext {
    parameters = immutableSortedParameters(parameters);
    if (page < 1) {
      throw new BizException("页码必须大于 0");
    }
    if (size < 1 || size > 200) {
      throw new BizException("每页数量必须在 1 到 200 之间");
    }
    sortField = normalize(sortField);
    sortOrder = normalize(sortOrder);
    readMode = readMode == null ? ReadMode.FORMAL : readMode;
  }

  public static AnalyticsDashboardQueryContext empty() {
    return of(Map.of());
  }

  public static AnalyticsDashboardQueryContext of(Map<String, String> parameters) {
    return new AnalyticsDashboardQueryContext(
        parameters, DEFAULT_PAGE, DEFAULT_SIZE, null, null, ReadMode.FORMAL);
  }

  public Optional<String> parameter(String key) {
    return Optional.ofNullable(parameters.get(key));
  }

  public AnalyticsDashboardQueryContext withReadMode(ReadMode mode) {
    return new AnalyticsDashboardQueryContext(parameters, page, size, sortField, sortOrder, mode);
  }

  /** Cache scope deliberately excludes pagination and sorting but always includes the read source. */
  public String scopeCacheKey(String dashboardKey) {
    StringBuilder key = new StringBuilder();
    appendPart(key, dashboardKey);
    appendPart(key, readMode.key());
    parameters.forEach((name, value) -> {
      appendPart(key, name);
      appendPart(key, value);
    });
    return key.toString();
  }

  private static Map<String, String> immutableSortedParameters(Map<String, String> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    TreeMap<String, String> sorted = new TreeMap<>();
    source.forEach((key, value) -> {
      String normalizedKey = normalize(key);
      String normalizedValue = normalize(value);
      if (normalizedKey != null && normalizedValue != null) {
        sorted.put(normalizedKey, normalizedValue);
      }
    });
    return Collections.unmodifiableMap(sorted);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static void appendPart(StringBuilder target, String value) {
    String normalized = value == null ? "" : value;
    target.append(normalized.length()).append(':').append(normalized).append('|');
  }

  public enum ReadMode {
    FORMAL("formal"),
    MATCH_MODE("match-mode");

    private final String key;

    ReadMode(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

  }
}
