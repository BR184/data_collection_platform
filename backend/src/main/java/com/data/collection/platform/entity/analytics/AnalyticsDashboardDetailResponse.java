package com.data.collection.platform.entity.analytics;

import java.util.List;
import java.util.Map;

public record AnalyticsDashboardDetailResponse(
    String dashboardKey,
    String viewKey,
    String title,
    String description,
    List<Column> columns,
    List<Map<String, Object>> records,
    long total,
    int page,
    int size,
    List<AnalyticsDashboardResponse.ExportAction> exports) {

  public AnalyticsDashboardDetailResponse {
    columns = columns == null ? List.of() : List.copyOf(columns);
    records = records == null ? List.of() : List.copyOf(records);
    exports = exports == null ? List.of() : List.copyOf(exports);
  }

  public record Column(String key, String label, String format, Integer width) {}
}
