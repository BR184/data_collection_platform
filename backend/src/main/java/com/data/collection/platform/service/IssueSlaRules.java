package com.data.collection.platform.service;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.util.StringUtils;

final class IssueSlaRules {
  private static final List<String> RESPONSE_DELAY_LABELS = List.of("响应已延期");
  // 闭环资格只比较规范状态成员：显式原因前缀、否定词和扩展文本不会进入成员集合。
  private static final List<String> NON_FIXED_CLOSURE_MEMBERS =
      List.of("申请延期", "数据异常", "需求如此", "设计如此", "未复现");
  private static final List<String> FIXED_CLOSURE_MEMBERS =
      List.of("已修复", CustomerIssueActivityTimeRules.EXACT_COMPLETION_MEMBER, "待合并", "未更新");
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
  static boolean isResponseExempt(String notesText) {
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
    return isResponseDelayedByClock(labels, createdAt, priorityLevel, now);
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

  /** 表单来源的延期判定结果；{@code resolveSlaDays} 只用于展示与记录，判定以 {@code resolveDeadlineAt} 为准。 */
  record Evaluation(
      boolean responseDelayed,
      boolean resolveDelayed,
      LocalDateTime resolveDeadlineAt,
      int resolveSlaDays) {}

  /**
   * 一次判定实际采用的业务事实，不区分表单或评论，也不允许两侧来源拼成同一份输入。
   *
   * @param plannedResolutionAt 最新有效响应的计划解决时间；评论侧取已发布事实的期限输入
   * @param responsePlanExempt 响应侧是否已有合法计划时间（D-21）
   * @param hasReply 是否存在有效回复（D-21 豁免与解决闭环）
   */
  record SlaInput(
      LocalDateTime plannedResolutionAt, boolean responsePlanExempt, boolean hasReply) {}

  /**
   * 用统一业务事实判定响应/解决延期，供首次事实与周期重算共用。
   *
   * <p>响应延期豁免只取“有效回复存在”或“某份响应给出合法计划时间”（D-21，方向为最新→更早），
   * 计划字段本身仍整份取自最新响应，不逐字段回退。解决期限取同一份最新响应计划；计划缺失或距创建
   * 超过 18 天时按 18 天默认值。关闭议题不产生延期。
   *
   * @param input 已确定的业务事实（表单或评论，不混源）
   * @param labels 当前来源标签（仅用于响应 SLA 优先级）
   * @param statusMembers 从 {@code 状态：值} 解析出的规范闭环状态成员
   * @param createdAt 议题创建时间
   * @param priorityLevel 当前来源优先级
   * @param now 判定时刻
   * @param open 议题是否仍为打开状态
   * @return 三列延期判定与解决期限/展示天数
   */
  static Evaluation evaluate(
      SlaInput input,
      List<String> labels,
      List<String> statusMembers,
      LocalDateTime createdAt,
      String priorityLevel,
      LocalDateTime now,
      boolean open) {
    LocalDateTime deadline = resolveDeadline(createdAt, input.plannedResolutionAt());
    int resolveSlaDays = resolveSlaDays(createdAt, input.plannedResolutionAt());
    boolean responseExempt = input.hasReply() || input.responsePlanExempt();
    boolean responseDelayed =
        open && !responseExempt && isResponseDelayedByClock(labels, createdAt, priorityLevel, now);
    boolean resolveDelayed =
        open && isResolveDelayed(statusMembers, input.hasReply(), deadline, now);
    return new Evaluation(responseDelayed, resolveDelayed, deadline, resolveSlaDays);
  }

  /**
   * 按最新响应的计划解决时间推导解决期限。
   *
   * @param createdAt 议题创建时间；为空时返回 {@code null}
   * @param plannedResolutionAt 最新响应的计划解决时间；为空时按 18 天默认值
   * @return 落库的解决期限
   */
  static LocalDateTime resolveDeadline(
      LocalDateTime createdAt, LocalDateTime plannedResolutionAt) {
    if (createdAt == null) {
      return null;
    }
    LocalDateTime defaultDeadline = createdAt.plusDays(MAX_RESOLVE_SLA_DAYS);
    if (plannedResolutionAt == null) {
      return defaultDeadline;
    }
    long daysBetween = Duration.between(createdAt, plannedResolutionAt).toDays();
    if (daysBetween <= MAX_RESOLVE_SLA_DAYS) {
      return plannedResolutionAt;
    }
    return defaultDeadline;
  }

  /**
   * 按最新响应计划推导展示用解决天数，取值 1—18。
   *
   * @param createdAt 议题创建时间
   * @param plannedResolutionAt 最新响应的计划解决时间
   * @return 解决天数；缺创建时间或缺计划时为 18
   */
  static int resolveSlaDays(LocalDateTime createdAt, LocalDateTime plannedResolutionAt) {
    if (createdAt == null || plannedResolutionAt == null) {
      return MAX_RESOLVE_SLA_DAYS;
    }
    long days = Duration.between(createdAt, plannedResolutionAt).toDays();
    return (int) Math.min(MAX_RESOLVE_SLA_DAYS, Math.max(1L, days));
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
      List<String> statusMembers, LocalDateTime resolveDeadlineAt, LocalDateTime now) {
    if (resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (hasAnyStatusMember(statusMembers, NON_FIXED_CLOSURE_MEMBERS)
        || hasAnyStatusMember(statusMembers, FIXED_CLOSURE_MEMBERS)) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  static boolean isResolveDelayed(
      List<String> statusMembers,
      boolean hasFixCaseNote,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime now) {
    if (resolveDeadlineAt == null || now == null) {
      return false;
    }
    if (hasAnyStatusMember(statusMembers, NON_FIXED_CLOSURE_MEMBERS)) {
      return false;
    }
    if (hasFixCaseNote && hasAnyStatusMember(statusMembers, FIXED_CLOSURE_MEMBERS)) {
      return false;
    }
    return now.isAfter(resolveDeadlineAt);
  }

  private static boolean hasAnyStatusMember(List<String> statusMembers, List<String> expectedMembers) {
    return statusMembers != null && statusMembers.stream().anyMatch(expectedMembers::contains);
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
