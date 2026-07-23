package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SQL 侧的测试状态成员谓词，和 {@link IssueStatusMembers} 保持同一集合语义。 */
final class IssueStatusMemberSqlSupport {
  private static final String NORMALIZED_MEMBER = "lower(btrim(status_member.value))";

  private IssueStatusMemberSqlSupport() {}

  static SqlPredicate matches(String expectedStatus) {
    String expected = TextQuerySupport.trimToNull(expectedStatus);
    return matchesAny(expected == null ? List.of() : List.of(expected));
  }

  static SqlPredicate notMatches(String expectedStatus) {
    String expected = TextQuerySupport.trimToNull(expectedStatus);
    return expected == null ? falsePredicate() : negate(matchesAny(List.of(expected)));
  }

  static SqlPredicate matchesAny(Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(memberMatches(value, args)));
    }
    return new SqlPredicate("(" + String.join(" or ", predicates) + ")", args);
  }

  static SqlPredicate notMatchesAny(Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    return expected.isEmpty() ? falsePredicate() : negate(matchesAny(expected));
  }

  static SqlPredicate matchesAll(Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(memberMatches(value, args)));
    }
    return new SqlPredicate("(" + String.join(" and ", predicates) + ")", args);
  }

  static SqlPredicate notMatchesAll(Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    return expected.isEmpty() ? falsePredicate() : negate(matchesAll(expected));
  }

  static SqlPredicate matchesPartialAny(Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    if (expected.isEmpty()) {
      return falsePredicate();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (String value : expected) {
      predicates.add(memberExists(NORMALIZED_MEMBER + " like ? escape '\\'"));
      args.add(LabelGroupFilterOperatorSupport.likeContainsPattern(value));
    }
    return new SqlPredicate("(" + String.join(" or ", predicates) + ")", args);
  }

  static SqlPredicate isEmpty() {
    return new SqlPredicate("not " + memberExists("1 = 1"), List.of());
  }

  static SqlPredicate isNotEmpty() {
    return new SqlPredicate(memberExists("1 = 1"), List.of());
  }

  private static String memberExists(String predicate) {
    return """
        exists (
          select 1
            from regexp_split_to_table(coalesce(bug_status, ''), '[、，,&]') as status_member(value)
           where nullif(btrim(status_member.value), '') is not null
             and (""" + predicate + "))";
  }

  private static String memberMatches(String expectedStatus, List<Object> args) {
    if (IssueStatusMembers.isLegacyFixedSelection(expectedStatus)) {
      List<String> predicates = new ArrayList<>();
      for (String token : List.of("已修复", "待合并", "未更新")) {
        predicates.add(NORMALIZED_MEMBER + " like ?");
        args.add("%" + token.toLowerCase(Locale.ROOT) + "%");
      }
      return "(" + String.join(" or ", predicates) + ")";
    }
    args.add(expectedStatus.toLowerCase(Locale.ROOT));
    return NORMALIZED_MEMBER + " = ?";
  }

  private static SqlPredicate negate(SqlPredicate predicate) {
    return new SqlPredicate("not (" + predicate.predicate() + ")", predicate.args());
  }

  private static SqlPredicate falsePredicate() {
    return new SqlPredicate("1 = 0", List.of());
  }

  private static List<String> normalizeExpectedStatuses(Collection<String> expectedStatuses) {
    if (expectedStatuses == null || expectedStatuses.isEmpty()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String value : expectedStatuses) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null && seen.add(normalized.toLowerCase(Locale.ROOT))) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }
}
