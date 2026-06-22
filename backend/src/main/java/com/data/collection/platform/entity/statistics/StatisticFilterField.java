package com.data.collection.platform.entity.statistics;

import java.util.List;

public record StatisticFilterField(
    String key,
    String label,
    String type,
    String placeholder,
    String defaultValue,
    Integer width,
    List<String> operators,
    List<StatisticFilterOption> options,
    Boolean labelGroupEnabled,
    String labelGroupValueType) {

  public StatisticFilterField(
      String key,
      String label,
      String type,
      String placeholder,
      String defaultValue,
      Integer width,
      List<String> operators,
      List<StatisticFilterOption> options) {
    this(key, label, type, placeholder, defaultValue, width, operators, options, false, null);
  }
}
