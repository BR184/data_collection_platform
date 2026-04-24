package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReviewDataRecordFilterGroupSupport {
  private static final Map<String, List<String>> FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("title", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("projectName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("moduleName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewOwner", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewType", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewExpert", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("problemStatus", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewScalePages", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("problemCount", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("problemDensity", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("reviewDate", List.of("day", "before", "after", "between")));

  private ReviewDataRecordFilterGroupSupport() {}

  static StatisticFilterGroup parse(JsonUtils jsonUtils, String filterGroupJson) {
    String normalized = TextQuerySupport.trimToNull(filterGroupJson);
    if (normalized == null) {
      return empty();
    }
    StatisticFilterGroup parsed = jsonUtils.fromJson(normalized, new TypeReference<>() {});
    if (parsed == null || parsed.conditions() == null || parsed.conditions().isEmpty()) {
      return empty();
    }
    List<StatisticFilterCondition> conditions =
        parsed.conditions().stream()
            .map(ReviewDataRecordFilterGroupSupport::normalizeCondition)
            .filter(Objects::nonNull)
            .toList();
    return new StatisticFilterGroup("OR".equalsIgnoreCase(parsed.logic()) ? "OR" : "AND", conditions);
  }

  static boolean needsField(StatisticFilterGroup filterGroup, String fieldKey) {
    return filterGroup != null
        && filterGroup.conditions() != null
        && filterGroup.conditions().stream().anyMatch(condition -> fieldKey.equals(condition.fieldKey()));
  }

  static boolean matches(
      ReviewDataRecordRowResponse row,
      StatisticFilterGroup filterGroup,
      Map<Long, List<String>> problemStatusesByRecordId) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(row, condition, problemStatusesByRecordId);
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
      ReviewDataRecordRowResponse row,
      StatisticFilterCondition condition,
      Map<Long, List<String>> problemStatusesByRecordId) {
    List<String> values = valuesForField(row, condition.fieldKey(), problemStatusesByRecordId);
    return switch (condition.operator()) {
      case "isEmpty" -> values.stream().allMatch(value -> TextQuerySupport.trimToNull(value) == null);
      case "isNotEmpty" -> values.stream().anyMatch(value -> TextQuerySupport.trimToNull(value) != null);
      case "ne" -> values.stream().noneMatch(value -> equalsIgnoreCase(value, condition.value()));
      case "contains" -> values.stream().anyMatch(value -> ReviewDataSearchSupport.matchesContains(value, condition.value()));
      case "notContains" -> values.stream().noneMatch(value -> ReviewDataSearchSupport.matchesContains(value, condition.value()));
      case "gt", "gte", "lt", "lte", "between" -> values.stream().anyMatch(value -> matchesNumber(value, condition));
      case "day" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value), condition.value()));
      case "before" -> values.stream().anyMatch(value -> compareText(firstDatePart(value), firstDatePart(condition.value())) < 0);
      case "after" -> values.stream().anyMatch(value -> compareText(firstDatePart(value), firstDatePart(condition.value())) > 0);
      default -> values.stream().anyMatch(value -> equalsIgnoreCase(value, condition.value()));
    };
  }

  private static List<String> valuesForField(
      ReviewDataRecordRowResponse row,
      String fieldKey,
      Map<Long, List<String>> problemStatusesByRecordId) {
    return switch (fieldKey) {
      case "title" -> List.of(Objects.toString(row.title(), ""));
      case "projectName" -> List.of(Objects.toString(row.projectName(), ""));
      case "moduleName" -> List.of(Objects.toString(row.moduleName(), ""));
      case "reviewOwner" -> List.of(Objects.toString(row.reviewOwner(), ""));
      case "reviewType" -> List.of(Objects.toString(row.reviewType(), ""));
      case "reviewExpert" -> splitMultiValue(row.reviewExpertsSummary());
      case "problemStatus" -> problemStatusesByRecordId.getOrDefault(row.id(), List.of());
      case "reviewScalePages" -> List.of(Objects.toString(row.reviewScalePages(), ""));
      case "problemCount" -> List.of(Objects.toString(row.problemCount(), ""));
      case "problemDensity" -> List.of(Objects.toString(row.problemDensity(), ""));
      case "reviewDate" -> List.of(row.reviewDate() == null ? "" : row.reviewDate().toString());
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

  private static List<String> splitMultiValue(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return List.of();
    }
    return java.util.Arrays.stream(normalized.split("[,，、]"))
        .map(TextQuerySupport::trimToNull)
        .filter(Objects::nonNull)
        .toList();
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private static int compareText(String left, String right) {
    if (left == null || right == null) {
      return 0;
    }
    return left.compareTo(right);
  }

  private static String firstDatePart(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : normalized.substring(0, Math.min(normalized.length(), 10));
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
