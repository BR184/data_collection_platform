package com.data.collection.platform.service;

import java.time.Duration;
import java.time.LocalDateTime;

/** 客户问题缺陷滞留时长计算规则。 */
final class IssueRetentionDurationSupport {
  private IssueRetentionDurationSupport() {}

  /**
   * 按调用方提供的统一时点计算从提出时间开始的完整小时数。
   *
   * @param createdAt 议题提出时间
   * @param asOf 本次页面或导出的统一计算时点
   * @return 滞留完整小时数；提出时间缺失时返回 {@code null}
   */
  static Long hoursBetween(LocalDateTime createdAt, LocalDateTime asOf) {
    if (createdAt == null || asOf == null) {
      return null;
    }
    return Math.max(0L, Duration.between(createdAt, asOf).toHours());
  }
}
