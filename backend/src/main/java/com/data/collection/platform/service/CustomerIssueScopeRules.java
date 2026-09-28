package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

final class CustomerIssueScopeRules {
  // 项目与创建下限是客户问题范围的唯一事实源，直接引用窄事实读取入口公开的常量，不在此重复字面量。
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = CustomerIssueFactQueryService.CC_PRODUCT_PROJECT_ID;
  static final LocalDate CUSTOMER_ISSUE_START_DATE =
      CustomerIssueFactQueryService.CUSTOMER_ISSUE_START_DATE;

  private CustomerIssueScopeRules() {
  }

  static boolean isCustomerProject(Long projectId, String projectName) {
    return projectId != null && projectId == LEGACY_CC_PRODUCT_PROJECT_ID;
  }

  static boolean isInCustomerIssueDateRange(LocalDateTime createdAt) {
    return createdAt == null || !createdAt.toLocalDate().isBefore(CUSTOMER_ISSUE_START_DATE);
  }
}
