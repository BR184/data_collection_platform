package com.data.collection.platform.service;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import java.util.List;

/** 客户问题最终闭环状态的统一成员规则。 */
final class CustomerIssueClosureRules {
  private static final String FIXED_CLOSURE_STATUS =
      CustomerIssueActivityTimeRules.EXACT_COMPLETION_MEMBER;
  private static final List<String> NON_FIXED_CLOSURE_STATUSES =
      List.of("申请延期", "数据异常", "需求如此", "设计如此", "未复现");
  private static final List<String> CLOSURE_STATUSES =
      java.util.stream.Stream.concat(
              java.util.stream.Stream.of(FIXED_CLOSURE_STATUS),
              NON_FIXED_CLOSURE_STATUSES.stream())
          .toList();

  private CustomerIssueClosureRules() {}

  static boolean hasClosureStatus(String bugStatus) {
    return IssueStatusMembers.parse(bugStatus).stream()
        .anyMatch(CLOSURE_STATUSES::contains);
  }

  static List<String> closureStatuses() {
    return CLOSURE_STATUSES;
  }
}
