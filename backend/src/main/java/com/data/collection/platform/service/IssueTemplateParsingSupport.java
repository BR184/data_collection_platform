package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class IssueTemplateParsingSupport {
  private static final int DEFAULT_RESOLVE_SLA_DAYS = 18;
  private static final Pattern DAY_PATTERN =
      Pattern.compile("(预计解决时间|预计修复时间|预计完成时间|计划解决时间)[^0-9]{0,8}(\\d{1,2})");
  private static final String NOTE_SEPARATOR = "\\R---\\R";

  private IssueTemplateParsingSupport() {}

  static IssueTemplateSnapshot parse(
      String notesText, Map<String, java.util.List<String>> reasonCategoryTokens, java.util.List<String> templateHeaders) {
    boolean hasTemplateReply = IssueRuleSupport.containsToken(notesText, templateHeaders);
    int resolveSlaDays = resolveSlaDays(notesText);
    Set<String> latestCategories = latestReasonCategories(notesText, reasonCategoryTokens);
    String normalizedReasonCategory =
        latestCategories.size() == 1 ? latestCategories.iterator().next() : null;
    return new IssueTemplateSnapshot(
        hasTemplateReply,
        resolveSlaDays,
        latestCategories.size(),
        normalizedReasonCategory);
  }

  private static int resolveSlaDays(String notesText) {
    if (!StringUtils.hasText(notesText)) {
      return DEFAULT_RESOLVE_SLA_DAYS;
    }
    Matcher matcher = DAY_PATTERN.matcher(notesText);
    int candidate = DEFAULT_RESOLVE_SLA_DAYS;
    while (matcher.find()) {
      try {
        int parsed = Integer.parseInt(matcher.group(2));
        if (parsed > 0) {
          candidate = Math.min(candidate, parsed);
        }
      } catch (NumberFormatException ignored) {
      }
    }
    return candidate;
  }

  private static Set<String> latestReasonCategories(
      String notesText, Map<String, java.util.List<String>> reasonCategoryTokens) {
    String latestNote = latestReasonNote(notesText, reasonCategoryTokens);
    if (latestNote == null) {
      return Set.of();
    }
    return matchedReasonCategories(latestNote, reasonCategoryTokens);
  }

  private static String latestReasonNote(
      String notesText, Map<String, java.util.List<String>> reasonCategoryTokens) {
    if (!StringUtils.hasText(notesText)) {
      return null;
    }
    String[] notes = notesText.split(NOTE_SEPARATOR);
    for (int index = notes.length - 1; index >= 0; index--) {
      String candidate = notes[index];
      if (!matchedReasonCategories(candidate, reasonCategoryTokens).isEmpty()) {
        return candidate;
      }
    }
    return null;
  }

  private static Set<String> matchedReasonCategories(
      String text, Map<String, java.util.List<String>> reasonCategoryTokens) {
    Set<String> categories = new LinkedHashSet<>();
    for (Map.Entry<String, java.util.List<String>> entry : reasonCategoryTokens.entrySet()) {
      if (IssueRuleSupport.containsToken(text, entry.getValue())) {
        categories.add(entry.getKey());
      }
    }
    return categories;
  }
}
