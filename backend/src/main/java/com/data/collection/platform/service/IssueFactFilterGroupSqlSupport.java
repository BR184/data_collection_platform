package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class IssueFactFilterGroupSqlSupport {
  private IssueFactFilterGroupSqlSupport() {}

  static Optional<SqlPredicate> toSql(StatisticFilterGroup filterGroup) {
    return toSql(filterGroup, false);
  }

  static Optional<SqlPredicate> toSql(StatisticFilterGroup filterGroup, boolean useFullTestingPhase) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return Optional.of(new SqlPredicate("", List.of()));
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      Optional<SqlPredicate> conditionSql = conditionToSql(condition, useFullTestingPhase);
      if (conditionSql.isEmpty()) {
        return Optional.empty();
      }
      predicates.add("(" + conditionSql.get().predicate() + ")");
      args.addAll(conditionSql.get().args());
    }
    String joiner = "OR".equalsIgnoreCase(filterGroup.logic()) ? " or " : " and ";
    return Optional.of(new SqlPredicate(String.join(joiner, predicates), args));
  }

  private static Optional<SqlPredicate> conditionToSql(
      StatisticFilterCondition condition, boolean useFullTestingPhase) {
    if (condition == null) {
      return Optional.empty();
    }
    return switch (condition.fieldKey()) {
      case "keyword" -> keywordCondition(condition);
      case "issueIid" -> issueIidCondition(condition);
      case "title" -> titleCondition(condition);
      case "projectName" -> textCondition("project_name", condition);
      case "moduleName" -> moduleCondition(condition);
      case "functionName" -> textCondition("function_name", condition);
      case "testingPhase" -> phaseCondition(condition, useFullTestingPhase);
      case "reasonCategory" -> textCondition("reason_category", condition);
      case "illegalReason" -> illegalReasonCondition(condition);
      case "severityLevel" -> textCondition("severity_level", condition);
      case "priorityLevel" -> textCondition("priority_level", condition);
      case "issueState" -> textCondition("issue_state", condition);
      case "bugStatus" -> textCondition("bug_status", condition);
      case "category" -> textCondition("category", condition);
      case "milestoneTitle" -> milestoneCondition(condition);
      case "authorName" -> authorCondition(condition);
      case "assigneeName" -> assigneeCondition(condition);
      case "createdAt" -> dateTimeCondition("created_at_source", condition);
      case "updatedAt" -> dateTimeCondition("updated_at_source", condition);
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> keywordCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "contains", "notContains" ->
          indexedSearchCondition(
              List.of("search_text", "search_compact", "search_spell", "search_initials"),
              condition);
      case "isEmpty" -> Optional.of(falsePredicate());
      case "isNotEmpty" -> Optional.of(truePredicate());
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> titleCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "title_search_text",
              "title_search_compact",
              "title_search_spell",
              "title_search_initials"),
          condition);
    }
    return textCondition("title", condition);
  }

  private static Optional<SqlPredicate> moduleCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "eq" -> Optional.of(
          new SqlPredicate("lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?",
              List.of("%," + lower(condition.value()) + ",%")));
      case "ne" -> Optional.of(
          new SqlPredicate("lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') not like ?",
              List.of("%," + lower(condition.value()) + ",%")));
      case "contains", "notContains" ->
          indexedSearchCondition(
              List.of(
                  "module_search_text",
                  "module_search_compact",
                  "module_search_spell",
                  "module_search_initials"),
              condition);
      case "isEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(module_names, '')), '') is null", List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(module_names, '')), '') is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> phaseCondition(
      StatisticFilterCondition condition, boolean useFullTestingPhase) {
    if ("RESOLVED_LITERAL_SET".equalsIgnoreCase(condition.valueType())) {
      return resolvedLiteralSetCondition(useFullTestingPhase ? "testing_phase" : "phase_filter_value", condition);
    }
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      if (useFullTestingPhase) {
        return containsTextCondition("testing_phase", condition);
      }
      return indexedSearchCondition(
          List.of("phase_search_text", "phase_search_compact", "phase_search_spell", "phase_search_initials"),
          condition);
    }
    return textCondition(useFullTestingPhase ? "testing_phase" : "phase_filter_value", condition);
  }

  private static Optional<SqlPredicate> resolvedLiteralSetCondition(
      String column, StatisticFilterCondition condition) {
    List<String> values =
        condition.values() == null
            ? List.of()
            : condition.values().stream()
                .map(TextQuerySupport::trimToNull)
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    if (values.isEmpty()) {
      return Optional.of("ne".equals(condition.operator()) ? truePredicate() : falsePredicate());
    }
    String placeholders = String.join(",", values.stream().map(ignored -> "?").toList());
    String predicate = "lower(coalesce(" + column + ", '')) in (" + placeholders + ")";
    if ("ne".equals(condition.operator())) {
      predicate = "not (" + predicate + ")";
    }
    return Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)));
  }

  private static Optional<SqlPredicate> milestoneCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "milestone_search_text",
              "milestone_search_compact",
              "milestone_search_spell",
              "milestone_search_initials"),
          condition);
    }
    return textCondition("milestone_title", condition);
  }

  private static Optional<SqlPredicate> authorCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of("author_search_text", "author_search_compact", "author_search_spell", "author_search_initials"),
          condition);
    }
    return textCondition("author_name", condition);
  }

  private static Optional<SqlPredicate> assigneeCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of(
              "assignee_search_text",
              "assignee_search_compact",
              "assignee_search_spell",
              "assignee_search_initials"),
          condition);
    }
    return textCondition("assignee_name", condition);
  }

  private static Optional<SqlPredicate> illegalReasonCondition(StatisticFilterCondition condition) {
    String operator = condition.operator();
    if ("eq".equals(operator) || "ne".equals(operator)) {
      List<String> rawReasons = SystemTestIllegalReasonSupport.rawReasonsFor(condition.value());
      if (rawReasons.isEmpty()) {
        return textCondition("illegal_reason", condition);
      }
      List<String> predicates = new ArrayList<>();
      List<Object> args = new ArrayList<>();
      for (String rawReason : rawReasons) {
        predicates.add(
            "lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') like ?");
        args.add("%," + lower(rawReason) + ",%");
      }
      String predicate = String.join(" or ", predicates);
      if ("ne".equals(operator)) {
        predicate = "not (" + predicate + ")";
      }
      return Optional.of(new SqlPredicate(predicate, args));
    }
    return textCondition("illegal_reason", condition);
  }

  private static Optional<SqlPredicate> indexedSearchCondition(
      List<String> columns, StatisticFilterCondition condition) {
    List<String> candidates = FactSearchIndexSupport.keywordCandidates(condition.value());
    if (candidates.isEmpty()) {
      return Optional.of(truePredicate());
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String candidate : candidates) {
      String pattern = "%" + candidate + "%";
      for (String column : columns) {
        predicates.add(column + " like ?");
        args.add(pattern);
      }
    }
    String predicate = "(" + String.join(" or ", predicates) + ")";
    if ("notContains".equals(condition.operator())) {
      predicate = "not " + predicate;
    }
    return Optional.of(new SqlPredicate(predicate, args));
  }

  private static Optional<SqlPredicate> textCondition(
      String column, StatisticFilterCondition condition) {
    if (condition.usesLabelGroup()) {
      return labelGroupTextCondition(column, condition);
    }
    return switch (condition.operator()) {
      case "eq" -> Optional.of(
          new SqlPredicate("lower(coalesce(" + column + ", '')) = ?", List.of(lower(condition.value()))));
      case "ne" -> Optional.of(
          new SqlPredicate("lower(coalesce(" + column + ", '')) <> ?", List.of(lower(condition.value()))));
      case "isEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(" + column + ", '')), '') is null", List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlPredicate("nullif(btrim(coalesce(" + column + ", '')), '') is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> labelGroupTextCondition(
      String column, StatisticFilterCondition condition) {
    if (LabelGroupFilterOperatorSupport.isPartialContainsAny(condition.operator())) {
      return partialContainsAnyCondition(column, condition);
    }
    List<String> values = normalizedLabelGroupValues(condition);
    if (values.isEmpty()) {
      return Optional.of(falsePredicate());
    }
    String placeholders = String.join(",", values.stream().map(ignored -> "?").toList());
    String predicate = "lower(coalesce(" + column + ", '')) in (" + placeholders + ")";
    return switch (condition.operator()) {
      case "notIntersects", "notContainsAll" -> Optional.of(new SqlPredicate("not (" + predicate + ")", new ArrayList<>(values)));
      case "containsAll" -> values.size() == 1
          ? Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)))
          : Optional.of(falsePredicate());
      default -> Optional.of(new SqlPredicate(predicate, new ArrayList<>(values)));
    };
  }

  private static Optional<SqlPredicate> partialContainsAnyCondition(
      String column, StatisticFilterCondition condition) {
    List<String> patterns = normalizedLabelGroupValues(condition).stream()
        .map(LabelGroupFilterOperatorSupport::likeContainsPattern)
        .filter(value -> value != null)
        .toList();
    if (patterns.isEmpty()) {
      return Optional.of(falsePredicate());
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String pattern : patterns) {
      predicates.add("lower(coalesce(" + column + ", '')) like ? escape '\\'");
      args.add(pattern);
    }
    return Optional.of(new SqlPredicate(String.join(" or ", predicates), args));
  }

  private static List<String> normalizedLabelGroupValues(StatisticFilterCondition condition) {
    if (condition.values() == null) {
      return List.of();
    }
    return condition.values().stream()
        .map(TextQuerySupport::trimToNull)
        .filter(value -> value != null)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }

  private static Optional<SqlPredicate> containsTextCondition(
      String column, StatisticFilterCondition condition) {
    String predicate = "lower(coalesce(" + column + ", '')) like ?";
    if ("notContains".equals(condition.operator())) {
      predicate = "not (" + predicate + ")";
    }
    return Optional.of(new SqlPredicate(predicate, List.of("%" + lower(condition.value()) + "%")));
  }

  private static Optional<SqlPredicate> issueIidCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "contains" -> Optional.of(
          new SqlPredicate("cast(issue_iid as varchar) like ?", List.of("%" + condition.value() + "%")));
      case "eq" -> Optional.of(new SqlPredicate("issue_iid = ?", List.of(parseLong(condition.value()))));
      case "ne" -> Optional.of(new SqlPredicate("issue_iid <> ?", List.of(parseLong(condition.value()))));
      case "isEmpty" -> Optional.of(falsePredicate());
      case "isNotEmpty" -> Optional.of(truePredicate());
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> dateTimeCondition(
      String column, StatisticFilterCondition condition) {
    String operator = condition.operator();
    return switch (operator) {
      case "year" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY') = ?", List.of(condition.value())));
      case "month" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM') = ?", List.of(condition.value())));
      case "day" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM-DD') = ?", List.of(condition.value())));
      case "before" -> Optional.of(new SqlPredicate(column + " < ?", List.of(parseDateTime(condition.value()))));
      case "after" -> Optional.of(new SqlPredicate(column + " > ?", List.of(parseDateTime(condition.value()))));
      case "between" -> Optional.of(
          new SqlPredicate(
              column + " between ? and ?",
              List.of(parseDateTime(condition.value()), parseDateTime(condition.secondaryValue()))));
      case "isEmpty" -> Optional.of(new SqlPredicate(column + " is null", List.of()));
      case "isNotEmpty" -> Optional.of(new SqlPredicate(column + " is not null", List.of()));
      default -> Optional.empty();
    };
  }

  private static SqlPredicate truePredicate() {
    return new SqlPredicate("1 = 1", List.of());
  }

  private static SqlPredicate falsePredicate() {
    return new SqlPredicate("1 = 0", List.of());
  }

  private static String lower(String value) {
    return TextQuerySupport.normalizeDisplay(value).toLowerCase(Locale.ROOT);
  }

  private static Long parseLong(String value) {
    try {
      return Long.parseLong(TextQuerySupport.normalizeDisplay(value));
    } catch (NumberFormatException exception) {
      return Long.MIN_VALUE;
    }
  }

  private static LocalDateTime parseDateTime(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return LocalDateTime.MIN;
    }
    try {
      if (normalized.length() <= 10) {
        return LocalDate.parse(normalized.substring(0, Math.min(normalized.length(), 10))).atStartOfDay();
      }
      return LocalDateTime.parse(normalized.replace(' ', 'T'));
    } catch (RuntimeException exception) {
      return LocalDateTime.MIN;
    }
  }
}
