package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 议题模块成员的集合语义。
 *
 * <p>{@code issue_fact.module_names} 保留多模块合并后的原始文本；本类只在读取、筛选与候选构建时把
 * 它解释为有序去重的模块成员，不改变事实文本或议题粒度。
 *
 * <p>SQL 侧的同一规则由 {@link IssueModuleMemberSqlSupport} 表达，两者必须逐项一致：同一分隔符集合、
 * 同一空白与空成员处理、同一占位成员排除。Java 的 {@code \s} 与 PostgreSQL 的 {@code [[:space:]]}
 * 都是空格、制表符、换行、回车、换页与纵向制表符，因此首尾去空白统一用这一集合表达，
 * 不得一侧用 {@code String.trim()}／{@code isBlank()}、另一侧用只去空格的 {@code btrim}。
 * 任何一侧改动都必须由 {@code CustomerIssueModuleMemberQueryIntegrationTest} 在真实 PostgreSQL 上复验。
 */
public final class IssueModuleMembers {
  /** 成员分隔符：半角逗号、全角逗号、顿号与 {@code &}，两侧空白属于分隔符。 */
  private static final Pattern MEMBER_SEPARATOR = Pattern.compile("\\s*(?:,|，|、|&)\\s*");

  /** SQL {@code regexp_split_to_table} 使用的同一分隔符定义（POSIX 正则）。 */
  static final String SQL_SEPARATOR_REGEX = "\\s*[,，、&]\\s*";

  /** 首尾空白定义（Java 正则与 POSIX 正则同义），两侧必须使用同一集合。 */
  private static final Pattern MEMBER_TRIM = Pattern.compile("^\\s+|\\s+$");

  /** SQL 侧同义的首尾去空白正则。 */
  static final String SQL_TRIM_REGEX = "^\\s+|\\s+$";

  /** 归一化占位成员前缀，与老平台“未设定…”保持一致，不构成真实模块成员。 */
  static final String PLACEHOLDER_PREFIX = "未设定";

  private IssueModuleMembers() {}

  /**
   * 把模块原始文本解析为真实模块成员。
   *
   * @param rawValue {@code issue_fact.module_names} 原始值，可为空
   * @return 去除首尾空白、丢弃空成员与“未设定”占位后的成员列表，保持出现顺序
   */
  public static List<String> split(String rawValue) {
    if (normalizeToNull(rawValue) == null) {
      return List.of();
    }
    List<String> members = new ArrayList<>();
    for (String part : MEMBER_SEPARATOR.split(rawValue)) {
      String normalized = normalizeToNull(part);
      if (normalized != null && !isPlaceholder(normalized) && !members.contains(normalized)) {
        members.add(normalized);
      }
    }
    return List.copyOf(members);
  }

  /** 成员是否为归一化占位（以“未设定”开头），占位成员等同于缺失。 */
  static boolean isPlaceholder(String normalizedMember) {
    return normalizedMember.startsWith(PLACEHOLDER_PREFIX);
  }

  /** 去掉首尾空白（空格、制表符、换行等），空白串视为缺失。 */
  public static String normalizeToNull(String value) {
    if (value == null) {
      return null;
    }
    String normalized = MEMBER_TRIM.matcher(value).replaceAll("");
    return normalized.isEmpty() ? null : normalized;
  }
}
