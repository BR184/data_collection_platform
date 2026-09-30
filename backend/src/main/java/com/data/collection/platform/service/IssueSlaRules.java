package com.data.collection.platform.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.util.StringUtils;

final class IssueSlaRules {
  private static final List<String> RESPONSE_DELAY_LABELS = List.of("响应已延期");
  private static final List<String> FIXED_LABELS = List.of("已修复", "已修复/完成");
  private static final List<String> RESPONSE_HEADER_TOKENS =
      List.of("# 问题调研情况说明", "问题调研情况说明");
  private static final List<String> FIX_CASE_NOTE_TOKENS = List.of("### 1、修复状态");
  private static final int RESPONSE_SLA_P1_HOURS = 24;
  private static final int RESPONSE_SLA_P2_HOURS = 48;
  private static final int RESPONSE_SLA_P3_HOURS = 72;
  private static final int MAX_RESOLVE_SLA_DAYS = 18;

  private IssueSlaRules() {}

  static boolean hasResponse(String notesText) {
    return templateSnapshot(notesText).hasTemplateReply();
  }

  /**
   * 响应延期豁免：回复模板存在，或响应模板存在且其“计划解决时间”合法。
   *
   * <p>合法格式以规则总表 5.4／11.4 为唯一标准，凡不符合即视为“没有写时间”，与非法模板判定同源；
   * 计划解决时间按最新一份响应模板取值，不可解析时回退更早模板。回复模板存在时优先豁免，
   * 不再检查响应模板的计划解决时间。
   *
   * @param notesText GitLab issue 备注聚合文本（按创建时间倒序）
   * @return 应豁免响应延期时返回 {@code true}
   */
  private static boolean isResponseExempt(String notesText) {
    return hasFixCaseNote(notesText)
        || IssueResponseTemplateParser.hasParseablePlanSolutionTime(notesText);
  }

  static boolean isResponseDelayed(List<String> labels, String notesText) {
    return !isResponseExempt(notesText)
        && IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS);
  }

  static boolean isResponseDelayed(
      List<String> labels,
      String notesText,
      LocalDateTime createdAt,
      String priorityLevel,
      LocalDateTime now) {
    if (isResponseExempt(notesText)) {
      return false;
    }
    if (createdAt == null || now == null) {
      return IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS);
    }
    return Duration.between(createdAt, now).toHours() > responseSlaHours(labels, priorityLevel);
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

  static LocalDateTime resolveDeadline(LocalDateTime createdAt, String notesText) {
    if (createdAt == null) {
      return null;
    }
    LocalDateTime defaultDeadline = createdAt.plusDays(MAX_RESOLVE_SLA_DAYS);
    LocalDateTime planSolutionTime = templateSnapshot(notesText).planSolutionTime();
    if (planSolutionTime == null) {
      return resolveDeadline(createdAt, resolveSlaDays(notesText));
    }
    long daysBetween = Duration.between(createdAt, planSolutionTime).toDays();
    if (daysBetween <= MAX_RESOLVE_SLA_DAYS) {
      return planSolutionTime;
    }
    return defaultDeadline;
  }

  static boolean hasFixCaseNote(String notesText) {
    return IssueRuleSupport.containsToken(notesText, FIX_CASE_NOTE_TOKENS);
  }

  static boolean isResolveDelayed(
      List<String> labels, boolean fixed, LocalDateTime resolveDeadlineAt, LocalDateTime now) {
    if (fixed || resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (CustomerIssueClosureRules.hasNonFixedClosureLabel(labels)
        || IssueRuleSupport.containsAnyLabel(labels, FIXED_LABELS)) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  static boolean isResolveDelayed(
      List<String> labels,
      boolean fixed,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    if (resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (CustomerIssueClosureRules.hasNonFixedClosureLabel(labels)) {
      return false;
    }
    if ((fixed || IssueRuleSupport.containsAnyLabel(labels, FIXED_LABELS)) && hasFixCaseNote) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  private static int responseSlaHours(List<String> labels, String priorityLevel) {
    String normalizedPriority = priorityLevel == null ? "" : priorityLevel.trim().toUpperCase(java.util.Locale.ROOT);
    if ("P1".equals(normalizedPriority) || hasExactLabel(labels, "P1")) {
      return RESPONSE_SLA_P1_HOURS;
    }
    if ("P2".equals(normalizedPriority) || hasExactLabel(labels, "P2")) {
      return RESPONSE_SLA_P2_HOURS;
    }
    return RESPONSE_SLA_P3_HOURS;
  }

  private static boolean hasExactLabel(List<String> labels, String expected) {
    if (labels == null || !StringUtils.hasText(expected)) {
      return false;
    }
    for (String label : labels) {
      if (expected.equalsIgnoreCase(label == null ? "" : label.trim())) {
        return true;
      }
    }
    return false;
  }

  private static IssueTemplateSnapshot templateSnapshot(String notesText) {
    return IssueTemplateParsingSupport.parse(
        notesText, IssueClassificationRules.REASON_CATEGORY_TOKENS, RESPONSE_HEADER_TOKENS);
  }
}
