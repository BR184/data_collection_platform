package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.statistics.SystemTestIssueMetricDimensionSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
          Map.entry("functionName", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("testingPhase", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("reasonCategory", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("fixUser", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("delayCause", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("illegalReason", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("severityLevel", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("priorityLevel", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("issueState", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("bugStatus", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("category", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("assigneeName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("milestoneTitle", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("createdAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")),
          Map.entry("updatedAt", List.of("year", "month", "day", "before", "after", "between", "isEmpty", "isNotEmpty")));

  /** CC_PRODUCT 议题明细专用字段，延期问题与非法数据页不接收该条件。 */
  static final Map<String, List<String>> CUSTOMER_ISSUE_RECORD_FILTER_OPERATORS =
      createCustomerIssueRecordFilterOperators();

  static final Map<String, List<String>> SYSTEM_TEST_FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("keyword", List.of("contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("issueIid", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("title", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("projectName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("moduleName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("functionName", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("testingPhase", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("metricSeverity", List.of("eq", "ne")),
          Map.entry("regularMetric", List.of("eq", "ne")),
          Map.entry("majorCause", List.of("eq", "ne")),
          Map.entry("causeMetric", List.of("eq", "ne")),
          Map.entry("reasonCategory", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("fixUser", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty")),
          Map.entry("delayCause", List.of("eq", "ne")),
          Map.entry("delayIssue", List.of("eq", "ne")),
          Map.entry("rollback", List.of("eq", "ne")),
          Map.entry("openIssue", List.of("eq", "ne")),
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

  private enum TestingPhaseValueSource {
    FILTER_VALUE,
    PRIMARY_PHASE,
    FACT_VALUE
  }

  private IssueFactRecordFilterGroupSupport() {}

  private static Map<String, List<String>> createCustomerIssueRecordFilterOperators() {
    Map<String, List<String>> operators = new LinkedHashMap<>(CUSTOMER_ISSUE_FILTER_OPERATORS);
    operators.put("customerName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty"));
    operators.put("handlerName", List.of("eq", "ne", "contains", "notContains", "isEmpty", "isNotEmpty"));
    return Map.copyOf(operators);
  }

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
    } catch (BizException exception) {
      throw exception;
    } catch (Exception ignored) {
      return empty();
    }
  }

  static boolean hasLabelGroupConditions(StatisticFilterGroup filterGroup) {
    return filterGroup != null
        && filterGroup.conditions() != null
        && filterGroup.conditions().stream().anyMatch(StatisticFilterCondition::usesLabelGroup);
  }

  static boolean matches(IssueFactRecord row, StatisticFilterGroup filterGroup) {
    return matches(row, filterGroup, TestingPhaseValueSource.FILTER_VALUE, false);
  }

  static boolean matches(
      IssueFactRecord row, StatisticFilterGroup filterGroup, boolean useFullTestingPhase) {
    return matches(
        row,
        filterGroup,
        useFullTestingPhase
            ? TestingPhaseValueSource.PRIMARY_PHASE
            : TestingPhaseValueSource.FILTER_VALUE,
        false);
  }

  static boolean matchesCustomerIssue(IssueFactRecord row, StatisticFilterGroup filterGroup) {
    return matches(row, filterGroup, TestingPhaseValueSource.FACT_VALUE, true);
  }

  private static boolean matches(
      IssueFactRecord row,
      StatisticFilterGroup filterGroup,
      TestingPhaseValueSource testingPhaseValueSource,
      boolean useFactDelayCause) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched =
          matchesCondition(row, condition, testingPhaseValueSource, useFactDelayCause);
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
    if (fieldKey == null || operator == null) {
      return null;
    }
    String value = TextQuerySupport.trimToNull(condition.value());
    String secondaryValue = TextQuerySupport.trimToNull(condition.secondaryValue());
    String valueType = TextQuerySupport.trimToNull(condition.valueType());
    if ("LABEL_GROUP".equalsIgnoreCase(valueType)) {
      if (!allowedOperators.containsKey(fieldKey)) {
        return null;
      }
      if (!LabelGroupFilterOperatorSupport.isSetOperator(operator)) {
        throw new BizException("标签组筛选只支持集合关系");
      }
      if (!LabelGroupFilterOperatorSupport.supportsPartialContainsAny(operator, fieldValueType(fieldKey))) {
        throw new BizException("局部包含任意标签组仅支持字符串字段");
      }
      if (condition.labelGroupId() == null) {
        throw new BizException("标签组筛选缺少标签组 ID");
      }
      return new StatisticFilterCondition(
          fieldKey,
          LabelGroupFilterOperatorSupport.normalize(operator),
          null,
          null,
          "LABEL_GROUP",
          condition.labelGroupId(),
          TextQuerySupport.trimToNull(condition.labelGroupName()),
          condition.values() == null ? List.of() : condition.values());
    }
    if (!allowedOperators.getOrDefault(fieldKey, List.of()).contains(operator)) {
      return null;
    }
    if (requiresPrimaryValue(operator) && value == null) {
      return null;
    }
    if ("between".equals(operator) && secondaryValue == null) {
      return null;
    }
    return new StatisticFilterCondition(fieldKey, operator, value, secondaryValue);
  }

  private static String fieldValueType(String fieldKey) {
    return List.of("createdAt", "updatedAt").contains(fieldKey) ? "DATE" : "STRING";
  }

  private static boolean matchesCondition(
      IssueFactRecord row,
      StatisticFilterCondition condition,
      TestingPhaseValueSource testingPhaseValueSource,
      boolean useFactDelayCause) {
    if ("bugStatus".equals(condition.fieldKey())) {
      return condition.usesLabelGroup()
          ? IssueStatusMembers.matchesLabelGroup(
              row.bugStatus(), condition.operator(), condition.values())
          : IssueStatusMembers.matchesFilter(
              row.bugStatus(), condition.operator(), condition.value());
    }
    if ("testingPhase".equals(condition.fieldKey())
        && testingPhaseValueSource == TestingPhaseValueSource.FACT_VALUE
        && CustomerIssueTestingPhaseSupport.isUnspecifiedFilter(condition.value())) {
      return matchesUnspecifiedTestingPhase(row.testingPhase(), condition.operator());
    }
    List<String> values =
        valuesForField(row, condition.fieldKey(), testingPhaseValueSource, useFactDelayCause);
    if (condition.usesLabelGroup()) {
      return matchesLabelGroup(values, condition);
    }
    if ("RESOLVED_LITERAL_SET".equalsIgnoreCase(condition.valueType())) {
      return matchesResolvedSet(values, condition);
    }
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

  private static boolean matchesLabelGroup(List<String> actualValues, StatisticFilterCondition condition) {
    List<String> expectedValues = condition.values() == null ? List.of() : condition.values();
    return LabelGroupFilterOperatorSupport.matches(actualValues, expectedValues, condition.operator());
  }

  private static boolean matchesResolvedSet(List<String> actualValues, StatisticFilterCondition condition) {
    List<String> expectedValues = condition.values() == null ? List.of() : condition.values();
    boolean matched =
        actualValues.stream()
            .anyMatch(actual -> expectedValues.stream().anyMatch(expected -> equalsIgnoreCase(actual, expected)));
    return "ne".equals(condition.operator()) ? !matched : matched;
  }

  private static List<String> valuesForField(
      IssueFactRecord row,
      String fieldKey,
      TestingPhaseValueSource testingPhaseValueSource,
      boolean useFactDelayCause) {
    return switch (fieldKey) {
      case "keyword" ->
          List.of(
              Objects.toString(row.issueIid(), ""),
              Objects.toString(row.title(), ""),
              Objects.toString(row.projectName(), ""),
              String.join(" ", row.moduleNames()),
              Objects.toString(row.functionName(), ""),
               testingPhaseValue(row, testingPhaseValueSource),
              Objects.toString(row.reasonCategory(), ""),
              String.join(" ", illegalReasonValues(row)),
              Objects.toString(row.authorName(), ""),
              Objects.toString(row.handlerName(), ""),
              Objects.toString(row.assigneeName(), ""),
              Objects.toString(row.milestoneTitle(), ""));
      case "issueIid" -> List.of(Objects.toString(row.issueIid(), ""));
      case "title" -> List.of(Objects.toString(row.title(), ""));
      case "customerName" -> row.customerNames();
      case "projectName" -> List.of(Objects.toString(row.projectName(), ""));
      case "moduleName" -> row.moduleNames();
      case "functionName" -> List.of(Objects.toString(row.functionName(), ""));
      case "testingPhase" -> List.of(testingPhaseValue(row, testingPhaseValueSource));
      case "metricSeverity" -> List.of(SystemTestIssueMetricDimensionSupport.metricSeverity(
          row.excluded(), row.exclusionReason(), row.severityLevel(), row.category()));
      case "regularMetric" -> List.of(Boolean.toString(SystemTestIssueMetricDimensionSupport.regularMetric(
          row.excluded(), row.exclusionReason(), row.severityLevel(), row.category())));
      case "majorCause" -> List.of(SystemTestIssueMetricDimensionSupport.majorCause(
          row.reasonCategory(), String.join(" ", row.labels())));
      case "causeMetric" -> SystemTestIssueMetricDimensionSupport.matchingCauseMetricKeys(
          row.reasonCategory(), String.join(" ", row.labels()));
      case "reasonCategory" -> List.of(Objects.toString(row.reasonCategory(), ""));
      case "fixUser" -> List.of(Objects.toString(row.fixUser(), ""));
      case "delayCause" -> useFactDelayCause
          ? IssueDelayCauseMembers.parse(row.delayCause())
          : SystemTestIssueMetricDimensionSupport.delayCauses(
              row.delayCause(), row.delayReason(), String.join(" ", row.labels()));
      case "delayIssue" -> List.of(Boolean.toString(row.delayIssue()));
      case "rollback" -> List.of(Boolean.toString(SystemTestIssueMetricDimensionSupport.rollback(
          row.regression(), row.title(), String.join(" ", row.labels()))));
      case "openIssue" -> List.of(Boolean.toString(!"closed".equalsIgnoreCase(row.issueState())));
      case "illegalReason" -> illegalReasonValues(row);
      case "severityLevel" -> List.of(Objects.toString(row.severityLevel(), ""));
      case "priorityLevel" -> List.of(Objects.toString(row.priorityLevel(), ""));
      case "issueState" -> List.of(Objects.toString(row.issueState(), ""));
      case "bugStatus" -> IssueStatusMembers.parse(row.bugStatus());
      case "category" -> List.of(Objects.toString(row.category(), ""));
      case "milestoneTitle" -> List.of(Objects.toString(row.milestoneTitle(), ""));
      case "authorName" -> List.of(Objects.toString(row.authorName(), ""));
      case "handlerName" -> List.of(Objects.toString(row.handlerName(), ""));
      case "assigneeName" -> List.of(Objects.toString(row.assigneeName(), ""));
      case "createdAt" -> List.of(formatDateTime(row.createdAt()));
      case "updatedAt" -> List.of(formatDateTime(row.updatedAt()));
      default -> List.of();
    };
  }

  private static String testingPhaseValue(
      IssueFactRecord row, TestingPhaseValueSource testingPhaseValueSource) {
    return switch (testingPhaseValueSource) {
      case FILTER_VALUE -> Objects.toString(row.phaseFilterValue(), "");
      case PRIMARY_PHASE -> Objects.toString(row.primaryPhaseLabel(), "");
      case FACT_VALUE -> Objects.toString(row.testingPhase(), "");
    };
  }

  private static boolean matchesUnspecifiedTestingPhase(String rawTestingPhase, String operator) {
    boolean unspecified = TextQuerySupport.trimToNull(rawTestingPhase) == null;
    return switch (operator) {
      case "eq", "contains", "isEmpty" -> unspecified;
      case "ne", "notContains", "isNotEmpty" -> !unspecified;
      default -> false;
    };
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private static List<String> illegalReasonValues(IssueFactRecord row) {
    List<String> rawReasons =
        row.illegalReasons() == null || row.illegalReasons().isEmpty()
            ? List.of(Objects.toString(row.illegalReason(), ""))
            : row.illegalReasons();
    return rawReasons.stream()
        .flatMap(
            raw -> {
              String safeRaw = Objects.toString(raw, "");
              String normalized = SystemTestIllegalReasonSupport.normalize(safeRaw);
              if (normalized == null || normalized.equals(safeRaw)) {
                return java.util.stream.Stream.of(safeRaw);
              }
              return java.util.stream.Stream.of(safeRaw, normalized);
            })
        .distinct()
        .toList();
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
