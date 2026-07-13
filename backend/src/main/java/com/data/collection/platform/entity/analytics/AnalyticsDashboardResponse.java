package com.data.collection.platform.entity.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Neutral dashboard contract shared by every chart-oriented analytics provider. */
public record AnalyticsDashboardResponse(
    String dashboardKey,
    String title,
    String subtitle,
    List<Metric> metrics,
    List<Chart> charts) {

  public AnalyticsDashboardResponse {
    metrics = metrics == null ? List.of() : List.copyOf(metrics);
    charts = charts == null ? List.of() : List.copyOf(charts);
  }

  public record Metric(
      String key,
      String title,
      BigDecimal value,
      String displayValue,
      String unit,
      String ruleKey,
      DetailAction detail,
      ExportAction export) {}

  public record Chart(
      String key,
      String title,
      String subtitle,
      Map<String, Object> option,
      String ruleKey,
      DetailAction detail,
      ExportAction export) {
    public Chart {
      option = option == null ? Map.of() : Map.copyOf(option);
    }
  }

  public record DetailAction(String viewKey, Map<String, String> params) {
    public DetailAction {
      params = params == null ? Map.of() : Map.copyOf(params);
    }
  }

  public record ExportAction(String exportKey, String label) {}
}
