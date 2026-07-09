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
  private static final String FIX_TEMPLATE_HEADER = "### 1、修复状态";
  private static final String FIX_TEMPLATE_DETAIL_FOOTER = "### 3、请描述具体原因：";
  private static final String FIX_TEMPLATE_DETAIL_FOOTER_LEGACY = "（2）具体原因，请描述：";
  private static final String LEGACY_OTHER_CAUSE_TOKEN = "其他，请具体说明";
  private static final java.util.List<String> LEGACY_MINOR_CAUSE_TOKENS =
      java.util.List.of(
          "新增需求问题",
          "新增需求",
          "需求理解有误",
          "新增理解偏差",
          "需求遗漏",
          "需求变更未同步",
          "场景考虑不全",
          "功能设计遗漏",
          "术语、提示信息不合适",
          "设计方案不合理",
          "编码规范错误",
          "功能编码遗漏",
          "编码逻辑错误",
          "编码逻辑：计算与算法错误",
          "编码逻辑：流程控制错误",
          "编码逻辑：数据与状态处理错误",
          "编码逻辑：业务逻辑错误",
          "编码逻辑：集成与接口错误",
          "编译打包问题",
          "环境配置问题",
          "编译/打包/部署问题",
          "第三方库问题",
          "算法/机制不支持",
          "算法不支持",
          "机制不支持",
          "前置数据异常（如缺少模板文件、前置输入文件本身错误等）",
          "由修改其他问题引起的",
          "未识别的前后置任务",
          "精度导致约束求解异常",
          "精度导致算法执行异常");

  private IssueTemplateParsingSupport() {}

  static IssueTemplateSnapshot parse(
      String notesText, Map<String, java.util.List<String>> reasonCategoryTokens, java.util.List<String> templateHeaders) {
    boolean hasTemplateReply = IssueRuleSupport.containsToken(notesText, templateHeaders);
    int resolveSlaDays = resolveSlaDays(notesText);
    LocalDateTime planSolutionTime = planSolutionTime(notesText);
    Set<String> latestCategories = latestTemplateReasonCategories(notesText, reasonCategoryTokens, templateHeaders);
    String normalizedReasonCategory =
        latestCategories.size() == 1 ? latestCategories.iterator().next() : null;
    return new IssueTemplateSnapshot(
        hasTemplateReply,
        resolveSlaDays,
        planSolutionTime,
        latestCategories.size(),
        normalizedReasonCategory,
        legacyReasonText(notesText));
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

  private static Set<String> latestTemplateReasonCategories(
      String notesText,
      Map<String, java.util.List<String>> reasonCategoryTokens,
      java.util.List<String> templateHeaders) {
    String latestTemplateNote = latestTemplateNote(notesText, templateHeaders);
    if (latestTemplateNote == null) {
      return Set.of();
    }
    return matchedReasonCategories(latestTemplateNote, reasonCategoryTokens);
  }

  private static String latestTemplateNote(String notesText, java.util.List<String> templateHeaders) {
    if (!StringUtils.hasText(notesText)) {
      return null;
    }
    String[] notes = notesText.split(NOTE_SEPARATOR);
    for (int index = notes.length - 1; index >= 0; index--) {
      String candidate = notes[index];
      if (IssueRuleSupport.containsToken(candidate, templateHeaders)) {
        return candidate;
      }
    }
    return null;
  }

  private static String legacyReasonText(String notesText) {
    if (!StringUtils.hasText(notesText)) {
      return null;
    }
    String[] notes = notesText.split(NOTE_SEPARATOR);
    for (String note : notes) {
      String cause = legacyCauseInNote(note);
      if (StringUtils.hasText(cause)) {
        return processLegacyCause(cause);
      }
    }
    return null;
  }

  private static String legacyCauseInNote(String note) {
    if (!StringUtils.hasText(note)) {
      return null;
    }
    String[] lines = note.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    if (lines.length == 0 || !lines[0].contains(FIX_TEMPLATE_HEADER)) {
      return null;
    }
    StringBuilder cause = new StringBuilder();
    int index = 0;
    while (index < lines.length
        && !lines[index].contains(FIX_TEMPLATE_DETAIL_FOOTER)
        && !lines[index].contains(FIX_TEMPLATE_DETAIL_FOOTER_LEGACY)) {
      String line = lines[index];
      if (line.contains("[x]") || line.contains("[X]")) {
        cause.append(line.replace("*", "").replace("[x]", "").replace("[X]", "").replace(" ", ""))
            .append(' ');
      }
      index++;
    }
    if (index >= lines.length) {
      return null;
    }
    cause.append("具体原因, 请描述：");
    while (++index < lines.length) {
      String line = lines[index];
      if (line.contains("[ ]") || line.isBlank() || "```".equals(line)) {
        continue;
      }
      cause.append(line.replace("#", "").replace(" ", "").replace("[x]", "").replace("[X]", ""));
    }
    return cause.toString();
  }

  private static String processLegacyCause(String cause) {
    if (!StringUtils.hasText(cause)) {
      return null;
    }
    if (!cause.contains(LEGACY_OTHER_CAUSE_TOKEN)) {
      return cause;
    }
    for (String token : LEGACY_MINOR_CAUSE_TOKENS) {
      if (cause.contains(token)) {
        return cause.replace(LEGACY_OTHER_CAUSE_TOKEN, "");
      }
    }
    return cause;
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
