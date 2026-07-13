package com.data.collection.platform.entity.analytics;

import java.util.List;

public record AnalyticsDashboardRulesResponse(String dashboardKey, List<Rule> rules) {
  public AnalyticsDashboardRulesResponse {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  public record Rule(
      String key,
      String title,
      String formula,
      String scope,
      String target,
      String description) {}
}
