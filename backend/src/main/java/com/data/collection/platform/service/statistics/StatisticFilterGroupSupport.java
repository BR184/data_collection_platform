package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterField;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class StatisticFilterGroupSupport {
  static final String FILTER_GROUP_PARAM = "filterGroup";
  static final String DETAIL_KEYWORD_PARAM = "detailKeyword";
  static final String DETAIL_FILTER_PARAM_PREFIX = "detailFilter.";

  private StatisticFilterGroupSupport() {
  }

  static StatisticFilterGroup parseFilterGroup(
      JsonUtils jsonUtils,
      Map<String, String> filters,
      StatisticBoardDefinition definition) {
    if (filters == null || filters.isEmpty()) {
      return emptyFilterGroup();
    }
    String filterGroupJson = trimToNull(filters.get(FILTER_GROUP_PARAM));
    if (filterGroupJson != null) {
      StatisticFilterGroup parsed =
          jsonUtils.fromJson(filterGroupJson, new TypeReference<>() {});
      return validateFilterGroup(parsed, definition);
    }
    return validateFilterGroup(buildLegacyFilterGroup(filters, definition), definition);
  }

  static Map<String, String> withoutReservedFilters(Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return Map.of();
    }
    return filters.entrySet().stream()
        .filter(entry -> !isReservedFilterKey(entry.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static boolean isReservedFilterKey(String key) {
    return FILTER_GROUP_PARAM.equals(key)
        || DETAIL_KEYWORD_PARAM.equals(key)
        || (key != null && key.startsWith(DETAIL_FILTER_PARAM_PREFIX));
  }

  static StatisticFilterGroup emptyFilterGroup() {
    return new StatisticFilterGroup("AND", java.util.List.of());
  }

  static java.util.Set<String> filterableFieldKeys(StatisticBoardDefinition definition) {
    return new java.util.HashSet<>(toFieldMap(definition).keySet());
  }

  static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static StatisticFilterGroup buildLegacyFilterGroup(
      Map<String, String> filters,
      StatisticBoardDefinition definition) {
    Map<String, String> safeFilters = withoutReservedFilters(filters);
    if (safeFilters.isEmpty()) {
      return emptyFilterGroup();
    }
    Map<String, StatisticFilterField> fieldMap = toFieldMap(definition);
    java.util.List<StatisticFilterCondition> conditions =
        safeFilters.entrySet().stream()
            .filter(entry -> fieldMap.containsKey(entry.getKey()))
            .map(entry -> toLegacyCondition(fieldMap.get(entry.getKey()), entry.getValue()))
            .filter(Objects::nonNull)
            .toList();
    if (conditions.isEmpty()) {
      return emptyFilterGroup();
    }
    return new StatisticFilterGroup("AND", conditions);
  }

  private static StatisticFilterCondition toLegacyCondition(
      StatisticFilterField field,
      String value) {
    String safeValue = trimToNull(value);
    if (safeValue == null) {
      return null;
    }
    return new StatisticFilterCondition(field.key(), "eq", safeValue, null);
  }

  private static StatisticFilterGroup validateFilterGroup(
      StatisticFilterGroup input,
      StatisticBoardDefinition definition) {
    if (input == null || input.conditions() == null || input.conditions().isEmpty()) {
      return emptyFilterGroup();
    }
    String logic = "OR".equalsIgnoreCase(input.logic()) ? "OR" : "AND";
    Map<String, StatisticFilterField> fieldMap = toFieldMap(definition);
    java.util.List<StatisticFilterCondition> normalized = new java.util.ArrayList<>();
    for (StatisticFilterCondition condition : input.conditions()) {
      if (condition == null) {
        continue;
      }
      StatisticFilterField field = fieldMap.get(trimToNull(condition.fieldKey()));
      if (field == null) {
        throw new IllegalArgumentException(
            "Unsupported filter field: " + condition.fieldKey());
      }
      String operator = trimToNull(condition.operator());
      if (operator == null) {
        throw new IllegalArgumentException(
            "Unsupported operator for field " + field.key() + ": " + condition.operator());
      }
      String value = trimToNull(condition.value());
      String secondaryValue = trimToNull(condition.secondaryValue());
      if (condition.usesLabelGroup()) {
        if (!isLabelGroupOperator(operator)) {
          throw new IllegalArgumentException(
              "Unsupported label group operator for field " + field.key() + ": " + condition.operator());
        }
        if (condition.labelGroupId() == null) {
          continue;
        }
        normalized.add(
            new StatisticFilterCondition(
                field.key(),
                normalizeLabelGroupOperator(operator),
                null,
                null,
                "LABEL_GROUP",
                condition.labelGroupId(),
                trimToNull(condition.labelGroupName()),
                condition.values() == null ? java.util.List.of() : condition.values()));
        continue;
      }
      if (!field.operators().contains(operator)) {
        throw new IllegalArgumentException(
            "Unsupported operator for field " + field.key() + ": " + condition.operator());
      }
      if (requiresPrimaryValue(operator) && value == null) {
        continue;
      }
      if ("between".equals(operator) && secondaryValue == null) {
        continue;
      }
      normalized.add(
          new StatisticFilterCondition(field.key(), operator, value, secondaryValue));
    }
    if (normalized.isEmpty()) {
      return emptyFilterGroup();
    }
    return new StatisticFilterGroup(logic, normalized);
  }

  private static boolean requiresPrimaryValue(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }

  private static boolean isLabelGroupSetOperator(String operator) {
    return "eq".equals(operator)
        || "ne".equals(operator)
        || "intersects".equals(operator)
        || "notIntersects".equals(operator)
        || "containsAll".equals(operator)
        || "notContainsAll".equals(operator)
        || "partialContainsAny".equals(operator);
  }

  private static boolean isLabelGroupOperator(String operator) {
    return isLabelGroupSetOperator(operator);
  }

  private static String normalizeLabelGroupOperator(String operator) {
    if ("eq".equals(operator)) {
      return "intersects";
    }
    if ("ne".equals(operator)) {
      return "notIntersects";
    }
    return operator;
  }

  private static Map<String, StatisticFilterField> toFieldMap(
      StatisticBoardDefinition definition) {
    Map<String, StatisticFilterField> fieldMap = new HashMap<>();
    for (StatisticFilterField field : definition.filters()) {
      fieldMap.put(field.key(), field);
    }
    return fieldMap;
  }
}
