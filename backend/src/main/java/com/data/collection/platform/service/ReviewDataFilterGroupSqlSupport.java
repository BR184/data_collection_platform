package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class ReviewDataFilterGroupSqlSupport {
  private ReviewDataFilterGroupSqlSupport() {}

  static boolean canPushDown(StatisticFilterGroup filterGroup) {
    return toSql(filterGroup).isPresent();
  }

  static Optional<SqlFilter> toSql(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return Optional.of(new SqlFilter("", List.of()));
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      Optional<SqlFilter> conditionSql = conditionToSql(condition);
      if (conditionSql.isEmpty()) {
        return Optional.empty();
      }
      predicates.add("(" + conditionSql.get().predicate() + ")");
      args.addAll(conditionSql.get().args());
    }
    String joiner = "OR".equalsIgnoreCase(filterGroup.logic()) ? " or " : " and ";
    return Optional.of(new SqlFilter(String.join(joiner, predicates), args));
  }

  private static Optional<SqlFilter> conditionToSql(StatisticFilterCondition condition) {
    if (condition == null) {
      return Optional.empty();
    }
    return switch (condition.fieldKey()) {
      case "title" -> textCondition("r.title", condition, false);
      case "projectName" -> textCondition("r.project_name", condition, false);
      case "moduleName" -> textCondition("r.module_name", condition, false);
      case "reviewOwner" -> textCondition("r.review_owner", condition, false);
      case "reviewType" -> textCondition("r.review_type", condition, false);
      case "reviewExpert" -> multiValueCondition(
          "review_record_experts", "expert_name", condition);
      case "problemStatus" -> multiValueCondition(
          "review_problem_items", "problem_status", condition);
      case "reviewScalePages" -> numberCondition("r.review_scale_pages", condition);
      case "problemCount" -> numberCondition("coalesce(problem.problem_count, 0)", condition);
      case "problemDensity" -> numberCondition(
          "case when r.review_scale_pages <= 0 then 0 else coalesce(problem.problem_count, 0)::numeric / r.review_scale_pages end",
          condition);
      case "reviewDate" -> dateCondition("r.review_date", condition);
      default -> Optional.empty();
    };
  }

  private static Optional<SqlFilter> textCondition(
      String expression, StatisticFilterCondition condition, boolean allowContains) {
    String operator = condition.operator();
    return switch (operator) {
      case "eq" -> Optional.of(
          new SqlFilter("lower(coalesce(" + expression + ", '')) = ?", List.of(lower(condition.value()))));
      case "ne" -> Optional.of(
          new SqlFilter("lower(coalesce(" + expression + ", '')) <> ?", List.of(lower(condition.value()))));
      case "isEmpty" -> Optional.of(
          new SqlFilter("nullif(btrim(coalesce(" + expression + ", '')), '') is null", List.of()));
      case "isNotEmpty" -> Optional.of(
          new SqlFilter("nullif(btrim(coalesce(" + expression + ", '')), '') is not null", List.of()));
      case "contains" -> allowContains
          ? Optional.of(
              new SqlFilter(
                  "lower(coalesce(" + expression + ", '')) like ?",
                  List.of("%" + lower(condition.value()) + "%")))
          : Optional.empty();
      case "notContains" -> allowContains
          ? Optional.of(
              new SqlFilter(
                  "lower(coalesce(" + expression + ", '')) not like ?",
                  List.of("%" + lower(condition.value()) + "%")))
          : Optional.empty();
      default -> Optional.empty();
    };
  }

  private static Optional<SqlFilter> multiValueCondition(
      String tableName, String valueColumn, StatisticFilterCondition condition) {
    String relationshipColumn = "review_record_id";
    String existsPrefix =
        "exists (select 1 from " + tableName + " fg where fg." + relationshipColumn
            + " = r.id and fg.deleted = false";
    String hasText = " and nullif(btrim(coalesce(fg." + valueColumn + ", '')), '') is not null";
    return switch (condition.operator()) {
      case "eq" -> Optional.of(
          new SqlFilter(
              existsPrefix + " and lower(coalesce(fg." + valueColumn + ", '')) = ?)",
              List.of(lower(condition.value()))));
      case "ne" -> Optional.of(
          new SqlFilter(
              "not " + existsPrefix + " and lower(coalesce(fg." + valueColumn + ", '')) = ?)",
              List.of(lower(condition.value()))));
      case "isEmpty" -> Optional.of(new SqlFilter("not " + existsPrefix + hasText + ")", List.of()));
      case "isNotEmpty" -> Optional.of(new SqlFilter(existsPrefix + hasText + ")", List.of()));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlFilter> numberCondition(
      String expression, StatisticFilterCondition condition) {
    Double value = parseDouble(condition.value());
    Double secondary = parseDouble(condition.secondaryValue());
    return switch (condition.operator()) {
      case "eq" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " = ?", List.of(value)));
      case "gt" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " > ?", List.of(value)));
      case "gte" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " >= ?", List.of(value)));
      case "lt" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " < ?", List.of(value)));
      case "lte" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " <= ?", List.of(value)));
      case "between" -> value == null || secondary == null
          ? Optional.of(falsePredicate())
          : Optional.of(
              new SqlFilter(
                  expression + " between ? and ?",
                  List.of(Math.min(value, secondary), Math.max(value, secondary))));
      default -> Optional.empty();
    };
  }

  private static Optional<SqlFilter> dateCondition(String expression, StatisticFilterCondition condition) {
    LocalDate value = parseDate(condition.value());
    LocalDate secondary = parseDate(condition.secondaryValue());
    return switch (condition.operator()) {
      case "day" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " = ?", List.of(value)));
      case "before" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " < ?", List.of(value)));
      case "after" -> value == null
          ? Optional.of(falsePredicate())
          : Optional.of(new SqlFilter(expression + " > ?", List.of(value)));
      case "between" -> value == null || secondary == null
          ? Optional.of(falsePredicate())
          : Optional.of(
              new SqlFilter(
                  expression + " between ? and ?",
                  List.of(value.isBefore(secondary) ? value : secondary, value.isBefore(secondary) ? secondary : value)));
      default -> Optional.empty();
    };
  }

  private static SqlFilter falsePredicate() {
    return new SqlFilter("1 = 0", List.of());
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

  private static LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return LocalDate.parse(normalized.substring(0, Math.min(normalized.length(), 10)));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  record SqlFilter(String predicate, List<Object> args) {}
}
