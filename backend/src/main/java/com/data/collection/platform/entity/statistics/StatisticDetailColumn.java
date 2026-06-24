package com.data.collection.platform.entity.statistics;

public record StatisticDetailColumn(
    String key,
    String label,
    Integer width,
    Integer minWidth,
    boolean sortable,
    String type,
    boolean expandOnly) {

  public StatisticDetailColumn(
      String key,
      String label,
      Integer width,
      Integer minWidth,
      boolean sortable,
      String type) {
    this(key, label, width, minWidth, sortable, type, false);
  }

  public StatisticDetailColumn(String key, String label, Integer width, Integer minWidth, boolean sortable) {
    this(key, label, width, minWidth, sortable, null, false);
  }

  public static StatisticDetailColumn expandOnly(
      String key,
      String label,
      Integer width,
      Integer minWidth,
      boolean sortable) {
    return new StatisticDetailColumn(key, label, width, minWidth, sortable, null, true);
  }

  public static StatisticDetailColumn expandOnly(
      String key,
      String label,
      Integer width,
      Integer minWidth,
      boolean sortable,
      String type) {
    return new StatisticDetailColumn(key, label, width, minWidth, sortable, type, true);
  }
}
