package com.data.collection.platform.domain.customerissue;

/**
 * 客户问题修复/申请延期纯谓词（F / P / A）。
 *
 * <p>三者是彼此独立的业务谓词，不得互相替代或强行互斥：
 *
 * <ul>
 *   <li>F（整体已修复）：bug_status 含“已修复”或“待合并”或“未更新”
 *   <li>P（优先级已修复）：bug_status 含“已修复/完成”或“未复现”，或 GitLab 已关闭
 *   <li>A（申请延期）：bug_status 含“申请延期”
 * </ul>
 */
public final class CustomerIssueMetricRules {
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

  private static boolean contains(String value, String token) {
    return value != null && value.contains(token);
  }
}
