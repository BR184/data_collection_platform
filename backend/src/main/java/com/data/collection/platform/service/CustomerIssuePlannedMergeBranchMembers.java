package com.data.collection.platform.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** CC_PRODUCT 计划合并版本分支的成员集合语义。 */
public final class CustomerIssuePlannedMergeBranchMembers {
  private static final String MEMBER_DELIMITER_REGEX = "[&,，、]";
  private static final Pattern MEMBER_DELIMITER_PATTERN = Pattern.compile(MEMBER_DELIMITER_REGEX);

  private CustomerIssuePlannedMergeBranchMembers() {}

  /**
   * 按来源文本中实际使用的列表分隔符解析分支成员，保留顺序并去重。
   *
   * <p>成员分隔符包括 {@code &}、半角逗号、全角逗号和顿号；斜杠、下划线、连字符、点号和冒号保留为分支名的一部分。
   *
   * @param rawValue 事实层计划合并版本分支文本
   * @return 不可变的非空成员列表
   */
  public static List<String> parse(String rawValue) {
    String normalized = TextQuerySupport.trimToNull(rawValue);
    if (normalized == null) {
      return List.of();
    }
    Set<String> members = new LinkedHashSet<>();
    for (String value : MEMBER_DELIMITER_PATTERN.split(normalized)) {
      String member = TextQuerySupport.trimToNull(value);
      if (member != null) {
        members.add(member);
      }
    }
    return List.copyOf(members);
  }

  /**
   * 汇总多条事实中的分支成员，保留首次出现顺序。
   *
   * @param rawValues 多条计划合并版本分支文本
   * @return 不可变的去重成员列表
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
   * 按完整成员、不区分大小写地匹配计划合并版本分支。
   *
   * @param rawValue 事实层计划合并版本分支文本
   * @param expectedValue 用户选择的分支成员
   * @return 筛选值为空或存在完整成员时返回 {@code true}
   */
  public static boolean matchesSelection(String rawValue, String expectedValue) {
    String expected = TextQuerySupport.trimToNull(expectedValue);
    return expected == null
        || parse(rawValue).stream().anyMatch(member -> member.equalsIgnoreCase(expected));
  }

  static String delimiterRegex() {
    return MEMBER_DELIMITER_REGEX;
  }
}
