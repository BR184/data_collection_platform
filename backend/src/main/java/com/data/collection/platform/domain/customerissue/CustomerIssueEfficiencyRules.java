package com.data.collection.platform.domain.customerissue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户问题响应/解决周期纯规则（E 集合的样本与均值）。
 *
 * <p>响应样本：创建时间与首次调研模板时间均存在；单条 {@code Duration.toHours()} 截断为完整小时，
 * 再对全部有效样本求平均并 {@code Math.round} 为完整小时。
 *
 * <p>解决样本：当前状态成员满足“已修复/完成”且创建/最近完成时间存在；单条毫秒差除以 86400000、
 * HALF_UP 保留 6 位，再对全部有效样本求平均并 HALF_UP 保留 1 位。
 *
 * <p>无有效样本时均值返回 0，有效样本数由调用方按 {@code List.size()} 读取并展示为 0，
 * 不得凭空新增一条 0 时长样本。
 */
public final class CustomerIssueEfficiencyRules {
  private static final BigDecimal MILLIS_PER_DAY = BigDecimal.valueOf(24L * 60L * 60L * 1000L);

  private CustomerIssueEfficiencyRules() {}

  /** 响应样本资格：创建与首次调研模板时间均存在。 */
  public static boolean hasResponseCycle(LocalDateTime createdAt, LocalDateTime researchTemplateTime) {
    return createdAt != null && researchTemplateTime != null;
  }

  /**
   * 解决样本资格：创建与最近完成时间均存在，且当前状态成员精确包含“已修复/完成”。
   *
   * @param fixedStatusMember 由调用方从 {@code IssueStatusMembers} 注入的精确完成成员判定
   */
  public static boolean hasResolutionCycle(
      LocalDateTime createdAt, LocalDateTime fixedLabelTime, boolean fixedStatusMember) {
    return createdAt != null && fixedLabelTime != null && fixedStatusMember;
  }

  /** 单条响应周期（完整小时，Duration.toHours 截断）。 */
  public static long responseCycleHours(LocalDateTime createdAt, LocalDateTime researchTemplateTime) {
    return hasResponseCycle(createdAt, researchTemplateTime)
        ? Duration.between(createdAt, researchTemplateTime).toHours()
        : 0L;
  }

  /** 单条解决周期（天，HALF_UP 保留 6 位）。 */
  public static BigDecimal resolutionCycleDays(LocalDateTime createdAt, LocalDateTime fixedLabelTime) {
    if (createdAt == null || fixedLabelTime == null) {
      return BigDecimal.ZERO;
    }
    long millis = Duration.between(createdAt, fixedLabelTime).toMillis();
    return BigDecimal.valueOf(millis).divide(MILLIS_PER_DAY, 6, RoundingMode.HALF_UP);
  }

  /** 对全部有效响应样本求平均并取整为完整小时；无样本返回 0。 */
  public static long averageResponseHours(List<Long> sampleHours) {
    if (sampleHours == null || sampleHours.isEmpty()) {
      return 0L;
    }
    double average = sampleHours.stream().mapToLong(Long::longValue).average().orElse(0D);
    return Math.round(average);
  }

  /** 对全部有效解决样本求平均，HALF_UP 保留 1 位；无样本返回 1 位小数的 0。 */
  public static BigDecimal averageResolutionDays(List<BigDecimal> sampleDays) {
    if (sampleDays == null || sampleDays.isEmpty()) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    BigDecimal sum = sampleDays.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(sampleDays.size()), 1, RoundingMode.HALF_UP);
  }

  /**
   * 效率范围的历史排除：关闭且标签展示串或状态文本标记“申请否决”或“需求如此”。
   *
   * <p>效率范围包含建议类，也不排除已关闭的“设计如此”，只做这一项关闭排除。
   */
  public static boolean isExcludedFromEfficiencyScope(
      boolean closed, String labelNames, String bugStatus) {
    if (!closed) {
      return false;
    }
    return containsBusinessText(labelNames, "申请否决")
        || containsBusinessText(bugStatus, "申请否决")
        || containsBusinessText(labelNames, "需求如此")
        || containsBusinessText(bugStatus, "需求如此");
  }

  private static boolean containsBusinessText(String value, String keyword) {
    return value != null && value.contains(keyword);
  }
}
