package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class IssueFactRecordFilterGroupSupport {
  static final Map<String, List<String>> CUSTOMER_ISSUE_FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("keyword", List.of("contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("issueIid", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("title", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("projectName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("moduleName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("reasonCategory", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("illegalReason", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("severityLevel", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("priorityLevel", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("issueState", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("bugStatus", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("category", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("milestoneTitle", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("createdAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")),
          Map.entry("updatedAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")));

  static final Map<String, List<String>> SYSTEM_TEST_FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("keyword", List.of("contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("issueIid", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("title", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("projectName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("moduleName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("testingPhase", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("illegalReason", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("severityLevel", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("issueState", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("bugStatus", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("category", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("milestoneTitle", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("authorName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("assigneeName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("createdAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")),
          Map.entry("updatedAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")));

  private IssueFactRecordFilterGroupSupport() {}

  static StatisticFilterGroup parse(
      ObjectMapper objectMapper, String filterGroupJson, Map<String, List<String>> allowedOperators) {
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
              .map(condition -> normalizeCondition(condition, allowedOperators))
              .filter(Objects::nonNull)
              .toList();
      return new StatisticFilterGroup("OR".equalsIgnoreCase(parsed.logic()) ? "OR" : "AND", conditions);
    } catch (Exception ignored) {
      return empty();
    }
  }

  static boolean matches(IssueFactRecord row, StatisticFilterGroup filterGroup) {
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

  private static StatisticFilterCondition normalizeCondition(
      StatisticFilterCondition condition, Map<String, List<String>> allowedOperators) {
    if (condition == null) {
      return null;
    }
    String fieldKey = TextQuerySupport.trimToNull(condition.fieldKey());
    String operator = TextQuerySupport.trimToNull(condition.operator());
    if (fieldKey == null
        || operator == null
        || !allowedOperators.getOrDefault(fieldKey, List.of()).contains(operator)) {
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

  private static boolean matchesCondition(IssueFactRecord row, StatisticFilterCondition condition) {
    List<String> values = valuesForField(row, condition.fieldKey());
    return switch (condition.operator()) {
      case "isEmpty" -> values.stream().allMatch(value -> TextQuerySupport.trimToNull(value) == null);
      case "isNotEmpty" -> values.stream().anyMatch(value -> TextQuerySupport.trimToNull(value) != null);
      case "ne" -> values.stream().noneMatch(value -> equalsIgnoreCase(value, condition.value()));
      case "contains" -> values.stream().anyMatch(value -> TextQuerySupport.containsAbstractSearch(value, condition.value()));
      case "notContains" -> values.stream().noneMatch(value -> TextQuerySupport.containsAbstractSearch(value, condition.value()));
      case "year" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 4), condition.value()));
      case "month" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 7), condition.value()));
      case "day" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value, 10), condition.value()));
      case "before" -> values.stream().anyMatch(value -> canCompare(value, condition.value()) && compareText(value, condition.value()) < 0);
      case "after" -> values.stream().anyMatch(value -> canCompare(value, condition.value()) && compareText(value, condition.value()) > 0);
      case "between" -> values.stream().anyMatch(value ->
          canCompare(value, condition.value())
              && canCompare(value, condition.secondaryValue())
              && compareText(value, condition.value()) >= 0
              && compareText(value, condition.secondaryValue()) <= 0);
      default -> values.stream().anyMatch(value -> equalsIgnoreCase(value, condition.value()));
    };
  }

  private static List<String> valuesForField(IssueFactRecord row, String fieldKey) {
    return switch (fieldKey) {
      case "keyword" ->
          List.of(
              Objects.toString(row.issueIid(), ""),
              Objects.toString(row.title(), ""),
              Objects.toString(row.projectName(), ""),
              String.join(" ", row.moduleNames()),
              Objects.toString(row.phaseFilterValue(), ""),
              Objects.toString(row.reasonCategory(), ""),
              Objects.toString(row.illegalReason(), ""),
              Objects.toString(row.authorName(), ""),
              Objects.toString(row.assigneeName(), ""),
              Objects.toString(row.milestoneTitle(), ""));
      case "issueIid" -> List.of(Objects.toString(row.issueIid(), ""));
      case "title" -> List.of(Objects.toString(row.title(), ""));
      case "projectName" -> List.of(Objects.toString(row.projectName(), ""));
      case "moduleName" -> row.moduleNames();
      case "testingPhase" -> List.of(Objects.toString(row.phaseFilterValue(), ""));
      case "reasonCategory" -> List.of(Objects.toString(row.reasonCategory(), ""));
      case "illegalReason" -> illegalReasonValues(row.illegalReason());
      case "severityLevel" -> List.of(Objects.toString(row.severityLevel(), ""));
      case "priorityLevel" -> List.of(Objects.toString(row.priorityLevel(), ""));
      case "issueState" -> List.of(Objects.toString(row.issueState(), ""));
      case "bugStatus" -> List.of(Objects.toString(row.bugStatus(), ""));
      case "category" -> List.of(Objects.toString(row.category(), ""));
      case "milestoneTitle" -> List.of(Objects.toString(row.milestoneTitle(), ""));
      case "authorName" -> List.of(Objects.toString(row.authorName(), ""));
      case "assigneeName" -> List.of(Objects.toString(row.assigneeName(), ""));
      case "createdAt" -> List.of(formatDateTime(row.createdAt()));
      case "updatedAt" -> List.of(formatDateTime(row.updatedAt()));
      default -> List.of();
    };
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private static List<String> illegalReasonValues(String illegalReason) {
    String raw = Objects.toString(illegalReason, "");
    String normalized = SystemTestIllegalReasonSupport.normalize(raw);
    if (normalized == null || normalized.equals(raw)) {
      return List.of(raw);
    }
    return List.of(raw, normalized);
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
    if (normalized == null || normalized.length() < length) {
      return null;
    }
    return normalized.substring(0, length);
  }

  private static String normalizeDateTime(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : normalized.replace('T', ' ');
  }

  private static String formatDateTime(LocalDateTime value) {
    return value == null ? "" : value.toString();
  }

  private static boolean requiresPrimaryValue(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }

  private static StatisticFilterGroup empty() {
    return new StatisticFilterGroup("AND", List.of());
  }
}
