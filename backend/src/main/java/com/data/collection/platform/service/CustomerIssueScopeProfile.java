package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class CustomerIssueScopeProfile implements IssueScopeProfile {
  static final long LEGACY_CC_PRODUCT_PROJECT_ID = CustomerIssueScopeRules.LEGACY_CC_PRODUCT_PROJECT_ID;
  static final LocalDate CUSTOMER_ISSUE_START_DATE = CustomerIssueScopeRules.CUSTOMER_ISSUE_START_DATE;

  @Override
  public String key() {
    return "customer-issue";
  }

  @Override
  public boolean matches(IssueScopeContext context) {
    if (context == null) {
      return false;
    }
    if (!isCustomerProjectScope(context)) {
      return false;
    }
    return isCreatedAfterStart(context.createdAt());
  }

  private boolean isCreatedAfterStart(LocalDateTime createdAt) {
    return CustomerIssueScopeRules.isInCustomerIssueDateRange(createdAt);
  }

  private boolean isCustomerProjectScope(IssueScopeContext context) {
    return CustomerIssueScopeRules.isCustomerProject(context.projectId(), context.projectName());
  }
}
