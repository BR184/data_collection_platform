package com.data.collection.platform.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IssueClassificationRules {
  static final String MISSING_SEVERITY = "未设定严重程度";
  static final String MISSING_MODULE = "未设定模块";
  static final String TEMPLATE_NOT_FOLLOWED = "未按照模板回复";
  static final String NON_UNIQUE_REASON = "缺陷原因不唯一";
  static final String INVALID_RESEARCH_TEMPLATE = "未按照要求填写缺陷调研模板";

  static final Map<String, List<String>> REASON_CATEGORY_TOKENS = IssueRuleSupport.ordered(
      Map.entry("需求理解偏差", List.of("新增理解偏差数量", "需求理解有误数量")),
      Map.entry("新增需求", List.of("新增需求数量", "新增需求问题数量", "新增需求问题")),
      Map.entry("编码逻辑错误", List.of("业务逻辑错误", "编码逻辑错误")),
      Map.entry("环境部署问题", List.of("编译/打包/部署问题", "编译打包问题")),
      Map.entry("算法机制不支持", List.of("机制不支持", "算法/机制不支持")));
  static final Map<String, List<String>> DELAY_REASON_TOKENS = IssueRuleSupport.ordered(
      Map.entry("技术卡点", List.of("技术卡点")),
      Map.entry("方案卡点", List.of("方案卡点")),
      Map.entry("资源卡点", List.of("资源卡点")),
      Map.entry("数据异常", List.of("数据异常")),
      Map.entry("算法问题", List.of("算法问题")),
      Map.entry("机制问题", List.of("机制问题")),
      Map.entry("计算效率", List.of("计算效率")));

  private static final List<String> REGRESSION_TITLE_TOKENS = List.of("回退", "倒退", "（退");
  private static final List<String> LEGACY_LEVEL1_OTHER_EXCLUDE_TITLE_TOKENS =
      List.of("退", "回退", "倒退", "挂机");
  private static final List<String> CRASH_TITLE_TOKENS = List.of("挂机");
  private static final List<String> APPLY_DELAY_LABELS = List.of("申请延期");
  private static final List<String> FIX_TEMPLATE_HEADER_TOKENS = List.of("### 1、修复状态");
  private static final List<String> RESEARCH_TEMPLATE_HEADER_TOKENS = List.of("# 问题调研情况说明", "问题调研情况说明");
  private static final String NOTE_SEPARATOR = "\\R---\\R";
  private static final String RESEARCH_TEMPLATE_HEADER = "# 问题调研情况说明";
  private static final String TEMPLATE_PASSED = "模板验证通过";
  private static final String LEVEL1 = "LEVEL1";
  private static final String RESPONSIBLE_OWNER_SIGNATURE_SECTION = "一级缺陷的修改方案请模块负责人签字确认";
  private static final List<String> CUSTOMER_RESEARCH_REQUIRED_SECTIONS =
      List.of("问题原因", "修改方案", "计划合并的版本分支");
  private static final Pattern PROBLEM_TYPE_DEFECT_PATTERN = Pattern.compile("\\[[xX]\\]\\s+缺陷");
  private static final Pattern PROBLEM_TYPE_REQUIREMENT_PATTERN = Pattern.compile("\\[[xX]\\]\\s+需求");
  private static final Pattern PLAN_DATE_PATTERN =
      Pattern.compile("\\d{4}\\s*(?:年|[.,，、/·`])\\s*\\d{1,2}\\s*(?:月|[.,，、/·`])\\s*\\d{1,2}\\s*(?:日)?");

  private IssueClassificationRules() {
  }

  static String normalizeFixReasonCategory(List<String> labels, String notesText) {
    String fromLatestNote = fixTemplateSnapshot(notesText).normalizedReasonCategory();
    if (fromLatestNote != null) {
      return fromLatestNote;
    }
    return normalizeReasonCategoryFromLabelsOrText(labels, notesText);
  }

  private static String normalizeReasonCategoryFromLabelsOrText(List<String> labels, String notesText) {
    for (Map.Entry<String, List<String>> entry : REASON_CATEGORY_TOKENS.entrySet()) {
      if (IssueRuleSupport.containsAny(labels, notesText, entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  static boolean hasDelayFlag(List<String> labels, String notesText) {
    return IssueRuleSupport.containsAnyLabel(labels, APPLY_DELAY_LABELS)
        || normalizeDelayReason(labels, notesText) != null;
  }

  static String normalizeDelayReason(List<String> labels, String notesText) {
    for (Map.Entry<String, List<String>> entry : DELAY_REASON_TOKENS.entrySet()) {
      if (IssueRuleSupport.containsAny(labels, notesText, entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  static String inferDelayCause(List<String> labels, String notesText) {
    String delayReason = normalizeDelayReason(labels, notesText);
    if (delayReason != null) {
      return delayReason;
    }
    if (IssueRuleSupport.containsAnyLabel(labels, APPLY_DELAY_LABELS)) {
      return "申请延期";
    }
    return null;
  }

  static boolean isRegression(List<String> labels, String title) {
    return isLevel1(labels) && IssueRuleSupport.containsToken(title, REGRESSION_TITLE_TOKENS);
  }

  static boolean isCrash(List<String> labels, String title) {
    return isLevel1(labels) && IssueRuleSupport.containsToken(title, CRASH_TITLE_TOKENS);
  }

  static boolean isLevel1Other(List<String> labels, String title) {
    return isLevel1(labels)
        && !IssueRuleSupport.containsToken(title, LEGACY_LEVEL1_OTHER_EXCLUDE_TITLE_TOKENS);
  }

  static boolean isIllegal(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return !systemTestIllegalReasons(labels, modules, notesText, fixed).isEmpty();
  }

  static String illegalReason(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = illegalReasons(labels, closed, modules, notesText, fixed);
    return reasons.isEmpty() ? null : reasons.get(0);
  }

  static List<String> illegalReasons(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return systemTestIllegalReasons(labels, modules, notesText, fixed);
  }

  private static List<String> systemTestIllegalReasons(
      List<String> labels, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = new java.util.ArrayList<>();
    if (IssueLabelRules.normalizeSeverityLevel(labels) == null) {
      reasons.add(MISSING_SEVERITY);
    }
    if (modules == null || modules.isEmpty()) {
      reasons.add(MISSING_MODULE);
    }
    if (fixed) {
      IssueTemplateSnapshot snapshot = fixTemplateSnapshot(notesText);
      if (!snapshot.hasTemplateReply()) {
        reasons.add(TEMPLATE_NOT_FOLLOWED);
      } else {
        int reasonCount = snapshot.latestReasonCategoryCount();
        if (reasonCount != 1) {
          reasons.add(NON_UNIQUE_REASON);
        }
      }
    }
    return List.copyOf(reasons);
  }

  static boolean isCustomerIssueIllegal(
      List<String> labels, List<String> modules, String notesText, boolean fixed) {
    return !customerIssueIllegalReasons(labels, modules, notesText, fixed).isEmpty();
  }

  static String customerIssueIllegalReason(
      List<String> labels, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = customerIssueIllegalReasons(labels, modules, notesText, fixed);
    return reasons.isEmpty() ? null : reasons.get(0);
  }

  static List<String> customerIssueIllegalReasons(
      List<String> labels, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = new java.util.ArrayList<>(systemTestIllegalReasons(labels, modules, notesText, fixed));
    if (hasInvalidCustomerResearchTemplate(notesText, IssueLabelRules.normalizeSeverityLevel(labels))) {
      reasons.add(INVALID_RESEARCH_TEMPLATE);
    }
    return List.copyOf(reasons);
  }

  static boolean hasFixTemplateReply(String notesText) {
    return fixTemplateSnapshot(notesText).hasTemplateReply();
  }

  static int latestFixReasonCategoryCount(String notesText) {
    return fixTemplateSnapshot(notesText).latestReasonCategoryCount();
  }

  static boolean hasResearchTemplateReply(String notesText) {
    return researchTemplateSnapshot(notesText).hasTemplateReply();
  }

  static int latestResearchReasonCategoryCount(String notesText) {
    return researchTemplateSnapshot(notesText).latestReasonCategoryCount();
  }

  private static boolean hasInvalidCustomerResearchTemplate(String notesText, String severityLevel) {
    if (!org.springframework.util.StringUtils.hasText(notesText)) {
      return false;
    }
    boolean hasMatchedTemplate = false;
    boolean hasValidTemplate = false;
    for (String note : notesText.split(NOTE_SEPARATOR)) {
      if (!org.springframework.util.StringUtils.hasText(note) || !note.contains(RESEARCH_TEMPLATE_HEADER)) {
        continue;
      }
      hasMatchedTemplate = true;
      if (TEMPLATE_PASSED.equals(validateCustomerResearchTemplate(note, severityLevel))) {
        hasValidTemplate = true;
        break;
      }
    }
    return hasMatchedTemplate && !hasValidTemplate;
  }

  private static String validateCustomerResearchTemplate(String content, String severityLevel) {
    if (!org.springframework.util.StringUtils.hasText(content)) {
      return "错误：文档内容为空";
    }
    String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n");
    String typeError = validateProblemType(lines);
    if (typeError != null) {
      return typeError;
    }
    for (String section : CUSTOMER_RESEARCH_REQUIRED_SECTIONS) {
      if (!hasSectionContent(lines, section)) {
        return section + "缺少回复内容";
      }
    }
    if (LEVEL1.equals(severityLevel) && !hasSectionContent(lines, RESPONSIBLE_OWNER_SIGNATURE_SECTION)) {
      return RESPONSIBLE_OWNER_SIGNATURE_SECTION + "缺少回复内容";
    }
    return hasSinglePlanSolutionDate(lines) ? TEMPLATE_PASSED : "计划解决时间非法";
  }

  private static String validateProblemType(String[] lines) {
    boolean defectChecked = false;
    boolean requirementChecked = false;
    for (String line : lines) {
      if (PROBLEM_TYPE_DEFECT_PATTERN.matcher(line).find()) {
        defectChecked = true;
      }
      if (PROBLEM_TYPE_REQUIREMENT_PATTERN.matcher(line).find()) {
        requirementChecked = true;
      }
    }
    if (!defectChecked && !requirementChecked) {
      return "错误：问题类型未勾选";
    }
    if (defectChecked && requirementChecked) {
      return "错误：问题类型只能勾选一项";
    }
    return null;
  }

  private static boolean hasSectionContent(String[] lines, String sectionName) {
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      if (!line.startsWith("## " + sectionName)) {
        continue;
      }
      String contentAfterTitle = line.substring(("## " + sectionName).length()).trim();
      if (contentAfterTitle.startsWith(":") || contentAfterTitle.startsWith("：")) {
        contentAfterTitle = contentAfterTitle.substring(1).trim();
      }
      if (!contentAfterTitle.isEmpty()) {
        return true;
      }
      for (int nextIndex = index + 1; nextIndex < lines.length; nextIndex++) {
        String nextLine = lines[nextIndex].trim();
        if (nextLine.isEmpty()) {
          continue;
        }
        return !nextLine.startsWith("##");
      }
      return false;
    }
    return false;
  }

  private static boolean hasSinglePlanSolutionDate(String[] lines) {
    String content = sectionContent(lines, "计划解决时间");
    if (content == null) {
      return false;
    }
    Matcher matcher = PLAN_DATE_PATTERN.matcher(content);
    int count = 0;
    while (matcher.find()) {
      count++;
      if (count > 1) {
        return false;
      }
    }
    return count == 1;
  }

  private static String sectionContent(String[] lines, String sectionName) {
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      if (!line.contains(sectionName)) {
        continue;
      }
      String contentAfterTitle = line.substring(line.indexOf(sectionName) + sectionName.length()).trim();
      if (contentAfterTitle.startsWith(":") || contentAfterTitle.startsWith("：")) {
        contentAfterTitle = contentAfterTitle.substring(1).trim();
      }
      StringBuilder content = new StringBuilder(contentAfterTitle);
      for (int nextIndex = index + 1; nextIndex < lines.length; nextIndex++) {
        String nextLine = lines[nextIndex].trim();
        if (nextLine.isEmpty()) {
          continue;
        }
        if (nextLine.startsWith("##")) {
          break;
        }
        if (content.length() > 0) {
          content.append('\n');
        }
        content.append(nextLine);
      }
      return content.toString().trim();
    }
    return null;
  }

  private static boolean isLevel1(List<String> labels) {
    return LEVEL1.equals(IssueLabelRules.normalizeSeverityLevel(labels));
  }

  private static IssueTemplateSnapshot fixTemplateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(notesText, REASON_CATEGORY_TOKENS, FIX_TEMPLATE_HEADER_TOKENS);
  }

  private static IssueTemplateSnapshot researchTemplateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(notesText, REASON_CATEGORY_TOKENS, RESEARCH_TEMPLATE_HEADER_TOKENS);
  }
}
