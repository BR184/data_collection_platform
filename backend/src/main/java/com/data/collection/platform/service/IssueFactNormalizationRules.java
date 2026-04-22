package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

public final class IssueFactNormalizationRules {
  private IssueFactNormalizationRules() {
  }

  public static String normalizeSeverityLevel(List<String> labels) {
    return IssueLabelRules.normalizeSeverityLevel(labels);
  }

  public static String normalizeSeverityAlias(List<String> labels) {
    return IssueLabelRules.normalizeSeverityAlias(labels);
  }

  public static String normalizePriorityLevel(List<String> labels) {
    return IssueLabelRules.normalizePriorityLevel(labels);
  }

  public static boolean isExcluded(List<String> labels, boolean closed) {
    return IssueLabelRules.isExcluded(labels, closed);
  }

  public static String exclusionReason(List<String> labels, boolean closed) {
    return IssueLabelRules.exclusionReason(labels, closed);
  }

  public static boolean isFixed(List<String> labels, boolean closed) {
    return IssueLabelRules.isFixed(labels, closed);
  }

  public static String normalizeReasonCategory(List<String> labels, String notesText) {
    return IssueClassificationRules.normalizeReasonCategory(labels, notesText);
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

  public static String normalizeTestingPhase(List<String> labels) {
    return IssueLabelRules.normalizeTestingPhase(labels);
  }

  public static String normalizeSystemTestLabel(List<String> labels) {
    return IssueLabelRules.normalizeSystemTestLabel(labels);
  }

  public static List<String> normalizeModuleNames(List<String> labels) {
    return IssueLabelRules.normalizeModuleNames(
        labels,
        IssueClassificationRules.REASON_CATEGORY_TOKENS,
        IssueClassificationRules.DELAY_REASON_TOKENS);
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

  public static boolean hasTemplateReply(String notesText) {
    return IssueClassificationRules.hasTemplateReply(notesText);
  }

  public static int latestReasonCategoryCount(String notesText) {
    return IssueClassificationRules.latestReasonCategoryCount(notesText);
  }

  public static boolean hasResponse(String notesText) {
    return IssueSlaRules.hasResponse(notesText);
  }

  public static boolean isResponseDelayed(List<String> labels, String notesText) {
    return IssueSlaRules.isResponseDelayed(labels, notesText);
  }

  public static int resolveSlaDays(String notesText) {
    return IssueSlaRules.resolveSlaDays(notesText);
  }

  public static LocalDateTime resolveDeadline(LocalDateTime createdAt, int resolveSlaDays) {
    return IssueSlaRules.resolveDeadline(createdAt, resolveSlaDays);
  }

  public static boolean isResolveDelayed(
      List<String> labels,
      boolean fixed,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    return IssueSlaRules.isResolveDelayed(labels, fixed, resolveDeadlineAt, now);
  }

  public static boolean isLegacy(
      boolean closed,
      LocalDateTime createdAt,
      LocalDateTime phaseStartAt) {
    return IssueLegacyRules.isLegacy(closed, createdAt, phaseStartAt);
  }
}
