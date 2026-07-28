package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class IssueFactNormalizationRules {
  private IssueFactNormalizationRules() {
  }

  public static String normalizeSeverityLevel(List<String> labels) {
    return IssueLabelRules.normalizeSeverityLevel(labels);
  }

  public static String normalizeSeverityAlias(List<String> labels) {
    return IssueLabelRules.normalizeSeverityAlias(labels);
  }

  public static String normalizeCategory(List<String> labels) {
    return IssueLabelRules.normalizeCategory(labels);
  }

  public static String normalizePriorityLevel(List<String> labels) {
    return IssueLabelRules.normalizePriorityLevel(labels);
  }

  public static boolean isExcluded(List<String> labels, boolean closed, Long projectId) {
    return IssueLabelRules.isExcluded(labels, closed, projectId);
  }

  public static String exclusionReason(List<String> labels, boolean closed, Long projectId) {
    return IssueLabelRules.exclusionReason(labels, closed, projectId);
  }

  public static boolean isFixed(List<String> labels, boolean closed) {
    return IssueLabelRules.isFixed(labels, closed);
  }

  /**
   * 按老平台标签契约提取议题测试状态，不混入 GitLab 议题开闭状态。
   *
   * @param labels 当前议题的完整 GitLab 标签
   * @return 由 {@code 状态：值} 标签组成的稳定文本；缺失时返回“未设定议题状态”
   */
  public static String normalizeBugStatus(List<String> labels) {
    return IssueLabelRules.normalizeBugStatus(labels);
  }

  public static String normalizeReasonCategory(List<String> labels, String notesText) {
    return IssueClassificationRules.normalizeFixReasonCategory(labels, notesText);
  }

  public static String normalizeCustomerIssueReasonCategory(List<String> labels, String notesText) {
    return IssueClassificationRules.normalizeLegacyFixReasonCategory(notesText);
  }

  public static boolean hasDelayFlag(List<String> labels, String notesText) {
    return IssueClassificationRules.hasDelayFlag(labels, notesText);
  }

  public static String normalizeDelayReason(List<String> labels, String notesText) {
    return IssueClassificationRules.normalizeDelayReason(labels, notesText);
  }

  public static String inferDelayCause(List<String> labels, String notesText) {
    return IssueClassificationRules.inferDelayCause(labels, notesText);
  }

  public static String inferCustomerIssueDelayCause(List<String> labels, String notesText) {
    String delayCause = inferDelayCause(labels, notesText);
    return delayCause == null ? "未设定类别" : delayCause;
  }

  public static String normalizeTestingPhase(List<String> labels) {
    return IssueLabelRules.normalizeTestingPhase(labels);
  }

  public static String normalizeSystemTestLabel(List<String> labels) {
    return IssueLabelRules.normalizeSystemTestLabel(labels);
  }

  public static List<String> normalizeModuleNames(List<String> labels) {
    return IssueLabelRules.normalizeModuleNames(labels);
  }

  public static List<String> normalizeMergeRequestModuleNames(List<String> labels) {
    return IssueLabelRules.normalizeMergeRequestModuleNames(labels);
  }

  public static String normalizeMergeRequestProjectName(List<String> labels) {
    return IssueLabelRules.normalizeMergeRequestProjectName(labels);
  }

  public static Map<String, List<String>> parseLegacyLabelMap(List<String> labels) {
    return IssueLabelRules.parseLegacyLabelMap(labels);
  }

  public static String normalizeFunctionName(String title) {
    return IssueFunctionRules.normalizeFunctionName(title);
  }

  public static String normalizePrimaryModuleName(List<String> labels) {
    List<String> modules = normalizeModuleNames(labels);
    return modules.isEmpty() ? null : modules.get(0);
  }

  public static boolean isRegression(List<String> labels, String title) {
    return IssueClassificationRules.isRegression(labels, title);
  }

  public static boolean isCrash(List<String> labels, String title) {
    return IssueClassificationRules.isCrash(labels, title);
  }

  public static boolean isLevel1Other(List<String> labels, String title) {
    return IssueClassificationRules.isLevel1Other(labels, title);
  }

  public static boolean isIllegal(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.isIllegal(labels, closed, modules, notesText, fixed);
  }

  public static String illegalReason(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.illegalReason(labels, closed, modules, notesText, fixed);
  }

  public static List<String> illegalReasons(List<String> labels, boolean closed, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.illegalReasons(labels, closed, modules, notesText, fixed);
  }

  public static boolean isCustomerIssueIllegal(List<String> labels, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.isCustomerIssueIllegal(labels, modules, notesText, fixed);
  }

  public static String customerIssueIllegalReason(List<String> labels, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.customerIssueIllegalReason(labels, modules, notesText, fixed);
  }

  public static List<String> customerIssueIllegalReasons(List<String> labels, List<String> modules, String notesText, boolean fixed) {
    return IssueClassificationRules.customerIssueIllegalReasons(labels, modules, notesText, fixed);
  }

  public static boolean hasFixTemplateReply(String notesText) {
    return IssueClassificationRules.hasFixTemplateReply(notesText);
  }

  public static int latestFixReasonCategoryCount(String notesText) {
    return IssueClassificationRules.latestFixReasonCategoryCount(notesText);
  }

  public static boolean hasResearchTemplateReply(String notesText) {
    return IssueClassificationRules.hasResearchTemplateReply(notesText);
  }

  public static int latestResearchReasonCategoryCount(String notesText) {
    return IssueClassificationRules.latestResearchReasonCategoryCount(notesText);
  }

  public static boolean hasResponse(String notesText) {
    return IssueSlaRules.hasResponse(notesText);
  }

  public static boolean isResponseDelayed(List<String> labels, String notesText) {
    return IssueSlaRules.isResponseDelayed(labels, notesText);
  }

  public static boolean isResponseDelayed(
      List<String> labels,
      String notesText,
      LocalDateTime createdAt,
      String priorityLevel,
      LocalDateTime now) {
    return IssueSlaRules.isResponseDelayed(labels, notesText, createdAt, priorityLevel, now);
  }

  public static int resolveSlaDays(String notesText) {
    return IssueSlaRules.resolveSlaDays(notesText);
  }

  public static LocalDateTime resolveDeadline(LocalDateTime createdAt, int resolveSlaDays) {
    return IssueSlaRules.resolveDeadline(createdAt, resolveSlaDays);
  }

  public static LocalDateTime resolveDeadline(LocalDateTime createdAt, String notesText) {
    return IssueSlaRules.resolveDeadline(createdAt, notesText);
  }

  public static boolean hasFixCaseNote(String notesText) {
    return IssueSlaRules.hasFixCaseNote(notesText);
  }

  public static boolean isResolveDelayed(
      List<String> labels,
      boolean fixed,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    return IssueSlaRules.isResolveDelayed(labels, fixed, resolveDeadlineAt, now);
  }

  public static boolean isResolveDelayed(
      List<String> labels,
      boolean fixed,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    return IssueSlaRules.isResolveDelayed(labels, fixed, hasFixCaseNote, resolveDeadlineAt, now);
  }

  public static boolean isLegacy(
      List<String> labels,
      boolean closed,
      LocalDateTime createdAt,
      LocalDateTime phaseStartAt) {
    return IssueLegacyRules.isLegacy(labels, closed, createdAt, phaseStartAt);
  }
}
