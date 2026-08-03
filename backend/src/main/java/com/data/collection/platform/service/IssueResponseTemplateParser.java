package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

/** 从 GitLab 聚合备注中读取最新“问题调研情况说明”响应模板。 */
final class IssueResponseTemplateParser {
  private static final String NOTE_SEPARATOR = "\\R---\\R";
  private static final List<String> TEMPLATE_HEADERS = List.of("# 问题调研情况说明", "问题调研情况说明");
  private static final List<String> PLAN_RESOLUTION_HEADERS = List.of("计划解决时间");
  private static final List<String> PLAN_MERGE_BRANCH_HEADERS = List.of("计划合并的版本分支", "计划合并版本分支");

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
    LocalDateTime plannedResolutionAt =
        IssueResponsePlanFieldRules.parsePlannedResolutionAt(plannedResolutionText);
    return new IssueResponseTemplate(
        plannedResolutionAt,
        plannedResolutionAt == null ? "" : plannedResolutionText.trim(),
        IssueResponsePlanFieldRules.normalizePlannedMergeBranchText(
            sectionContent(latestTemplate, PLAN_MERGE_BRANCH_HEADERS)));
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
    String line = rawLine == null ? "" : rawLine.trim();
    for (String sectionHeader : sectionHeaders) {
      int headerIndex = line.indexOf(sectionHeader);
      if (headerIndex < 0) {
        continue;
      }
      String prefix = line.substring(0, headerIndex).trim().replace("**", "").replace("`", "");
      if (!prefix.isEmpty() && !prefix.matches("#+")) {
        continue;
      }
      String content = line.substring(headerIndex + sectionHeader.length()).trim();
      if (content.startsWith("**")) {
        content = content.substring(2).trim();
      }
      if (content.startsWith("`")
          && content.length() > 1
          && (content.charAt(1) == ':' || content.charAt(1) == '：')) {
        content = content.substring(1).trim();
      }
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

  private static String valueOrEmpty(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "" : normalized;
  }
}
