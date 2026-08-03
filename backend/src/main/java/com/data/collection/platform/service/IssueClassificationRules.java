package com.data.collection.platform.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class IssueClassificationRules {
  static final String MISSING_SEVERITY = "未设定严重程度";
  static final String MISSING_MODULE = "未设定模块";
  static final String TEMPLATE_NOT_FOLLOWED = "未按照模板回复";
  static final String NON_UNIQUE_REASON = "缺陷原因不唯一";
  static final String INVALID_RESEARCH_TEMPLATE = "未按照要求填写缺陷调研模板";

  static final Map<String, List<String>> REASON_CATEGORY_TOKENS = IssueRuleSupport.ordered(
      Map.entry("新增理解偏差", List.of("新增理解偏差", "新增理解偏差数量", "需求理解有误", "需求理解有误数量")),
      Map.entry("需求遗漏", List.of("需求遗漏", "需求遗漏数量")),
      Map.entry("新增需求", List.of("新增需求", "新增需求数量", "新增需求问题", "新增需求问题数量")),
      Map.entry("需求变更未同步", List.of("需求变更未同步", "需求变更未同步数量")),
      Map.entry("功能设计遗漏", List.of("功能设计遗漏", "功能设计遗漏数量")),
      Map.entry("设计方案不合理", List.of("设计方案不合理", "设计方案不合理数量")),
      Map.entry("场景考虑不全", List.of("场景考虑不全", "场景考虑不全数量")),
      Map.entry("术语、提示信息不合适", List.of("术语、提示信息不合适", "提示信息不合理")),
      Map.entry("编码规范错误", List.of("编码规范错误", "编码规范错误数量")),
      Map.entry("功能编码遗漏", List.of("功能编码遗漏", "功能编码遗漏数量")),
      Map.entry("编码逻辑：计算与算法错误", List.of("编码逻辑：计算与算法错误")),
      Map.entry("编码逻辑：流程控制错误", List.of("编码逻辑：流程控制错误")),
      Map.entry("编码逻辑：数据与状态处理错误", List.of("编码逻辑：数据与状态处理错误")),
      Map.entry("编码逻辑：业务逻辑错误", List.of("编码逻辑：业务逻辑错误", "编码逻辑错误", "业务逻辑错误")),
      Map.entry("编码逻辑：集成与接口错误", List.of("编码逻辑：集成与接口错误", "调用接口错误")),
      Map.entry("环境配置问题", List.of("环境配置问题")),
      Map.entry("编译/打包/部署问题", List.of("编译/打包/部署问题", "编译打包问题")),
      Map.entry("第三方库问题", List.of("第三方库问题")),
      Map.entry("算法不支持", List.of("算法不支持")),
      Map.entry("机制不支持", List.of("机制不支持", "算法/机制不支持")),
      Map.entry("前置数据异常", List.of("前置数据异常", "前置数据异常（如缺少模板文件、前置输入文件本身错误等）")),
      Map.entry("未识别的前后置任务", List.of("未识别的前后置任务")),
      Map.entry("精度导致约束求解异常", List.of("精度导致约束求解异常")),
      Map.entry("精度导致算法执行异常", List.of("精度导致算法执行异常")));
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
      List.of("问题原因", "修改方案");
  private static final List<String> PLAN_MERGE_VERSION_BRANCH_SECTIONS =
      List.of("计划合并的版本分支", "计划合并版本分支");
  private static final Pattern PROBLEM_TYPE_DEFECT_PATTERN = Pattern.compile("\\[[xX]\\]\\s+缺陷");
  private static final Pattern PROBLEM_TYPE_REQUIREMENT_PATTERN = Pattern.compile("\\[[xX]\\]\\s+需求");

  private IssueClassificationRules() {
  }

  static String normalizeFixReasonCategory(List<String> labels, String notesText) {
    IssueTemplateSnapshot snapshot = fixTemplateSnapshot(notesText);
    if (snapshot.legacyReasonText() != null) {
      return snapshot.legacyReasonText();
    }
    return normalizeReasonCategoryFromLabelsOrText(labels, notesText);
  }

  static String normalizeLegacyFixReasonCategory(String notesText) {
    return fixTemplateSnapshot(notesText).legacyReasonText();
  }

  private static String normalizeReasonCategoryFromLabelsOrText(List<String> labels, String notesText) {
    List<String> matched = new java.util.ArrayList<>();
    for (Map.Entry<String, List<String>> entry : REASON_CATEGORY_TOKENS.entrySet()) {
      if (IssueRuleSupport.containsAny(labels, notesText, entry.getValue())) {
        matched.add(entry.getKey());
      }
    }
    return matched.isEmpty() ? null : String.join(" ", matched);
  }

  static boolean hasDelayFlag(List<String> labels, String notesText) {
    return IssueRuleSupport.containsAnyLabel(labels, APPLY_DELAY_LABELS)
        || !IssueDelayCauseMembers.fromLabels(labels).isEmpty();
  }

  static String normalizeDelayReason(List<String> labels, String notesText) {
    return IssueDelayCauseMembers.normalizeLabels(labels);
  }

  static String inferDelayCause(List<String> labels, String notesText) {
    String delayReason = normalizeDelayReason(labels, notesText);
    if (delayReason != null) {
      return delayReason;
    }
    // 申请延期是解决闭环状态，不属于七类延期原因；状态判定由 hasDelayFlag 单独负责。
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
    return !systemTestIllegalReasons(labels, notesText).isEmpty();
  }

  static String illegalReason(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = illegalReasons(labels, closed, modules, notesText, fixed);
    return reasons.isEmpty() ? null : reasons.get(0);
  }

  static List<String> illegalReasons(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return systemTestIllegalReasons(labels, notesText);
  }

  private static List<String> systemTestIllegalReasons(List<String> labels, String notesText) {
    List<String> reasons = new java.util.ArrayList<>();
    Map<String, List<String>> oldPlatformLabels = IssueLabelRules.parseOldPlatformChineseColonLabelMap(labels);
    String oldPlatformModuleName = IssueLabelRules.oldPlatformCombinedIssueModuleName(labels);
    String oldPlatformSeverity =
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "严重程度", MISSING_SEVERITY);
    String oldPlatformBugStatus =
        IssueLabelRules.oldPlatformLabelValue(oldPlatformLabels, "状态", "未设定议题状态");

    if (!List.of("一级缺陷", "二级缺陷", "三级缺陷").contains(oldPlatformSeverity)) {
      reasons.add(MISSING_SEVERITY);
    }
    if (MISSING_MODULE.equals(oldPlatformModuleName)) {
      reasons.add(MISSING_MODULE);
    }
    if (oldPlatformBugStatus.contains("已修复")) {
      IssueTemplateSnapshot snapshot = fixTemplateSnapshot(notesText);
      if (!org.springframework.util.StringUtils.hasText(snapshot.legacyReasonText())) {
        reasons.add(TEMPLATE_NOT_FOLLOWED);
      } else {
        int reasonCount = oldPlatformMajorByCauseSplitCount(snapshot.legacyReasonText());
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
    List<String> reasons = new java.util.ArrayList<>(customerIssueBaseIllegalReasons(labels, modules, notesText, fixed));
    if (hasInvalidCustomerResearchTemplate(notesText, IssueLabelRules.normalizeDefectSeverityLevel(labels))) {
      reasons.add(INVALID_RESEARCH_TEMPLATE);
    }
    return List.copyOf(reasons);
  }

  private static List<String> customerIssueBaseIllegalReasons(
      List<String> labels, List<String> modules, String notesText, boolean fixed) {
    List<String> reasons = new java.util.ArrayList<>();
    if (IssueLabelRules.normalizeDefectSeverityLevel(labels) == null) {
      reasons.add(MISSING_SEVERITY);
    }
    if (modules == null || modules.isEmpty()) {
      reasons.add(MISSING_MODULE);
    }
    if (fixed) {
      IssueTemplateSnapshot snapshot = fixTemplateSnapshot(notesText);
      String legacyReasonText = snapshot.legacyReasonText();
      if (!org.springframework.util.StringUtils.hasText(legacyReasonText)) {
        reasons.add(TEMPLATE_NOT_FOLLOWED);
      } else {
        int reasonCount = oldPlatformMajorByCauseSplitCount(legacyReasonText);
        if (reasonCount != 1) {
          reasons.add(NON_UNIQUE_REASON);
        }
      }
    }
    return List.copyOf(reasons);
  }

  static boolean hasFixTemplateReply(String notesText) {
    return fixTemplateSnapshot(notesText).hasTemplateReply();
  }

  static int latestFixReasonCategoryCount(String notesText) {
    IssueTemplateSnapshot snapshot = fixTemplateSnapshot(notesText);
    int legacyCount = legacyMajorReasonCount(snapshot);
    return legacyCount > 0 ? legacyCount : snapshot.latestReasonCategoryCount();
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
    String plannedMergeVersionBranch = sectionContent(lines, PLAN_MERGE_VERSION_BRANCH_SECTIONS);
    if (!org.springframework.util.StringUtils.hasText(plannedMergeVersionBranch)) {
      return "计划合并的版本分支缺少回复内容";
    }
    if (LEVEL1.equals(severityLevel) && !hasSectionContent(lines, RESPONSIBLE_OWNER_SIGNATURE_SECTION)) {
      return RESPONSIBLE_OWNER_SIGNATURE_SECTION + "缺少回复内容";
    }
    if (!hasSinglePlanSolutionDate(lines)) {
      return "计划解决时间非法";
    }
    return !IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches(plannedMergeVersionBranch)
        ? "计划合并版本分支非法"
        : TEMPLATE_PASSED;
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
    return IssueResponsePlanFieldRules.parsePlannedResolutionAt(content) != null;
  }

  private static String sectionContent(String[] lines, String sectionName) {
    return sectionContent(lines, List.of(sectionName));
  }

  private static String sectionContent(String[] lines, List<String> sectionNames) {
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      String sectionName =
          sectionNames.stream().filter(line::contains).findFirst().orElse(null);
      if (sectionName == null) {
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
    return LEVEL1.equals(IssueLabelRules.normalizeDefectSeverityLevel(labels));
  }

  private static IssueTemplateSnapshot fixTemplateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(notesText, REASON_CATEGORY_TOKENS, FIX_TEMPLATE_HEADER_TOKENS);
  }

  private static IssueTemplateSnapshot researchTemplateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(notesText, REASON_CATEGORY_TOKENS, RESEARCH_TEMPLATE_HEADER_TOKENS);
  }

  private static int legacyMajorReasonCount(IssueTemplateSnapshot snapshot) {
    String cause = snapshot.legacyReasonText();
    if (!org.springframework.util.StringUtils.hasText(cause)) {
      return 0;
    }
    java.util.Set<String> categories = new java.util.LinkedHashSet<>();
    addIfContains(categories, cause, "需求阶段",
        "新增需求问题", "需求理解有误", "新增理解偏差", "需求遗漏", "新增需求", "需求变更未同步");
    addIfContains(categories, cause, "设计问题",
        "功能设计遗漏", "设计方案不合理", "场景考虑不全", "术语、提示不正确", "术语、提示信息不合适");
    addIfContains(categories, cause, "编码问题",
        "编码规范错误", "功能编码遗漏", "编码逻辑：计算与算法错误", "编码逻辑：流程控制错误",
        "编码逻辑：数据与状态处理错误", "编码逻辑：业务逻辑错误", "编码逻辑：集成与接口错误",
        "编码逻辑错误");
    addIfContains(categories, cause, "打包问题", "环境配置问题", "编译/打包/部署问题");
    addIfContains(categories, cause, "依赖问题",
        "第三方库问题", "算法/机制不支持", "未识别的前后置任务", "算法不支持", "机制不支持", "前置数据异常");
    addIfContains(categories, cause, "精度问题", "精度导致约束求解异常", "精度导致算法执行异常");
    return categories.size();
  }

  private static int oldPlatformMajorByCauseSplitCount(String cause) {
    if (!org.springframework.util.StringUtils.hasText(cause)) {
      return 0;
    }
    String majorByCause = oldPlatformMajorByCause(cause);
    return majorByCause.split("&").length;
  }

  private static String oldPlatformMajorByCause(String cause) {
    StringBuilder result = new StringBuilder();
    appendOldPlatformMajorIfContains(result, cause, "精度问题", "精度导致约束求解异常", "精度导致算法执行异常");
    appendOldPlatformMajorIfContains(result, cause, "打包问题", "环境配置问题", "编译/打包/部署问题");
    appendOldPlatformMajorIfContains(result, cause, "需求阶段",
        "新增需求问题", "需求理解有误", "新增理解偏差", "需求遗漏", "新增需求", "需求变更未同步");
    appendOldPlatformMajorIfContains(result, cause, "设计问题",
        "功能设计遗漏", "设计方案不合理", "场景考虑不全", "术语、提示不正确", "术语、提示信息不合适");
    appendOldPlatformMajorIfContains(result, cause, "编码问题",
        "编码规范错误", "功能编码遗漏", "编码逻辑：计算与算法错误", "编码逻辑：流程控制错误",
        "编码逻辑：数据与状态处理错误", "编码逻辑：业务逻辑错误", "编码逻辑：集成与接口错误",
        "编码逻辑错误", "编译打包问题");
    appendOldPlatformMajorIfContains(result, cause, "依赖问题",
        "第三方库问题", "算法/机制不支持", "未识别的前后置任务", "算法不支持", "机制不支持",
        "前置数据异常（如缺少模板文件、前置输入文件本身错误等）");
    return result.toString();
  }

  private static void appendOldPlatformMajorIfContains(
      StringBuilder result, String cause, String category, String... tokens) {
    for (String token : tokens) {
      if (cause.contains(token)) {
        result.append(category).append(' ');
      }
    }
  }

  private static void addIfContains(java.util.Set<String> categories, String text, String category, String... tokens) {
    for (String token : tokens) {
      if (text.contains(token)) {
        categories.add(category);
        return;
      }
    }
  }
}
