package com.data.collection.platform.service;

import cn.hutool.extra.pinyin.PinyinUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class TextQuerySupport {
  private TextQuerySupport() {}

  // 文本查询工具统一生成 normalized、compact、spell、initials 四类搜索形态。
  // Java 生成的结果会写入数据库影子字段，SQL 再基于这些字段做模糊、拼音和首字母搜索。
  public static String normalizeDisplay(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? "" : normalized;
  }

  public static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  public static String normalizeForMatch(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  public static boolean equalsNormalized(String left, String right) {
    String normalizedRight = normalizeForMatch(right);
    return normalizedRight == null || Objects.equals(normalizeForMatch(left), normalizedRight);
  }

  public static boolean containsIgnoreCase(String source, String keyword) {
    String normalizedKeyword = normalizeForMatch(keyword);
    return normalizedKeyword == null
        || (source != null && source.toLowerCase(Locale.ROOT).contains(normalizedKeyword));
  }

  public static boolean containsAbstractSearch(String source, String keyword) {
    String normalizedKeyword = normalizeForMatch(keyword);
    if (normalizedKeyword == null) {
      return true;
    }

    SearchIndex haystack = buildSearchIndex(source);
    SearchIndex needle = buildSearchIndex(keyword);
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(normalizedKeyword);
    candidates.add(needle.compact());
    candidates.add(needle.spell());

    return candidates.stream()
        .filter(StringUtils::hasText)
        .anyMatch(
            candidate ->
                haystack.normalized().contains(candidate)
                    || haystack.compact().contains(candidate)
                    || haystack.spell().contains(candidate)
                    || haystack.initials().contains(candidate));
  }

  public static SearchIndex buildSearchIndex(String value) {
    String normalized = normalizeForMatch(value);
    if (normalized == null) {
      return SearchIndex.EMPTY;
    }
    String compact = normalized.replaceAll("\\s+", "");
    java.util.List<String> tokens = tokenize(value);
    return new SearchIndex(
        normalized,
        compact,
        tokens.stream().map(TextQuerySupport::tokenSpell).reduce("", String::concat),
        tokens.stream().map(TextQuerySupport::tokenInitial).reduce("", String::concat));
  }

  private static java.util.List<String> tokenize(String value) {
    java.util.List<String> tokens = new ArrayList<>();
    if (!StringUtils.hasText(value)) {
      return tokens;
    }
    StringBuilder asciiBuffer = new StringBuilder();
    for (char ch : value.toCharArray()) {
      if (isAsciiWordChar(ch)) {
        asciiBuffer.append(Character.toLowerCase(ch));
        continue;
      }
      flushAsciiToken(tokens, asciiBuffer);
      if (isChineseChar(ch)) {
        tokens.add(String.valueOf(ch));
      }
    }
    flushAsciiToken(tokens, asciiBuffer);
    return tokens;
  }

  private static void flushAsciiToken(java.util.List<String> tokens, StringBuilder asciiBuffer) {
    if (asciiBuffer.isEmpty()) {
      return;
    }
    tokens.add(asciiBuffer.toString());
    asciiBuffer.setLength(0);
  }

  private static boolean isAsciiWordChar(char ch) {
    return (ch >= 'a' && ch <= 'z')
        || (ch >= 'A' && ch <= 'Z')
        || (ch >= '0' && ch <= '9');
  }

  private static boolean isChineseChar(char ch) {
    return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
  }

  private static String tokenSpell(String token) {
    if (!StringUtils.hasText(token)) {
      return "";
    }
    if (token.chars().allMatch(ch -> isAsciiWordChar((char) ch))) {
      return token.toLowerCase(Locale.ROOT);
    }
    return compactPinyin(PinyinUtil.getPinyin(token, ""));
  }

  private static String tokenInitial(String token) {
    if (!StringUtils.hasText(token)) {
      return "";
    }
    if (token.chars().allMatch(ch -> isAsciiWordChar((char) ch))) {
      return token.substring(0, 1).toLowerCase(Locale.ROOT);
    }
    return compactPinyin(PinyinUtil.getFirstLetter(token, ""));
  }

  private static String compactPinyin(String value) {
    String normalized = normalizeForMatch(value);
    return normalized == null ? "" : normalized.replaceAll("\\s+", "");
  }

  public record SearchIndex(String normalized, String compact, String spell, String initials) {
    private static final SearchIndex EMPTY = new SearchIndex("", "", "", "");
  }
}
