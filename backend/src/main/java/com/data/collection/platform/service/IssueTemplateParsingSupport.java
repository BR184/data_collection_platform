package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
  private static final Pattern PLAN_DATE_PATTERN =
      Pattern.compile(
          "(计划解决时间|预计解决时间|预计修复时间|预计完成时间)[\\s:：：]*([^\\r\\n#]*)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE_VALUE_PATTERN =
      Pattern.compile(
          "(\\d{4})\\s*(?:年|[.,，、/·`])\\s*(\\d{1,2})\\s*(?:月|[.,，、/·`])\\s*(\\d{1,2})\\s*(?:日)?");
  private static final String NOTE_SEPARATOR = "\\R---\\R";

  private IssueTemplateParsingSupport() {}

  static IssueTemplateSnapshot parse(
      String notesText, Map<String, java.util.List<String>> reasonCategoryTokens, java.util.List<String> templateHeaders) {
    boolean hasTemplateReply = IssueRuleSupport.containsToken(notesText, templateHeaders);
    int resolveSlaDays = resolveSlaDays(notesText);
    LocalDateTime planSolutionTime = planSolutionTime(notesText);
    Set<String> latestCategories = latestReasonCategories(notesText, reasonCategoryTokens);
    String normalizedReasonCategory =
        latestCategories.size() == 1 ? latestCategories.iterator().next() : null;
    return new IssueTemplateSnapshot(
        hasTemplateReply,
        resolveSlaDays,
        planSolutionTime,
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

  private static LocalDateTime planSolutionTime(String notesText) {
    if (!StringUtils.hasText(notesText)) {
      return null;
    }
    String[] notes = notesText.split(NOTE_SEPARATOR);
    for (int index = notes.length - 1; index >= 0; index--) {
      LocalDateTime parsed = planSolutionTimeInNote(notes[index]);
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private static LocalDateTime planSolutionTimeInNote(String note) {
    if (!StringUtils.hasText(note)) {
      return null;
    }
    Matcher sectionMatcher = PLAN_DATE_PATTERN.matcher(note);
    while (sectionMatcher.find()) {
      LocalDateTime parsed = parseDateValue(sectionMatcher.group(2));
      if (parsed != null) {
        return parsed;
      }
      int nextLineStart = sectionMatcher.end();
      int nextLineEnd = note.indexOf('\n', nextLineStart);
      while (nextLineStart >= 0 && nextLineStart < note.length()) {
        if (nextLineEnd < 0) {
          nextLineEnd = note.length();
        }
        String line = note.substring(nextLineStart, nextLineEnd).trim();
        if (!line.isEmpty()) {
          if (line.startsWith("##")) {
            break;
          }
          parsed = parseDateValue(line);
          if (parsed != null) {
            return parsed;
          }
        }
        if (nextLineEnd >= note.length()) {
          break;
        }
        nextLineStart = nextLineEnd + 1;
        nextLineEnd = note.indexOf('\n', nextLineStart);
      }
    }
    return null;
  }

  private static LocalDateTime parseDateValue(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    Matcher matcher = DATE_VALUE_PATTERN.matcher(value);
    if (!matcher.find()) {
      return null;
    }
    try {
      int year = Integer.parseInt(matcher.group(1));
      int month = Integer.parseInt(matcher.group(2));
      int day = Integer.parseInt(matcher.group(3));
      if (year < 2020 || year > 2040) {
        return null;
      }
      return LocalDate.of(year, month, day).atStartOfDay();
    } catch (RuntimeException ignored) {
      return null;
    }
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
