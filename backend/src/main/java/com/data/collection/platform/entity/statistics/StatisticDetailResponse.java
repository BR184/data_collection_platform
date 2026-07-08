package com.data.collection.platform.entity.statistics;

import java.util.List;
import java.util.Map;

public record StatisticDetailResponse(
    String title,
    String description,
    List<StatisticDetailColumn> columns,
    List<Map<String, Object>> records,
    long total,
    int page,
    int size,
    String sortField,
    String sortOrder,
    Map<String, List<String>> quickFilterOptions) {
  public StatisticDetailResponse(
      String title,
      String description,
      List<StatisticDetailColumn> columns,
      List<Map<String, Object>> records,
      long total,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    this(title, description, columns, records, total, page, size, sortField, sortOrder, Map.of());
  }
}
