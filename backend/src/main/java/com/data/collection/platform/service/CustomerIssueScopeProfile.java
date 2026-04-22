package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueScopeProfile implements IssueScopeProfile {
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);
  private static final List<String> CUSTOMER_PROJECT_TOKENS =
      List.of("cc_product", "cc-product", "ccproduct");

  private final SystemTestScopeProfile systemTestScopeProfile;

  public CustomerIssueScopeProfile(SystemTestScopeProfile systemTestScopeProfile) {
    this.systemTestScopeProfile = systemTestScopeProfile;
  }

  @Override
  public String key() {
    return "customer-issue";
  }

  @Override
  public boolean matches(IssueScopeContext context) {
    if (context == null || systemTestScopeProfile.matches(context)) {
      return false;
    }
    if (!isCustomerProjectScope(context)) {
      return false;
    }
    return isCreatedAfterStart(context.createdAt());
  }

  private boolean isCreatedAfterStart(LocalDateTime createdAt) {
    return createdAt == null || !createdAt.toLocalDate().isBefore(CUSTOMER_ISSUE_START_DATE);
  }

  private boolean isCustomerProjectScope(IssueScopeContext context) {
    if (context.projectId() != null && context.projectId() == LEGACY_CC_PRODUCT_PROJECT_ID) {
      return true;
    }
    if (containsCustomerToken(context.projectName()) || containsCustomerToken(context.milestoneTitle())) {
      return true;
    }
    return context.labels().stream().anyMatch(this::containsCustomerToken);
  }

  private boolean containsCustomerToken(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, CUSTOMER_PROJECT_TOKENS);
  }
}
