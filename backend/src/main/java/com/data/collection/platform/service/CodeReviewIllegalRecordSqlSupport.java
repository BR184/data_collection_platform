package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class CodeReviewIllegalRecordSqlSupport {
  private CodeReviewIllegalRecordSqlSupport() {}

  static Optional<SqlPredicate> toSql(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return Optional.of(new SqlPredicate("", List.of()));
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      Optional<SqlPredicate> conditionSql = conditionToSql(condition);
      if (conditionSql.isEmpty()) {
        return Optional.empty();
      }
      predicates.add("(" + conditionSql.get().predicate() + ")");
      args.addAll(conditionSql.get().args());
    }
    String joiner = "OR".equalsIgnoreCase(filterGroup.logic()) ? " or " : " and ";
    return Optional.of(new SqlPredicate(String.join(joiner, predicates), args));
  }

  static String illegalPredicate(String illegalType) {
    String normalized = TextQuerySupport.trimToNull(illegalType);
    if (normalized == null) {
      return String.join(
          " or ",
          missingModulePredicate(),
          missingOwnerPredicate(),
          missingReviewPredicate(),
          notScannedPredicate(),
          openScanIssuePredicate(),
          "comment_rate is null",
          "defect_count is null",
          "added_lines is null");
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_MODULE_LABEL.equals(normalized)) {
      return missingModulePredicate();
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_OWNER_LABEL.equals(normalized)) {
      return missingOwnerPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_REVIEW_LABEL.equals(normalized)) {
      return missingReviewPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.NOT_SCANNED_LABEL.equals(normalized)) {
      return notScannedPredicate();
    }
    if (CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL.equals(normalized)) {
      return openScanIssuePredicate();
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_COMMENT_RATE_LABEL.equals(normalized)) {
      return "comment_rate is null";
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_DEFECT_COUNT_LABEL.equals(normalized)) {
      return "defect_count is null";
    }
    if (CodeReviewIllegalRuleRegistry.MISSING_ADDED_LINES_LABEL.equals(normalized)) {
      return "added_lines is null";
    }
    return "1 = 0";
  }

  private static Optional<SqlPredicate> conditionToSql(StatisticFilterCondition condition) {
    if (condition == null) {
      return Optional.empty();
    }
    return switch (condition.fieldKey()) {
      case "repositoryName" -> textCondition("repository_name", condition);
      case "mergedAt" -> dateTimeCondition("merged_at_source", condition);
      case "illegalType" -> illegalTypeCondition(condition);
      case "keyword" -> keywordCondition(condition);
      case "requestType" -> requestTypeCondition(condition);
      case "mergeRequestIid" -> numberCondition("merge_request_iid", condition);
      case "owner" -> ownerCondition(condition);
      case "targetBranch" -> textCondition("target_branch", condition);
      case "mergedBy" -> textCondition("merge_user_name", condition);
      case "moduleName" -> textCondition("module_name", condition);
      case "projectName" -> textCondition("project_name", condition);
      case "commentRate" -> numberCondition("comment_rate", condition);
      case "defectCount" -> numberCondition("defect_count", condition);
      case "addedLines" -> numberCondition("added_lines", condition);
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> keywordCondition(StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "contains", "notContains" ->
          indexedSearchCondition(
              List.of("search_text", "search_compact", "search_spell", "search_initials"),
              condition);
      case "eq", "ne" -> keywordEqualsCondition(condition);
      case "isEmpty" -> Optional.of(
          new SqlPredicate(
              String.join(
                  " and ",
                  "nullif(btrim(coalesce(title, '')), '') is null",
                  "nullif(btrim(coalesce(owner_name, '')), '') is null",
                  "nullif(btrim(coalesce(project_name, '')), '') is null",
                  "nullif(btrim(coalesce(repository_name, '')), '') is null",
                  "nullif(btrim(coalesce(module_name, '')), '') is null",
                  "nullif(btrim(coalesce(target_branch, '')), '') is null",
                  "nullif(btrim(coalesce(merge_user_name, '')), '') is null"),
              List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlPredicate(
              String.join(
                  " or ",
                  "nullif(btrim(coalesce(title, '')), '') is not null",
                  "nullif(btrim(coalesce(owner_name, '')), '') is not null",
                  "nullif(btrim(coalesce(project_name, '')), '') is not null",
                  "nullif(btrim(coalesce(repository_name, '')), '') is not null",
                  "nullif(btrim(coalesce(module_name, '')), '') is not null",
                  "nullif(btrim(coalesce(target_branch, '')), '') is not null",
                  "nullif(btrim(coalesce(merge_user_name, '')), '') is not null"),
              List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> keywordEqualsCondition(StatisticFilterCondition condition) {
    List<String> columns =
        List.of("title", "owner_name", "project_name", "repository_name", "module_name", "target_branch", "merge_user_name");
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String column : columns) {
      predicates.add("lower(coalesce(" + column + ", '')) = ?");
      args.add(lower(condition.value()));
    }
    String predicate = "(" + String.join(" or ", predicates) + ")";
    if ("ne".equals(condition.operator())) {
      predicate = "not " + predicate;
    }
    return Optional.of(new SqlPredicate(predicate, args));
  }

  private static Optional<SqlPredicate> ownerCondition(StatisticFilterCondition condition) {
    if ("contains".equals(condition.operator()) || "notContains".equals(condition.operator())) {
      return indexedSearchCondition(
          List.of("owner_search_text", "owner_search_compact", "owner_search_spell", "owner_search_initials"),
          condition);
    }
    return textCondition("owner_name", condition);
  }

  private static Optional<SqlPredicate> illegalTypeCondition(StatisticFilterCondition condition) {
    String predicate = illegalPredicate(condition.value());
    return switch (condition.operator()) {
      case "eq" -> Optional.of(new SqlPredicate(predicate, List.of()));
      case "ne" -> Optional.of(new SqlPredicate("not (" + predicate + ")", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> requestTypeCondition(StatisticFilterCondition condition) {
    boolean isMergeRequest = "merge_request".equalsIgnoreCase(TextQuerySupport.normalizeDisplay(condition.value()));
    return switch (condition.operator()) {
      case "eq" -> Optional.of(isMergeRequest ? truePredicate() : falsePredicate());
      case "ne" -> Optional.of(isMergeRequest ? falsePredicate() : truePredicate());
      default -> Optional.empty();
    };
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

  private static Optional<SqlPredicate> textCondition(String column, StatisticFilterCondition condition) {
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

  private static Optional<SqlPredicate> numberCondition(String column, StatisticFilterCondition condition) {
    Double value = parseDouble(condition.value());
    Double secondary = parseDouble(condition.secondaryValue());
    if (value == null) {
      return Optional.of(falsePredicate());
    }
    return switch (condition.operator()) {
      case "eq" -> Optional.of(new SqlPredicate(column + " = ?", List.of(value)));
      case "gt" -> Optional.of(new SqlPredicate(column + " > ?", List.of(value)));
      case "gte" -> Optional.of(new SqlPredicate(column + " >= ?", List.of(value)));
      case "lt" -> Optional.of(new SqlPredicate(column + " < ?", List.of(value)));
      case "lte" -> Optional.of(new SqlPredicate(column + " <= ?", List.of(value)));
      case "between" -> secondary == null
          ? Optional.of(falsePredicate())
          : Optional.of(
              new SqlPredicate(
                  column + " between ? and ?",
                  List.of(Math.min(value, secondary), Math.max(value, secondary))));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlPredicate> dateTimeCondition(
      String column, StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "year" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY') = ?", List.of(condition.value())));
      case "month" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM') = ?", List.of(condition.value())));
      case "day" -> Optional.of(
          new SqlPredicate("to_char(" + column + ", 'YYYY-MM-DD') = ?", List.of(condition.value())));
      case "at" -> Optional.of(new SqlPredicate(column + " = ?", List.of(parseDateTime(condition.value()))));
      case "before" -> Optional.of(new SqlPredicate(column + " < ?", List.of(parseDateTime(condition.value()))));
      case "after" -> Optional.of(new SqlPredicate(column + " > ?", List.of(parseDateTime(condition.value()))));
      case "between" -> Optional.of(
          new SqlPredicate(
              column + " between ? and ?",
              List.of(parseDateTime(condition.value()), parseDateTime(condition.secondaryValue()))));
      default -> Optional.empty();
    };
  }

  private static String missingModulePredicate() {
    return "(label_names is null or btrim(label_names) = '')";
  }

  private static String missingOwnerPredicate() {
    return "(owner_name is null or btrim(owner_name) = '')";
  }

  private static String missingReviewPredicate() {
    return "(review_status is null or btrim(review_status) = '' or review_duration_minutes is null)";
  }

  private static String notScannedPredicate() {
    StringBuilder sql = new StringBuilder("upper(btrim(coalesce(scan_status, ''))) in (");
    List<String> statuses = CodeReviewIllegalRuleRegistry.notScannedStatuses();
    for (int index = 0; index < statuses.size(); index++) {
      if (index > 0) {
        sql.append(", ");
      }
      sql.append("'").append(statuses.get(index).replace("'", "''").toUpperCase(Locale.ROOT)).append("'");
    }
    sql.append(")");
    return sql.toString();
  }

  private static String openScanIssuePredicate() {
    return "(scan_bug_count is not null and scan_bug_count > 0)";
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
