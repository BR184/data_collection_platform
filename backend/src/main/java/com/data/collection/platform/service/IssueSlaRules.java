package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

final class IssueSlaRules {
  private static final List<String> RESPONSE_DELAY_LABELS = List.of("响应已延期");
  private static final List<String> RESOLVE_DELAY_EXEMPT_LABELS =
      List.of("申请延期", "数据异常", "需求如此", "未复现", "已修复", "已修复/完成");
  private static final List<String> RESPONSE_HEADER_TOKENS =
      List.of("# 问题调研情况说明", "问题调研情况说明");

  private IssueSlaRules() {}

  static boolean hasResponse(String notesText) {
    return templateSnapshot(notesText).hasTemplateReply();
  }

  static boolean isResponseDelayed(List<String> labels, String notesText) {
    return IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS)
        || (!hasResponse(notesText) && IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS));
  }

  static int resolveSlaDays(String notesText) {
    return templateSnapshot(notesText).resolveSlaDays();
  }

  static LocalDateTime resolveDeadline(LocalDateTime createdAt, int resolveSlaDays) {
    if (createdAt == null) {
      return null;
    }
    return createdAt.plusDays(Math.max(resolveSlaDays, 1));
  }

  static boolean isResolveDelayed(
      List<String> labels, boolean fixed, LocalDateTime resolveDeadlineAt, LocalDateTime now) {
    if (fixed || resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (IssueRuleSupport.containsAnyLabel(labels, RESOLVE_DELAY_EXEMPT_LABELS)) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  private static IssueTemplateSnapshot templateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(
        notesText, IssueClassificationRules.REASON_CATEGORY_TOKENS, RESPONSE_HEADER_TOKENS);
  }
}
