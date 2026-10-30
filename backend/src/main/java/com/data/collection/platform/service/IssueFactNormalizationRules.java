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
   * <p>所有项目保留全角状态前缀；客户项目 325 额外接受已确认的九个裸状态词与半角前缀。
   *
   * @param labels 当前议题的完整 GitLab 标签
   * @param projectId 议题所属 GitLab 项目 ID
   * @return 稳定去重的状态成员文本；缺失时返回“未设定议题状态”
   */
  public static String normalizeBugStatus(List<String> labels, Long projectId) {
    return IssueLabelRules.normalizeBugStatus(labels, projectId);
  }

  /**
   * 按老平台固定修复模板提取缺陷原因，不从标签或普通评论推断。
   *
   * @param notesText 按时间倒序拼接的 GitLab 议题评论
   * @return 第一条合法固定模板的勾选原因及具体说明；没有合法模板时返回 {@code null}
   */
  public static String normalizeReasonCategory(String notesText) {
    return IssueClassificationRules.normalizeFixReasonCategory(notesText);
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

  /**
   * 先应用共用标签/修复模板及合法响应计划豁免，再读取现有响应延期标签。
   *
   * @param labels 当前有效原始标签，允许为空
   * @param notesText 评论聚合正文，允许为空
   * @return 未豁免且已有响应延期标签时返回true；本重载不计算时间差
   */
  public static boolean isResponseDelayed(List<String> labels, String notesText) {
    return IssueSlaRules.isResponseDelayed(labels, notesText);
  }

  /**
   * 豁免优先于时间计算；未豁免时按紧急程度比较响应时限。
   *
   * @param labels 当前有效原始标签，允许为空
   * @param notesText 评论聚合正文，允许为空
   * @param createdAt 议题创建时刻，缺失时读取已有响应延期标签
   * @param priorityLevel 紧急程度P1/P2/P3，未识别时按标签或P3处理
   * @param now 本次判定时刻，缺失时读取已有响应延期标签
   * @return 未豁免且按完整小时严格超过响应时限时返回true
   */
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

  /**
   * 从按创建时间、ID倒序聚合的评论读取首个可解析计划日期，再应用既有18天上限与无日期回退规则。
   *
   * @param createdAt 来源创建时间；为空时不生成截止时间
   * @param notesText 从新到旧聚合的评论；新评论无可解析日期时继续查找更早评论
   * @return 解决截止时间；不改变计划日期格式、普通备注范围或闭环豁免规则
   */
  public static LocalDateTime resolveDeadline(LocalDateTime createdAt, String notesText) {
    return IssueSlaRules.resolveDeadline(createdAt, notesText);
  }

  public static boolean hasFixCaseNote(String notesText) {
    return IssueSlaRules.hasFixCaseNote(notesText);
  }

  /**
   * 共用豁免命中时不解决延期，否则比较截止时间；不从归一化状态丢失需求、建议等原始标签。
   *
   * @param labels 当前有效原始标签，不能仅传入bug_status成员
   * @param hasFixCaseNote 是否存在修复模板标记；存在即独立豁免
   * @param resolveDeadlineAt 解决截止时间，缺失时返回false
   * @param now 本次判定时刻，缺失时返回false
   * @return 未命中共用豁免且已严格超过截止时返回true
   */
  public static boolean isResolveDelayed(
      List<String> labels,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    return IssueSlaRules.isResolveDelayed(labels, hasFixCaseNote, resolveDeadlineAt, now);
  }

  public static boolean isLegacy(
      List<String> labels,
      boolean closed,
      LocalDateTime createdAt,
      LocalDateTime phaseStartAt) {
    return IssueLegacyRules.isLegacy(labels, closed, createdAt, phaseStartAt);
  }
}
