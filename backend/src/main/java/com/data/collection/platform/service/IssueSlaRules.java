package com.data.collection.platform.service;

import com.data.collection.platform.domain.customerissue.CustomerRequirementRules;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class IssueSlaRules {
  private static final List<String> RESPONSE_DELAY_LABELS = List.of("响应已延期");
  // 仅延期判定共用此豁免，不改变其它统计中的状态、修复或需求身份。
  private static final Set<String> COMMON_DELAY_EXEMPT_STATUSES =
      Set.of("申请延期", "设计如此", "申请否决", "数据异常", "未复现", "需求如此");
  private static final Set<String> COMMON_DELAY_EXEMPT_LABELS = Set.of(
      CustomerRequirementRules.REQUIREMENT_LABEL, CustomerRequirementRules.SUGGESTION_LABEL, "建议");
  private static final Pattern STATUS_LABEL_PREFIX = Pattern.compile("^状态\\s*[：:]\\s*");
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
   * 响应延期先应用双标签共用豁免，其次检查响应模板中的合法计划日期。
   *
   * <p>合法格式以规则总表 5.4／11.4 为唯一标准，凡不符合即视为“没有写时间”，与非法模板判定同源；
   * 计划解决时间按最新一份响应模板取值，不可解析时回退更早模板。回复模板存在时优先豁免，
   * 不再检查响应模板的计划解决时间。
   *
   * @param notesText GitLab issue 备注聚合文本（按创建时间倒序）
   * @return 应豁免响应延期时返回 {@code true}
   */
  private static boolean isResponseExempt(List<String> labels, String notesText) {
    return hasCommonDelayExemption(labels, hasFixCaseNote(notesText))
        || IssueResponseTemplateParser.hasParseablePlanSolutionTime(notesText);
  }

  static boolean isResponseDelayed(List<String> labels, String notesText) {
    return !isResponseExempt(labels, notesText)
        && IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS);
  }

  static boolean isResponseDelayed(
      List<String> labels,
      String notesText,
      LocalDateTime createdAt,
      String priorityLevel,
      LocalDateTime now) {
    if (isResponseExempt(labels, notesText)) {
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
      List<String> labels,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    if (hasCommonDelayExemption(labels, hasFixCaseNote)
        || resolveDeadlineAt == null || now == null) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  /** 原始标签与修复模板共同决定双延期豁免；原因前缀、否定词和其它标签组不能冒充豁免状态。 */
  private static boolean hasCommonDelayExemption(List<String> labels, boolean hasFixCaseNote) {
    if (hasFixCaseNote) {
      return true;
    }
    for (String label : labels == null ? List.<String>of() : labels) {
      String value = label == null ? "" : label.trim();
      if (COMMON_DELAY_EXEMPT_LABELS.contains(value)) {
        return true;
      }
      String status = STATUS_LABEL_PREFIX.matcher(value).replaceFirst("").trim();
      if (COMMON_DELAY_EXEMPT_STATUSES.contains(status)) {
        return true;
      }
    }
    return false;
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

  // 表单接入使用结构化输入；原评论入口及其判定保持旧基线行为。
  record Evaluation(boolean responseDelayed, boolean resolveDelayed, LocalDateTime resolveDeadlineAt, int resolveSlaDays) {}
  private static final List<String> NON_FIXED_CLOSURE_MEMBERS = List.of("申请延期", "数据异常", "需求如此", "设计如此", "未复现");
  private static final List<String> FIXED_CLOSURE_MEMBERS = List.of("已修复", com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules.EXACT_COMPLETION_MEMBER, "待合并", "未更新");
  static boolean isResponsePlanExempt(String notesText) {
    return IssueResponseTemplateParser.hasParseablePlanSolutionTime(notesText);
  }

  static boolean isResponseDelayedByClock(
      List<String> labels,
      LocalDateTime createdAt,
      String priorityLevel,
      LocalDateTime now) {
    if (createdAt == null || now == null) {
      return IssueRuleSupport.containsAnyLabel(labels, RESPONSE_DELAY_LABELS);
    }
    return Duration.between(createdAt, now).toHours() > responseSlaHours(labels, priorityLevel);
  }

  static LocalDateTime resolveDeadline(
      LocalDateTime createdAt, LocalDateTime plannedResolutionAt) {
    if (createdAt == null) {
      return null;
    }
    return plannedResolutionAt == null
        ? createdAt.plusDays(MAX_RESOLVE_SLA_DAYS) : plannedResolutionAt;
  }

  static int resolveSlaDays(LocalDateTime createdAt, LocalDateTime plannedResolutionAt) {
    if (createdAt == null || plannedResolutionAt == null) {
      return MAX_RESOLVE_SLA_DAYS;
    }
    long days = Duration.between(createdAt, plannedResolutionAt).toDays();
    return Math.toIntExact(Math.max(1L, days));
  }

  static boolean isResolveDelayedWithStatusMembers(
      List<String> labels,
      List<String> statusMembers,
      boolean hasReply,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    if (resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (hasCommonDelayExemption(labels, hasFixCaseNote)) {
      return false;
    }
    if (hasAnyClosureMember(statusMembers, NON_FIXED_CLOSURE_MEMBERS)) {
      return false;
    }
    if (hasReply && hasAnyClosureMember(statusMembers, FIXED_CLOSURE_MEMBERS)) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  private static boolean hasAnyClosureMember(List<String> values, List<String> expectedMembers) {
    if (values == null) {
      return false;
    }
    for (String value : values) {
      String normalized = value == null ? "" : value.trim();
      String status = STATUS_LABEL_PREFIX.matcher(normalized).replaceFirst("").trim();
      if (expectedMembers.contains(normalized) || expectedMembers.contains(status)) {
        return true;
      }
    }
    return false;
  }
}
