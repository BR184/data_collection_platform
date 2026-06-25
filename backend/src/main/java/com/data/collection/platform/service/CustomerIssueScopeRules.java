package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class CustomerIssueScopeRules {
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);
  static final List<String> CUSTOMER_PROJECT_TOKENS =
      List.of("cc_product", "cc-product", "ccproduct");

  private CustomerIssueScopeRules() {
  }

  static boolean isCustomerProject(Long projectId, String projectName) {
    return (projectId != null && projectId == LEGACY_CC_PRODUCT_PROJECT_ID)
        || containsCustomerProjectToken(projectName);
  }

  static boolean isInCustomerIssueDateRange(LocalDateTime createdAt) {
    return createdAt == null || !createdAt.toLocalDate().isBefore(CUSTOMER_ISSUE_START_DATE);
  }

  static boolean containsCustomerProjectToken(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    return CUSTOMER_PROJECT_TOKENS.stream().anyMatch(normalized::contains);
  }
}
