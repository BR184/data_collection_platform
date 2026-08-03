package com.data.collection.platform.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 延期原因的成员集合语义。
 *
 * <p>事实层保留老平台的 {@code &} 组合文本；读取、筛选和统计时按成员解释该文本，避免
 * 组合值被当成一个不可筛选的整体。</p>
 */
public final class IssueDelayCauseMembers {
  private static final Pattern MEMBER_SEPARATOR = Pattern.compile("[、，,&]");
  private static final Pattern LABEL_TEXT_SEPARATOR = Pattern.compile("[,，、;；\\s]+");
  private static final String DELAY_CAUSE_PREFIX = "延期原因";
  private static final List<String> KNOWN_CAUSES = List.of(
      "技术卡点", "方案卡点", "资源卡点", "数据异常", "算法问题", "机制问题", "计算效率");
  private static final Set<String> KNOWN_CAUSE_SET = Set.copyOf(KNOWN_CAUSES);

  private IssueDelayCauseMembers() {}

  /**
   * 返回老平台定义的七类延期原因，顺序与老平台枚举一致。
   *
   * @return 不可变的延期原因列表
   */
  public static List<String> values() {
    return KNOWN_CAUSES;
  }

  /**
   * 解析事实层延期原因文本中的成员，保留出现顺序并去重。
   *
   * <p>成员解析只负责按事实字段的分隔符拆分非空成员，不在读取边界丢弃历史非规范值。需要严格
   * 使用老平台七类原因时，调用 {@link #collectKnownCauses(Collection)} 或 {@link #fromLabels(Collection)}。
   * “未设定类别”同样可以作为客户问题页面的事实回退成员参与筛选。</p>
   *
   * @param rawValue 事实层延期原因文本，可为空
   * @return 按出现顺序去重后的延期原因成员
   */
  public static List<String> parse(String rawValue) {
    String normalized = TextQuerySupport.trimToNull(rawValue);
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
   * 从原始 GitLab 标签中按标签出现顺序提取合法延期原因。
   *
   * <p>支持老平台的独立原因标签和“延期原因：原因”标签；其它标签及“申请延期”不会被当作
   * 延期原因。</p>
   *
   * @param labels 议题完整标签列表，可为空
   * @return 去重后的七类延期原因
   */
  public static List<String> fromLabels(Collection<String> labels) {
    if (labels == null || labels.isEmpty()) {
      return List.of();
    }
    Set<String> causes = new LinkedHashSet<>();
    for (String rawLabel : labels) {
      String label = TextQuerySupport.trimToNull(rawLabel);
      if (label == null) {
        continue;
      }
      addKnownCauses(causes, parse(label));
      String prefixedValue = prefixedValue(label);
      if (prefixedValue != null) {
        addKnownCauses(causes, parse(prefixedValue));
      }
    }
    return List.copyOf(causes);
  }

  /**
   * 从事实层延期原因值集合中汇总显示成员，保留首次出现顺序。
   *
   * @param rawValues 多条事实值，可为空
   * @return 去重后的合法延期原因和显示回退成员
   */
  public static List<String> collectMembers(Collection<String> rawValues) {
    if (rawValues == null || rawValues.isEmpty()) {
      return List.of();
    }
    Set<String> members = new LinkedHashSet<>();
    for (String rawValue : rawValues) {
      members.addAll(parse(rawValue));
    }
    return List.copyOf(members);
  }

  /**
   * 从事实值和标签文本中汇总七类延期原因，供统计维度使用。
   *
   * @param rawValues 已保存的延期原因字段，可为空
   * @return 去重后的七类延期原因
   */
  public static List<String> collectKnownCauses(Collection<String> rawValues) {
    if (rawValues == null || rawValues.isEmpty()) {
      return List.of();
    }
    Set<String> causes = new LinkedHashSet<>();
    for (String rawValue : rawValues) {
      addKnownCauses(causes, parse(rawValue));
    }
    return List.copyOf(causes);
  }

  /**
   * 判断普通等值筛选是否命中一个延期原因成员。
   *
   * @param rawValue 事实层延期原因文本
   * @param expectedValue 筛选值
   * @return 是否命中；筛选值为空时返回 {@code true}
   */
  public static boolean matchesSelection(String rawValue, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null || matchesParsedValues(parse(rawValue), expected);
  }

  /**
   * 从以逗号或空白连接的 GitLab 标签文本中提取七类延期原因。
   *
   * @param labelsText 标签文本，可为空
   * @return 去重后的七类延期原因
   */
  public static List<String> fromLabelText(String labelsText) {
    String normalized = TextQuerySupport.trimToNull(labelsText);
    if (normalized == null) {
      return List.of();
    }
    return fromLabels(List.of(LABEL_TEXT_SEPARATOR.split(normalized)));
  }

  /**
   * 按老平台字段格式将原始标签归一化为延期原因组合文本。
   *
   * @param labels 议题完整标签列表，可为空
   * @return 使用 {@code &} 连接的原因文本；没有合法原因时返回 {@code null}
   */
  public static String normalizeLabels(Collection<String> labels) {
    List<String> causes = fromLabels(labels);
    return causes.isEmpty() ? null : String.join("&", causes);
  }

  /**
   * 判断普通延期原因筛选是否命中成员。
   *
   * @param rawValue 事实层延期原因文本
   * @param operator 筛选操作符
   * @param expectedValue 筛选值
   * @return 是否命中
   */
  public static boolean matchesFilter(String rawValue, String operator, String expectedValue) {
    return matchesFilter(parse(rawValue), operator, expectedValue);
  }

  /**
   * 按已解析的延期原因成员集合匹配普通筛选操作符。
   *
   * @param members 已解析的延期原因成员，可为空
   * @param operator 筛选操作符
   * @param expectedValue 筛选值
   * @return 是否命中
   */
  public static boolean matchesFilter(
      Collection<String> members, String operator, String expectedValue) {
    List<String> normalizedMembers = normalizeMembers(members);
    String normalizedOperator = TextQuerySupport.trimToNull(operator);
    String expected = TextQuerySupport.trimToNull(expectedValue);
    if (requiresExpectedValue(normalizedOperator) && expected == null) {
      return true;
    }
    return switch (normalizedOperator == null ? "" : normalizedOperator) {
      case "eq" -> matchesSelectionMembers(normalizedMembers, expected);
      case "ne" -> !matchesSelectionMembers(normalizedMembers, expected);
      case "contains" -> matchesPartialMembers(normalizedMembers, expected);
      case "notContains" -> !matchesPartialMembers(normalizedMembers, expected);
      case "isEmpty" -> normalizedMembers.isEmpty();
      case "isNotEmpty" -> !normalizedMembers.isEmpty();
      default -> true;
    };
  }

  /**
   * 判断延期原因成员与标签组集合关系。
   *
   * @param rawValue 事实层延期原因文本
   * @param operator 标签组操作符
   * @param expectedValues 标签组展开后的成员
   * @return 是否命中
   */
  public static boolean matchesLabelGroup(
      String rawValue, String operator, Collection<String> expectedValues) {
    return matchesLabelGroup(parse(rawValue), operator, expectedValues);
  }

  /**
   * 按已解析的延期原因成员集合匹配标签组操作符。
   *
   * @param members 已解析的延期原因成员，可为空
   * @param operator 标签组操作符
   * @param expectedValues 标签组展开后的成员
   * @return 是否命中
   */
  public static boolean matchesLabelGroup(
      Collection<String> members, String operator, Collection<String> expectedValues) {
    List<String> expected = normalizeExpectedValues(expectedValues);
    if (expected.isEmpty()) {
      return false;
    }
    List<String> actual = normalizeMembers(members);
    boolean intersects = expected.stream().anyMatch(value -> matchesParsedValues(actual, value));
    boolean containsAll = expected.stream().allMatch(value -> matchesParsedValues(actual, value));
    return switch (operator == null ? "" : operator) {
      case "partialContainsAny" -> actual.stream()
          .anyMatch(member -> expected.stream().anyMatch(value -> containsIgnoreCase(member, value)));
      case "notIntersects", "ne" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> intersects;
    };
  }

  private static boolean matchesParsedValues(Collection<String> actualValues, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected != null
        && actualValues.stream().anyMatch(actual -> actual.equalsIgnoreCase(expected));
  }

  private static boolean matchesPartialMembers(Collection<String> members, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null
        || members.stream().anyMatch(member -> containsIgnoreCase(member, expected));
  }

  private static boolean matchesSelectionMembers(
      Collection<String> members, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null
        || members.stream().anyMatch(member -> member.equalsIgnoreCase(expected));
  }

  private static List<String> normalizeMembers(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    Set<String> members = new LinkedHashSet<>();
    for (String value : values) {
      members.addAll(parse(value));
    }
    return List.copyOf(members);
  }

  private static List<String> normalizeExpectedValues(Collection<String> expectedValues) {
    if (expectedValues == null || expectedValues.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : expectedValues) {
      String member = TextQuerySupport.trimToNull(value);
      if (member != null) {
        normalized.add(member.toLowerCase(Locale.ROOT));
      }
    }
    return List.copyOf(normalized);
  }

  private static void addKnownCauses(Set<String> target, Collection<String> candidates) {
    for (String candidate : candidates) {
      if (KNOWN_CAUSE_SET.contains(candidate)) {
        target.add(candidate);
      }
    }
  }

  private static String prefixedValue(String label) {
    int chineseColon = label.indexOf('：');
    int asciiColon = label.indexOf(':');
    int separatorIndex = chineseColon < 0
        ? asciiColon
        : asciiColon < 0 ? chineseColon : Math.min(chineseColon, asciiColon);
    if (separatorIndex <= 0
        || !DELAY_CAUSE_PREFIX.equals(label.substring(0, separatorIndex).trim())) {
      return null;
    }
    return label.substring(separatorIndex + 1);
  }

  private static boolean containsIgnoreCase(String value, String expected) {
    return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
  }

  private static boolean requiresExpectedValue(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }
}
