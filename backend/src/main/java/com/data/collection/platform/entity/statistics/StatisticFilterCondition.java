package com.data.collection.platform.entity.statistics;

import java.util.List;

public record StatisticFilterCondition(
    String fieldKey,
    String operator,
    String value,
    String secondaryValue,
    String valueType,
    Long labelGroupId,
    String labelGroupName,
    List<String> values) {

  public StatisticFilterCondition(
      String fieldKey,
      String operator,
      String value,
      String secondaryValue) {
    this(fieldKey, operator, value, secondaryValue, "LITERAL", null, null, List.of());
  }

  public StatisticFilterCondition(
      String fieldKey,
      String operator,
      String value,
      String secondaryValue,
      String valueType,
      Long labelGroupId,
      String labelGroupName) {
    this(fieldKey, operator, value, secondaryValue, valueType, labelGroupId, labelGroupName, List.of());
  }

  public boolean usesLabelGroup() {
    return "LABEL_GROUP".equalsIgnoreCase(valueType);
  }
}
