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
    List<AnalyticsDashboardResponse.ExportAction> exports,
    List<Filter> filters,
    Chart chart) {

  public AnalyticsDashboardDetailResponse(
      String dashboardKey,
      String viewKey,
      String title,
      String description,
      List<Column> columns,
      List<Map<String, Object>> records,
      long total,
      int page,
      int size,
      List<AnalyticsDashboardResponse.ExportAction> exports,
      List<Filter> filters) {
    this(
        dashboardKey,
        viewKey,
        title,
        description,
        columns,
        records,
        total,
        page,
        size,
        exports,
        filters,
        null);
  }

  public AnalyticsDashboardDetailResponse(
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
    this(
        dashboardKey,
        viewKey,
        title,
        description,
        columns,
        records,
        total,
        page,
        size,
        exports,
        List.of(),
        null);
  }

  public AnalyticsDashboardDetailResponse {
    columns = columns == null ? List.of() : List.copyOf(columns);
    records = records == null ? List.of() : List.copyOf(records);
    exports = exports == null ? List.of() : List.copyOf(exports);
    filters = filters == null ? List.of() : List.copyOf(filters);
  }

  public record Column(String key, String label, String format, Integer width) {}

  public record Filter(
      String key,
      String label,
      String value,
      List<Option> options) {
    public Filter {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  public record Option(String label, String value) {}

  public record Chart(
      String key,
      String title,
      String subtitle,
      Map<String, Object> option,
      Integer height) {
    public Chart {
      option = option == null ? Map.of() : Map.copyOf(option);
    }
  }
}
