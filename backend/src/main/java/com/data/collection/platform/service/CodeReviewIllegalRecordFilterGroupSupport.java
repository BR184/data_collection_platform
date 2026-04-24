package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CodeReviewIllegalRecordFilterGroupSupport {
  private static final Map<String, List<String>> FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("repositoryName", List.of("eq", "ne")),
          Map.entry("mergedAt", List.of("year", "month", "day", "at", "before", "after", "between")),
          Map.entry("illegalType", List.of("eq", "ne")),
          Map.entry("keyword", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("requestType", List.of("eq", "ne")),
          Map.entry("mergeRequestIid", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("owner", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("targetBranch", List.of("eq", "ne")),
          Map.entry("mergedBy", List.of("eq", "ne")),
          Map.entry("moduleName", List.of("eq", "ne")),
          Map.entry("projectName", List.of("eq", "ne")),
          Map.entry("commentRate", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("defectCount", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("addedLines", List.of("eq", "gt", "gte", "lt", "lte", "between")));

  private CodeReviewIllegalRecordFilterGroupSupport() {}

  static StatisticFilterGroup parse(ObjectMapper objectMapper, String filterGroupJson) {
    String normalized = TextQuerySupport.trimToNull(filterGroupJson);
    if (normalized == null) {
      return empty();
    }
    try {
      StatisticFilterGroup parsed = objectMapper.readValue(normalized, new TypeReference<>() {});
      if (parsed == null || parsed.conditions() == null || parsed.conditions().isEmpty()) {
        return empty();
      }
      List<StatisticFilterCondition> conditions =
          parsed.conditions().stream()
              .map(CodeReviewIllegalRecordFilterGroupSupport::normalizeCondition)
              .filter(Objects::nonNull)
              .toList();
      return new StatisticFilterGroup("OR".equalsIgnoreCase(parsed.logic()) ? "OR" : "AND", conditions);
    } catch (Exception ignored) {
      return empty();
    }
  }

  static boolean matches(CodeReviewIllegalRecordView row, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(row, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private static StatisticFilterGroup empty() {
    return new StatisticFilterGroup("AND", List.of());
  }

  private static StatisticFilterCondition normalizeCondition(StatisticFilterCondition condition) {
    if (condition == null) {
      return null;
    }
    String fieldKey = TextQuerySupport.trimToNull(condition.fieldKey());
    String operator = TextQuerySupport.trimToNull(condition.operator());
    if (fieldKey == null
        || operator == null
        || !FILTER_OPERATORS.getOrDefault(fieldKey, List.of()).contains(operator)) {
      return null;
    }
    String value = TextQuerySupport.trimToNull(condition.value());
    String secondaryValue = TextQuerySupport.trimToNull(condition.secondaryValue());
    if (requiresPrimaryValue(operator) && value == null) {
      return null;
    }
    if ("between".equals(operator) && secondaryValue == null) {
      return null;
    }
    return new StatisticFilterCondition(fieldKey, operator, value, secondaryValue);
  }

  private static boolean matchesCondition(
      CodeReviewIllegalRecordView row, StatisticFilterCondition condition) {
    List<String> values = valuesForField(row, condition.fieldKey());
    if ("mergedAt".equals(condition.fieldKey()) && "between".equals(condition.operator())) {
      return values.stream()
          .anyMatch(
              value ->
                  canCompare(value, condition.value())
                      && canCompare(value, condition.secondaryValue())
                      && compareText(value, condition.value()) >= 0
                      && compareText(value, condition.secondaryValue()) <= 0);
    }
    return switch (condition.operator()) {
      case "isEmpty" -> values.stream().allMatch(value -> TextQuerySupport.trimToNull(value) == null);
      case "isNotEmpty" -> values.stream().anyMatch(value -> TextQuerySupport.trimToNull(value) != null);
      case "ne" -> values.stream().noneMatch(value -> equalsIgnoreCase(value, condition.value()));
      case "contains" -> values.stream().anyMatch(value -> TextQuerySupport.containsAbstractSearch(value, condition.value()));
      case "notContains" -> values.stream().noneMatch(value -> TextQuerySupport.containsAbstractSearch(value, condition.value()));
      case "gt", "gte", "lt", "lte", "between" -> values.stream().anyMatch(value -> matchesNumber(value, condition));
      case "year" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 4), condition.value()));
      case "month" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 7), condition.value()));
      case "day" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 10), condition.value()));
      case "before" -> values.stream().anyMatch(value -> canCompare(value, condition.value()) && compareText(value, condition.value()) < 0);
      case "after" -> values.stream().anyMatch(value -> canCompare(value, condition.value()) && compareText(value, condition.value()) > 0);
      case "at" -> values.stream().anyMatch(value -> Objects.equals(normalizeDateTime(value), normalizeDateTime(condition.value())));
      default -> values.stream().anyMatch(value -> equalsIgnoreCase(value, condition.value()));
    };
  }

  private static List<String> valuesForField(CodeReviewIllegalRecordView row, String fieldKey) {
    return switch (fieldKey) {
      case "repositoryName" -> List.of(Objects.toString(row.repositoryName(), ""));
      case "mergedAt" -> List.of(Objects.toString(row.mergedAt(), ""));
      case "illegalType" -> row.illegalTypes();
      case "keyword" -> List.of(
          Objects.toString(row.mergeRequestContent(), ""),
          Objects.toString(row.owner(), ""),
          Objects.toString(row.projectName(), ""),
          Objects.toString(row.repositoryName(), ""),
          Objects.toString(row.moduleName(), ""),
          Objects.toString(row.targetBranch(), ""),
          Objects.toString(row.mergedBy(), ""));
      case "requestType" -> List.of(Objects.toString(row.requestType(), ""));
      case "mergeRequestIid" -> List.of(Objects.toString(row.mergeRequestIid(), ""));
      case "owner" -> List.of(Objects.toString(row.owner(), ""));
      case "targetBranch" -> List.of(Objects.toString(row.targetBranch(), ""));
      case "mergedBy" -> List.of(Objects.toString(row.mergedBy(), ""));
      case "moduleName" -> List.of(Objects.toString(row.moduleName(), ""));
      case "projectName" -> List.of(Objects.toString(row.projectName(), ""));
      case "commentRate" -> List.of(Objects.toString(row.commentRate(), ""));
      case "defectCount" -> List.of(Objects.toString(row.defectCount(), ""));
      case "addedLines" -> List.of(Objects.toString(row.addedLines(), ""));
      default -> List.of();
    };
  }

  private static boolean matchesNumber(String value, StatisticFilterCondition condition) {
    Double left = parseDouble(value);
    Double right = parseDouble(condition.value());
    Double secondary = parseDouble(condition.secondaryValue());
    if (left == null || right == null) {
      return false;
    }
    return switch (condition.operator()) {
      case "gt" -> left > right;
      case "gte" -> left >= right;
      case "lt" -> left < right;
      case "lte" -> left <= right;
      case "between" -> secondary != null && left >= Math.min(right, secondary) && left <= Math.max(right, secondary);
      default -> Double.compare(left, right) == 0;
    };
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private static int compareText(String left, String right) {
    String safeLeft = normalizeDateTime(left);
    String safeRight = normalizeDateTime(right);
    if (safeLeft == null || safeRight == null) {
      return 0;
    }
    return safeLeft.compareTo(safeRight);
  }

  private static boolean canCompare(String left, String right) {
    return normalizeDateTime(left) != null && normalizeDateTime(right) != null;
  }

  private static String firstDatePart(String value, int length) {
    String normalized = normalizeDateTime(value);
    return normalized == null ? null : normalized.substring(0, Math.min(normalized.length(), length));
  }

  private static String normalizeDateTime(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : normalized.replace('T', ' ');
  }

  private static Double parseDouble(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static boolean requiresPrimaryValue(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }
}
