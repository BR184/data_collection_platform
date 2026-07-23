package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 GitLab 聚合备注中读取最新“问题调研情况说明”响应模板。 */
final class IssueResponseTemplateParser {
  private static final String NOTE_SEPARATOR = "\\R---\\R";
  private static final List<String> TEMPLATE_HEADERS = List.of("# 问题调研情况说明", "问题调研情况说明");
  private static final List<String> PLAN_RESOLUTION_HEADERS = List.of("计划解决时间");
  private static final List<String> PLAN_MERGE_BRANCH_HEADERS = List.of("计划合并的版本分支", "计划合并版本分支");
  private static final Pattern DATE_VALUE =
      Pattern.compile(
          "(\\d{4})\\s*(?:年|[-./，、])\\s*(\\d{1,2})\\s*(?:月|[-./，、])\\s*(\\d{1,2})\\s*(?:日)?");

  private IssueResponseTemplateParser() {}

  /**
   * notesText 由事实源按备注创建时间倒序聚合。本方法只解析第一份匹配响应模板，不从旧模板补齐字段。
   *
   * @param notesText GitLab issue 备注聚合文本
   * @return 最新模板中的计划字段；不存在模板时返回空快照
   */
  static IssueResponseTemplate parse(String notesText) {
    String latestTemplate = latestTemplate(notesText);
    if (latestTemplate == null) {
      return IssueResponseTemplate.empty();
    }
    String plannedResolutionText = sectionContent(latestTemplate, PLAN_RESOLUTION_HEADERS);
    return new IssueResponseTemplate(
        parseDate(plannedResolutionText),
        plannedResolutionText == null ? "" : plannedResolutionText,
        valueOrEmpty(sectionContent(latestTemplate, PLAN_MERGE_BRANCH_HEADERS)));
  }

  private static String latestTemplate(String notesText) {
    String normalized = TextQuerySupport.trimToNull(notesText);
    if (normalized == null) {
      return null;
    }
    for (String note : normalized.split(NOTE_SEPARATOR)) {
      if (IssueRuleSupport.containsToken(note, TEMPLATE_HEADERS)) {
        return note;
      }
    }
    return null;
  }

  private static String sectionContent(String template, List<String> sectionHeaders) {
    String[] lines = template.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    for (int index = 0; index < lines.length; index++) {
      String inlineContent = inlineContent(lines[index], sectionHeaders);
      if (inlineContent == null) {
        continue;
      }
      if (!inlineContent.isEmpty()) {
        return inlineContent;
      }
      return followingSectionContent(lines, index + 1);
    }
    return null;
  }

  private static String inlineContent(String rawLine, List<String> sectionHeaders) {
    String line = rawLine == null ? "" : rawLine.trim().replace("**", "").replace("`", "");
    for (String sectionHeader : sectionHeaders) {
      int headerIndex = line.indexOf(sectionHeader);
      if (headerIndex < 0) {
        continue;
      }
      String prefix = line.substring(0, headerIndex).trim();
      if (!prefix.isEmpty() && !prefix.matches("#+")) {
        continue;
      }
      String content = line.substring(headerIndex + sectionHeader.length()).trim();
      if (content.startsWith(":" ) || content.startsWith("：")) {
        content = content.substring(1).trim();
      }
      return content;
    }
    return null;
  }

  private static String followingSectionContent(String[] lines, int startIndex) {
    StringBuilder content = new StringBuilder();
    for (int index = startIndex; index < lines.length; index++) {
      String line = lines[index].trim();
      if (line.startsWith("#")) {
        break;
      }
      if (line.isEmpty()) {
        continue;
      }
      if (content.length() > 0) {
        content.append('\n');
      }
      content.append(line);
    }
    return valueOrEmpty(content.toString());
  }

  private static LocalDateTime parseDate(String value) {
    if (TextQuerySupport.trimToNull(value) == null) {
      return null;
    }
    Matcher matcher = DATE_VALUE.matcher(value);
    if (!matcher.find()) {
      return null;
    }
    try {
      int year = Integer.parseInt(matcher.group(1));
      int month = Integer.parseInt(matcher.group(2));
      int day = Integer.parseInt(matcher.group(3));
      return year < 2020 || year > 2040 ? null : LocalDate.of(year, month, day).atStartOfDay();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String valueOrEmpty(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "" : normalized;
  }
}
