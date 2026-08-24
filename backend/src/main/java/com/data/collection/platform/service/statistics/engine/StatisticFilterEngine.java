package com.data.collection.platform.service.statistics.engine;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.util.StringUtils;

/**
 * 统计看板筛选引擎：全平台操作符语义的唯一实现。
 *
 * <p>输入 {@link StatisticFilterGroup} 与字段注册表，输出已编译的行谓词；
 * 一次请求编译一次，期望值集合与日期边界在编译期归一化，行求值阶段短路执行。
 * SQL 下推路径必须与本引擎语义保持一致（由 FilterSemanticsContractTest 平价测试锁定）。
 */
public final class StatisticFilterEngine {

  private StatisticFilterEngine() {}

  /**
   * 编译筛选组为行谓词。
   *
   * <p>空组或空条件列表恒真；AND 组遇假即假、OR 组遇真即真（与既有看板行为逐字一致）；
   * 未注册的字段键恒真（保持"未知条件不收窄范围"的既有契约）。
   */
  public static <R> Predicate<R> compile(
      StatisticFilterGroup group, Map<String, StatisticFieldDescriptor<R>> fields) {
    List<StatisticFilterCondition> conditions = group == null ? null : group.conditions();
    if (conditions == null || conditions.isEmpty()) {
      return row -> true;
    }
    boolean isOr = "OR".equalsIgnoreCase(group.logic());
    List<Predicate<R>> compiled =
        conditions.stream().map(condition -> compileCondition(condition, fields)).toList();
    return row -> {
      for (Predicate<R> predicate : compiled) {
        boolean matched = predicate.test(row);
        if (isOr && matched) {
          return true;
        }
        if (!isOr && !matched) {
          return false;
        }
      }
      return !isOr;
    };
  }

  private static <R> Predicate<R> compileCondition(
      StatisticFilterCondition condition, Map<String, StatisticFieldDescriptor<R>> fields) {
    StatisticFieldDescriptor<R> descriptor =
        condition == null || !StringUtils.hasText(condition.fieldKey())
            ? null
            : fields.get(condition.fieldKey());
    if (descriptor == null) {
      return row -> true;
    }
    return row -> evaluate(descriptor, row, condition);
  }

  private static <R> boolean evaluate(
      StatisticFieldDescriptor<R> descriptor, R row, StatisticFilterCondition condition) {
    if (descriptor.overridePredicate() != null) {
      Boolean overridden = descriptor.overridePredicate().apply(row, condition);
      if (overridden != null) {
        return overridden;
      }
    }
    if (descriptor.type() == StatisticFieldType.DATETIME) {
      return matchesDateTime(
          descriptor.dateTimeAccessor().apply(row),
          Objects.toString(condition.operator(), ""),
          trimToNull(condition.value()),
          trimToNull(condition.secondaryValue()));
    }
    List<String> actualValues = normalize(
        descriptor.conditionalValuesAccessor() != null
            ? descriptor.conditionalValuesAccessor().apply(row, condition)
            : descriptor.valuesAccessor().apply(row));
    if (condition.usesLabelGroup()) {
      return matchesSet(actualValues, normalize(condition.values()), condition.operator());
    }
    return matchesText(actualValues, condition);
  }

  // ---- 多值文本操作符（语义与既有看板实现逐字一致） ----

  private static boolean matchesText(List<String> actualValues, StatisticFilterCondition condition) {
    String expected = trimToNull(condition.value());
    return switch (Objects.toString(condition.operator(), "")) {
      case "isEmpty" -> actualValues.stream().allMatch(value -> trimToNull(value) == null);
      case "isNotEmpty" -> actualValues.stream().anyMatch(value -> trimToNull(value) != null);
      case "ne" -> actualValues.stream().noneMatch(value -> equalsIgnoreCase(value, expected));
      case "contains" -> actualValues.stream().anyMatch(value -> containsIgnoreCase(value, expected));
      case "notContains" ->
          actualValues.stream().noneMatch(value -> containsIgnoreCase(value, expected));
      default -> actualValues.stream().anyMatch(value -> equalsIgnoreCase(value, expected));
    };
  }

  private static boolean matchesSet(List<String> actualValues, List<String> expectedValues, String operator) {
    List<String> safeActual = actualValues == null ? List.of() : actualValues;
    List<String> safeExpected = expectedValues == null ? List.of() : expectedValues;
    if (safeExpected.stream().noneMatch(value -> trimToNull(value) != null)) {
      return false;
    }
    if ("partialContainsAny".equals(operator)) {
      return safeActual.stream()
          .filter(value -> trimToNull(value) != null)
          .anyMatch(
              actual ->
                  safeExpected.stream()
                      .filter(value -> trimToNull(value) != null)
                      .anyMatch(expected -> containsIgnoreCase(actual, expected)));
    }
    boolean intersects =
        safeActual.stream()
            .filter(value -> trimToNull(value) != null)
            .anyMatch(
                actual -> safeExpected.stream().anyMatch(expected -> equalsIgnoreCase(actual, expected)));
    boolean containsAll =
        safeExpected.stream()
            .filter(value -> trimToNull(value) != null)
            .allMatch(
                expected -> safeActual.stream().anyMatch(actual -> equalsIgnoreCase(actual, expected)));
    return switch (normalizeSetOperator(operator)) {
      case "notIntersects" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> intersects;
    };
  }

  private static String normalizeSetOperator(String operator) {
    if ("ne".equals(operator)) {
      return "notIntersects";
    }
    if ("eq".equals(operator) || operator == null) {
      return "intersects";
    }
    return operator;
  }

  // ---- 日期时间操作符 ----

  private static boolean matchesDateTime(
      LocalDateTime candidate, String operator, String value, String secondaryValue) {
    if (candidate == null) {
      return "isEmpty".equals(operator);
    }
    if (!StringUtils.hasText(value)) {
      return true;
    }
    LocalDateTime target = parseDateTimeBoundary(value, false);
    if (target == null) {
      return true;
    }
    return switch (operator) {
      case "year" -> candidate.getYear() == target.getYear();
      case "month" -> candidate.getYear() == target.getYear() && candidate.getMonth() == target.getMonth();
      case "day", "at" -> candidate.toLocalDate().equals(target.toLocalDate());
      case "before" -> candidate.isBefore(target);
      case "after" -> candidate.isAfter(target);
      case "between" -> {
        LocalDateTime end = parseDateTimeBoundary(secondaryValue, true);
        yield end == null || (!candidate.isBefore(target) && !candidate.isAfter(end));
      }
      case "isEmpty" -> false;
      case "isNotEmpty" -> true;
      default -> true;
    };
  }

  private static LocalDateTime parseDateTimeBoundary(String value, boolean endOfDay) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(normalized);
    } catch (java.time.format.DateTimeParseException ignored) {
      // 日期选择器常见仅日期值。
    }
    try {
      LocalDate date = LocalDate.parse(normalized);
      return endOfDay ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
    } catch (java.time.format.DateTimeParseException ignored) {
      return null;
    }
  }

  // ---- 基础比较原语 ----

  private static List<String> normalize(List<String> values) {
    return values == null ? List.of() : values;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = trimToNull(left);
    String safeRight = trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private static boolean containsIgnoreCase(String left, String right) {
    String safeLeft = trimToNull(left);
    String safeRight = trimToNull(right);
    return safeLeft != null
        && safeRight != null
        && safeLeft.toLowerCase(Locale.ROOT).contains(safeRight.toLowerCase(Locale.ROOT));
  }
}