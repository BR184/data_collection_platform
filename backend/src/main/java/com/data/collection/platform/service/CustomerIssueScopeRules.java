package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

final class CustomerIssueScopeRules {
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);

  private CustomerIssueScopeRules() {
  }

  static boolean isCustomerProject(Long projectId, String projectName) {
    return projectId != null && projectId == LEGACY_CC_PRODUCT_PROJECT_ID;
  }

  static boolean isInCustomerIssueDateRange(LocalDateTime createdAt) {
    return createdAt == null || !createdAt.toLocalDate().isBefore(CUSTOMER_ISSUE_START_DATE);
  }
}
