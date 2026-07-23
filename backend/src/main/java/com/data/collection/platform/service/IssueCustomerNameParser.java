package com.data.collection.platform.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** GitLab 议题 description 与标题中的客户名称解析规则。 */
final class IssueCustomerNameParser {
  private static final Pattern CUSTOMER_NAME_LINE =
      Pattern.compile("^\\s*(?:#{1,6}\\s*)?客户名称\\s*[：:]\\s*(.+?)\\s*$");
  private static final Pattern CUSTOMER_SEPARATOR = Pattern.compile("[/／、,，;；]+");
  private static final Pattern DATE_SUFFIX =
      Pattern.compile("^\\d{4}\\s*(?:年|[-./，、])\\s*\\d{1,2}\\s*(?:月|[-./，、])\\s*\\d{1,2}\\s*(?:日)?$");

  private IssueCustomerNameParser() {}

  /**
   * 解析规范客户成员。description 中明确的“客户名称”优先；仅缺失时使用标题末尾的双破折号后缀。
   *
   * @param description GitLab issue description
   * @param title GitLab issue 标题
   * @param aliases 精确别名到规范名称的映射
   * @return 保持来源顺序且去重后的规范客户名称
   */
  static List<String> parse(String description, String title, Map<String, String> aliases) {
    List<String> descriptionMembers = parseDescriptionMembers(description);
    List<String> sourceMembers = descriptionMembers.isEmpty() ? parseTitleSuffix(title) : descriptionMembers;
    if (sourceMembers.isEmpty()) {
      return List.of();
    }

    Map<String, String> normalizedAliases = normalizedAliases(aliases);
    LinkedHashMap<String, String> canonicalMembers = new LinkedHashMap<>();
    for (String sourceMember : sourceMembers) {
      String normalized = TextQuerySupport.trimToNull(sourceMember);
      if (normalized == null) {
        continue;
      }
      String canonical = normalizedAliases.getOrDefault(normalizeKey(normalized), normalized);
      String canonicalKey = normalizeKey(canonical);
      if (!canonicalKey.isEmpty()) {
        canonicalMembers.putIfAbsent(canonicalKey, canonical);
      }
    }
    return List.copyOf(canonicalMembers.values());
  }

  private static List<String> parseDescriptionMembers(String description) {
    String normalizedDescription = TextQuerySupport.trimToNull(description);
    if (normalizedDescription == null) {
      return List.of();
    }
    for (String rawLine : normalizedDescription.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      String line = stripMarkdownDecoration(rawLine);
      java.util.regex.Matcher matcher = CUSTOMER_NAME_LINE.matcher(line);
      if (matcher.matches()) {
        return splitMembers(matcher.group(1));
      }
    }
    return List.of();
  }

  private static List<String> parseTitleSuffix(String title) {
    String normalizedTitle = TextQuerySupport.trimToNull(title);
    if (normalizedTitle == null) {
      return List.of();
    }
    int delimiterIndex = normalizedTitle.lastIndexOf("——");
    if (delimiterIndex < 0 || delimiterIndex + 2 >= normalizedTitle.length()) {
      return List.of();
    }
    String suffix = TextQuerySupport.trimToNull(normalizedTitle.substring(delimiterIndex + 2));
    if (suffix == null || DATE_SUFFIX.matcher(suffix).matches()) {
      return List.of();
    }
    return splitMembers(suffix);
  }

  private static List<String> splitMembers(String rawValue) {
    String normalized = TextQuerySupport.trimToNull(rawValue);
    if (normalized == null) {
      return List.of();
    }
    LinkedHashSet<String> members = new LinkedHashSet<>();
    for (String member : CUSTOMER_SEPARATOR.split(normalized)) {
      String value = TextQuerySupport.trimToNull(stripMarkdownDecoration(member));
      if (value != null) {
        members.add(value);
      }
    }
    return List.copyOf(members);
  }

  private static Map<String, String> normalizedAliases(Map<String, String> aliases) {
    if (aliases == null || aliases.isEmpty()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : aliases.entrySet()) {
      String alias = TextQuerySupport.trimToNull(entry.getKey());
      String canonical = TextQuerySupport.trimToNull(entry.getValue());
      if (alias != null && canonical != null) {
        result.putIfAbsent(normalizeKey(alias), canonical);
      }
    }
    return Map.copyOf(result);
  }

  private static String stripMarkdownDecoration(String value) {
    String normalized = value == null ? "" : value.trim();
    normalized = normalized.replace("**", "").replace("__", "").replace("`", "").trim();
    if (normalized.startsWith("- ") || normalized.startsWith("* ")) {
      normalized = normalized.substring(2).trim();
    }
    return normalized;
  }

  private static String normalizeKey(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
