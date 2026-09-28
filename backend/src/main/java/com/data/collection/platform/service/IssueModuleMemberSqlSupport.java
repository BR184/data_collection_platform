package com.data.collection.platform.service;

import java.util.List;
import java.util.Locale;

/**
 * SQL 侧的模块成员谓词，和 {@link IssueModuleMembers} 保持同一集合语义。
 *
 * <p>成员拆分使用同一分隔符集合（半角逗号、全角逗号、顿号与 {@code &}，含两侧空白），丢弃空白成员，
 * 并把以“未设定”开头的归一化占位视为不构成真实成员。精确匹配使用等值比较，{@code %} 与 {@code _}
 * 因此始终是模块名本身的一部分，不会退化为通配符。
 *
 * <p>首尾空白用与 Java 同义的 {@code \s}（POSIX 的 {@code [[:space:]]}）表达：只去空格的
 * {@code btrim(x)} 会留下制表符与换行，使同一成员串在两侧得到不同的成员集合。
 */
final class IssueModuleMemberSqlSupport {
  private static final String TRIMMED_MEMBER =
      "regexp_replace(module_member.value, '" + IssueModuleMembers.SQL_TRIM_REGEX + "', '', 'g')";
  private static final String NORMALIZED_MEMBER = "lower(" + TRIMMED_MEMBER + ")";

  private IssueModuleMemberSqlSupport() {}

  /** 存在等于给定名称的真实模块成员；空值不匹配任何议题。 */
  static SqlPredicate matches(String expectedModule) {
    String expected = IssueModuleMembers.normalizeToNull(expectedModule);
    if (expected == null) {
      return new SqlPredicate("1 = 0", List.of());
    }
    return new SqlPredicate(
        memberExists(NORMALIZED_MEMBER + " = ?"), List.of(expected.toLowerCase(Locale.ROOT)));
  }

  /** 没有任何真实模块成员（全空、仅空白或仅“未设定”占位）。 */
  static SqlPredicate isEmpty() {
    return new SqlPredicate("not " + memberExists("true"), List.of());
  }

  private static String memberExists(String predicate) {
    return """
        exists (
          select 1
            from regexp_split_to_table(
              coalesce(issue_fact.module_names, ''), '%s') as module_member(value)
           where %s <> ''
             and %s not like '%s%%'
             and (%s))"""
        .formatted(
            IssueModuleMembers.SQL_SEPARATOR_REGEX,
            TRIMMED_MEMBER,
            TRIMMED_MEMBER,
            IssueModuleMembers.PLACEHOLDER_PREFIX,
            predicate);
  }
}
