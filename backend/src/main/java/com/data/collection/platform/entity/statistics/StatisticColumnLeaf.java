package com.data.collection.platform.entity.statistics;

public record StatisticColumnLeaf(
    String key,
    String label,
    boolean drilldown,
    String metricType,
    String headerTooltip) {
  public StatisticColumnLeaf(String key, String label, boolean drilldown, String metricType) {
    this(key, label, drilldown, metricType, null);
  }
}
