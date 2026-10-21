package com.data.collection.platform.domain.customerissue;

import java.util.List;

/**
 * 客户问题修复/申请延期纯谓词（F / P / A）与校准剔除资格。
 *
 * <p>三者是彼此独立的业务谓词，不得互相替代或强行互斥：
 *
 * <ul>
 *   <li>F（整体已修复）：bug_status 含“已修复”或“待合并”或“未更新”
 *   <li>P（优先级已修复）：bug_status 含“已修复/完成”或“未复现”，或 GitLab 已关闭
 *   <li>A（申请延期）：bug_status 含“申请延期”
 * </ul>
 *
 * <p>校准口径（CI-24—CI-26）：E 为 D 中状态成员精确命中“数据异常”或“未复现”的议题并集，V = D 去除 E，
 * 分子与分母同时使用 V。F、P、A 的既有判据不被校准改写，申请延期不额外剔除。
 */
public final class CustomerIssueMetricRules {
  /** 剔除状态成员名（CI-47）；由现有状态成员解析器精确匹配，不做子串猜测。 */
  public static final String DATA_EXCEPTION_MEMBER = "数据异常";
  /** 剔除状态成员名（CI-48）；可与其它状态同时命中，不是互斥桶。 */
  public static final String NOT_REPRODUCED_MEMBER = "未复现";

  private CustomerIssueMetricRules() {}

  /** 整体修复谓词 F。 */
  public static boolean isFixedBySummary(String bugStatus) {
    return contains(bugStatus, "已修复") || contains(bugStatus, "待合并") || contains(bugStatus, "未更新");
  }

  /** 优先级修复谓词 P。closed 表示 GitLab 已关闭。 */
  public static boolean isPriorityFixed(String bugStatus, boolean closed) {
    return contains(bugStatus, "已修复/完成") || contains(bugStatus, "未复现") || closed;
  }

  /** 申请延期状态谓词 A。 */
  public static boolean hasAppliedDelay(String bugStatus) {
    return contains(bugStatus, "申请延期");
  }

  /** 数据异常状态计数资格（CI-47）。 */
  public static boolean hasDataException(List<String> statusMembers) {
    return hasMember(statusMembers, DATA_EXCEPTION_MEMBER);
  }

  /** 未复现状态计数资格（CI-48）。 */
  public static boolean hasNotReproduced(List<String> statusMembers) {
    return hasMember(statusMembers, NOT_REPRODUCED_MEMBER);
  }

  /**
   * 校准集合 V 资格：议题是否留在校准分子与分母之中。
   *
   * <p>两类剔除状态取并集，同一议题命中两者也只剔除一次；未修复议题仍按既有 D 范围进入分母。
   *
   * @param statusMembers 由现有状态成员解析器得到的成员列表
   * @return 未命中任一剔除状态时为 {@code true}
   */
  public static boolean keepsCalibrationPopulation(List<String> statusMembers) {
    return !hasDataException(statusMembers) && !hasNotReproduced(statusMembers);
  }

  private static boolean hasMember(List<String> statusMembers, String expected) {
    return statusMembers != null && statusMembers.stream().anyMatch(expected::equalsIgnoreCase);
  }

  private static boolean contains(String value, String token) {
    return value != null && value.contains(token);
  }
}
