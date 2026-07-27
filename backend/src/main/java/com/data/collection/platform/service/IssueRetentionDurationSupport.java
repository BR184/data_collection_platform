package com.data.collection.platform.service;

import java.time.Duration;
import java.time.LocalDateTime;

/** 客户问题缺陷滞留时长计算规则。 */
final class IssueRetentionDurationSupport {
  private IssueRetentionDurationSupport() {}

  /**
   * 计算客户问题当前尚未闭环的完整滞留小时数。
   *
   * <p>GitLab 已关闭或业务状态已闭环时返回 {@code 0}；否则按调用方提供的统一时点动态计算，
   * 保证同一次页面请求或跨页导出的结果一致。历史解决耗时不属于本字段。</p>
   *
   * @param createdAt 议题提出时间
   * @param asOf 本次页面或导出的统一计算时点
   * @param issueState GitLab 议题状态
   * @param closedAt GitLab 议题关闭时间
   * @param bugStatus 议题测试状态成员文本
   * @return 未闭环议题的滞留完整小时数；未闭环记录的时间输入缺失时返回 {@code null}
   */
  static Long calculate(
      LocalDateTime createdAt,
      LocalDateTime asOf,
      String issueState,
      LocalDateTime closedAt,
      String bugStatus) {
    if (isClosed(issueState, closedAt) || CustomerIssueClosureRules.hasClosureStatus(bugStatus)) {
      return 0L;
    }
    if (createdAt == null || asOf == null) {
      return null;
    }
    return Math.max(0L, Duration.between(createdAt, asOf).toHours());
  }

  private static boolean isClosed(String issueState, LocalDateTime closedAt) {
    return closedAt != null || "closed".equalsIgnoreCase(TextQuerySupport.trimToNull(issueState));
  }
}
