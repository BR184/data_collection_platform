package com.data.collection.platform.service;

import java.util.List;

/** 客户问题最终闭环状态的统一成员规则。 */
final class CustomerIssueClosureRules {
  private static final String FIXED_CLOSURE_STATUS = "已修复/完成";
  private static final List<String> NON_FIXED_CLOSURE_STATUSES =
      List.of("申请延期", "数据异常", "需求如此", "设计如此", "未复现");

  private CustomerIssueClosureRules() {}

  static boolean hasClosureStatus(String bugStatus) {
    return IssueStatusMembers.parse(bugStatus).stream()
        .anyMatch(
            status ->
                FIXED_CLOSURE_STATUS.equals(status)
                    || NON_FIXED_CLOSURE_STATUSES.contains(status));
  }

  static boolean hasNonFixedClosureLabel(List<String> labels) {
    return IssueRuleSupport.containsAnyLabel(labels, NON_FIXED_CLOSURE_STATUSES);
  }
}
