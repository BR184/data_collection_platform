package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 议题测试状态的成员集合语义。
 *
 * <p>{@code issue_fact.bug_status} 保留老平台合并后的原始文本；本类只在读取、筛选和候选构建时
 * 将其解释为有序且去重的状态成员，不改变事实文本或议题粒度。</p>
 */
public final class IssueStatusMembers {
  private static final Pattern MEMBER_SEPARATOR = Pattern.compile("[、，,&]");
  private static final String LEGACY_FIXED_STATUS = "已修复";
  private static final List<String> LEGACY_FIXED_STATUS_TOKENS =
      List.of("已修复", "待合并", "未更新");

  private IssueStatusMembers() {}

  /**
   * 将一个事实字段值解析为有序、去重且非空的状态成员。
   *
   * <p>仅识别老平台和事实层使用的顿号、逗号及 {@code &} 分隔符；斜杠是状态名称的一部分，
   * 因此 {@code 已修复/完成} 始终只返回一个成员。</p>
   *
   * @param rawStatus 事实层保存的原始测试状态文本，可为空
   * @return 不可变的状态成员列表
   */
  public static List<String> parse(String rawStatus) {
    String normalized = TextQuerySupport.trimToNull(rawStatus);
    if (normalized == null) {
      return List.of();
    }
    Set<String> members = new LinkedHashSet<>();
    for (String value : MEMBER_SEPARATOR.split(normalized)) {
      String member = TextQuerySupport.trimToNull(value);
      if (member != null) {
        members.add(member);
      }
    }
    return List.copyOf(members);
  }

  /**
   * 汇总多个事实字段值中的状态成员，保留数据源首次出现顺序。
   *
   * @param rawStatuses 多条议题记录的原始测试状态文本
   * @return 不可变、去重后的状态候选成员
   */
  public static List<String> collectMembers(Collection<String> rawStatuses) {
    if (rawStatuses == null || rawStatuses.isEmpty()) {
      return List.of();
    }
    Set<String> members = new LinkedHashSet<>();
    for (String rawStatus : rawStatuses) {
      members.addAll(parse(rawStatus));
    }
    return List.copyOf(members);
  }

  /**
   * 判断一个状态集合是否命中普通筛选的指定状态。
   *
   * <p>空筛选值表示不限制。选择“已修复”时保留老平台的修复状态口径，同时匹配包含
   * “已修复”、"待合并"或"未更新"的状态成员。</p>
   *
   * @param rawStatus 原始测试状态文本
   * @param expectedStatus 普通筛选值
   * @return 是否命中；筛选值为空时返回 {@code true}
   */
  public static boolean matchesSelection(String rawStatus, String expectedStatus) {
    String expected = TextQuerySupport.trimToNull(expectedStatus);
    if (expected == null) {
      return true;
    }
    return parse(rawStatus).stream().anyMatch(member -> matchesMember(member, expected));
  }

  /**
   * 按普通筛选操作符匹配状态成员集合。
   *
   * @param rawStatus 原始测试状态文本
   * @param operator 筛选操作符
   * @param expectedStatus 筛选值
   * @return 当前记录是否满足筛选条件
   */
  public static boolean matchesFilter(String rawStatus, String operator, String expectedStatus) {
    String normalizedOperator = TextQuerySupport.trimToNull(operator);
    String expected = TextQuerySupport.trimToNull(expectedStatus);
    if (requiresExpectedStatus(normalizedOperator) && expected == null) {
      return true;
    }
    return switch (normalizedOperator == null ? "" : normalizedOperator) {
      case "eq" -> matchesSelection(rawStatus, expected);
      case "ne" -> !matchesSelection(rawStatus, expected);
      case "contains" -> matchesPartial(rawStatus, expected);
      case "notContains" -> !matchesPartial(rawStatus, expected);
      case "isEmpty" -> parse(rawStatus).isEmpty();
      case "isNotEmpty" -> !parse(rawStatus).isEmpty();
      default -> true;
    };
  }

  /**
   * 按标签组集合操作符匹配状态成员集合。
   *
   * @param rawStatus 原始测试状态文本
   * @param operator 标签组集合操作符
   * @param expectedStatuses 标签组展开后的状态成员
   * @return 当前记录是否满足标签组条件
   */
  public static boolean matchesLabelGroup(
      String rawStatus, String operator, Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    if (expected.isEmpty()) {
      return false;
    }
    boolean intersects = expected.stream().anyMatch(value -> matchesSelection(rawStatus, value));
    boolean containsAll = expected.stream().allMatch(value -> matchesSelection(rawStatus, value));
    return switch (operator == null ? "" : operator) {
      case "partialContainsAny" -> matchesPartialAny(rawStatus, expected);
      case "notIntersects", "ne" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> intersects;
    };
  }

  static boolean isLegacyFixedSelection(String expectedStatus) {
    return LEGACY_FIXED_STATUS.equalsIgnoreCase(TextQuerySupport.trimToNull(expectedStatus));
  }

  private static boolean matchesPartial(String rawStatus, String expectedStatus) {
    String expected = TextQuerySupport.trimToNull(expectedStatus);
    return expected == null || matchesPartialAny(rawStatus, List.of(expected));
  }

  private static boolean matchesPartialAny(String rawStatus, Collection<String> expectedStatuses) {
    List<String> expected = normalizeExpectedStatuses(expectedStatuses);
    return parse(rawStatus).stream()
        .anyMatch(
            member ->
                expected.stream()
                    .anyMatch(expectedStatus -> containsIgnoreCase(member, expectedStatus)));
  }

  private static List<String> normalizeExpectedStatuses(Collection<String> expectedStatuses) {
    if (expectedStatuses == null || expectedStatuses.isEmpty()) {
      return List.of();
    }
    List<String> expected = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String value : expectedStatuses) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null && seen.add(normalized.toLowerCase(Locale.ROOT))) {
        expected.add(normalized);
      }
    }
    return List.copyOf(expected);
  }

  private static boolean matchesMember(String member, String expectedStatus) {
    if (isLegacyFixedSelection(expectedStatus)) {
      return LEGACY_FIXED_STATUS_TOKENS.stream()
          .anyMatch(token -> containsIgnoreCase(member, token));
    }
    return member.equalsIgnoreCase(expectedStatus);
  }

  private static boolean containsIgnoreCase(String value, String expected) {
    String normalizedValue = TextQuerySupport.trimToNull(value);
    String normalizedExpected = TextQuerySupport.trimToNull(expected);
    return normalizedValue != null
        && normalizedExpected != null
        && normalizedValue.toLowerCase(Locale.ROOT).contains(normalizedExpected.toLowerCase(Locale.ROOT));
  }

  private static boolean requiresExpectedStatus(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }
}
