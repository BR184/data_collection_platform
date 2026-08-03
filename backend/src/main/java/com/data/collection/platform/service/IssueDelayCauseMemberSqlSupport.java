package com.data.collection.platform.service;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SQL 侧的延期原因成员谓词，和 {@link IssueDelayCauseMembers} 保持同一集合语义。 */
final class IssueDelayCauseMemberSqlSupport {
  private static final String MEMBER = "lower(btrim(delay_cause_member.value))";

  private IssueDelayCauseMemberSqlSupport() {}

  static SqlPredicate condition(String column, StatisticFilterCondition condition) {
    if (condition.usesLabelGroup()) {
      return switch (condition.operator()) {
        case "partialContainsAny" -> matchesPartialAny(column, condition.values());
        case "notIntersects" -> notMatchesAny(column, condition.values());
        case "containsAll" -> matchesAll(column, condition.values());
        case "notContainsAll" -> notMatchesAll(column, condition.values());
        default -> matchesAny(column, condition.values());
      };
    }
    return switch (condition.operator()) {
      case "eq" -> matches(column, condition.value());
      case "ne" -> notMatches(column, condition.value());
      case "contains" -> matchesPartialAny(column, List.of(condition.value()));
      case "notContains" -> negate(matchesPartialAny(column, List.of(condition.value())));
      case "isEmpty" -> isEmpty(column);
      case "isNotEmpty" -> isNotEmpty(column);
      default -> falsePredicate();
    };
  }

  static SqlPredicate matches(String column, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null ? falsePredicate() : matchesAny(column, List.of(expected));
  }

  static SqlPredicate notMatches(String column, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null ? falsePredicate() : negate(matchesAny(column, List.of(expected)));
  }

  private static SqlPredicate matchesAny(String column, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(column, MEMBER + " = ?"));
      args.add(value);
    }
    return new SqlPredicate("(" + String.join(" or ", predicates) + ")", args);
  }

  private static SqlPredicate notMatchesAny(String column, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    return expected.isEmpty() ? falsePredicate() : negate(matchesAny(column, expected));
  }

  private static SqlPredicate matchesAll(String column, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(column, MEMBER + " = ?"));
      args.add(value);
    }
    return new SqlPredicate("(" + String.join(" and ", predicates) + ")", args);
  }

  private static SqlPredicate notMatchesAll(String column, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    return expected.isEmpty() ? falsePredicate() : negate(matchesAll(column, expected));
  }

  private static SqlPredicate matchesPartialAny(String column, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(column, MEMBER + " like ? escape '\\'"));
      args.add("%" + escapeLike(value) + "%");
    }
    return new SqlPredicate("(" + String.join(" or ", predicates) + ")", args);
  }

  private static SqlPredicate isEmpty(String column) {
    return new SqlPredicate("not " + memberExists(column, "1 = 1"), List.of());
  }

  private static SqlPredicate isNotEmpty(String column) {
    return new SqlPredicate(memberExists(column, "1 = 1"), List.of());
  }

  private static String memberExists(String column, String predicate) {
    return """
        exists (
          select 1
            from regexp_split_to_table(coalesce(%s, ''), '[、，,&]') as delay_cause_member(value)
           where nullif(btrim(delay_cause_member.value), '') is not null
             and (%s))
        """.formatted(column, predicate);
  }

  private static SqlPredicate negate(SqlPredicate predicate) {
    return new SqlPredicate("not (" + predicate.predicate() + ")", predicate.args());
  }

  private static SqlPredicate falsePredicate() {
    return new SqlPredicate("1 = 0", List.of());
  }

  private static List<String> normalizeExpectedValues(Collection<String> expectedValues) {
    if (expectedValues == null || expectedValues.isEmpty()) {
      return List.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (String value : expectedValues) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null) {
        result.add(normalized.toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(result);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
