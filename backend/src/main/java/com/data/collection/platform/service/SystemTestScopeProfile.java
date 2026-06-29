package com.data.collection.platform.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestScopeProfile implements IssueScopeProfile {
  private static final long LEGACY_CROWN_CAD_PROJECT_ID = 9L;
  private static final List<String> SYSTEM_TEST_TOKENS = List.of("系统测试", "回归测试");

  @Override
  public String key() {
    return "system-test";
  }

  @Override
  public boolean matches(IssueScopeContext context) {
    if (context == null) {
      return false;
    }
    return context.projectId() != null
        && context.projectId() == LEGACY_CROWN_CAD_PROJECT_ID
        && hasScope(context.testingPhase());
  }

  private boolean hasScope(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, SYSTEM_TEST_TOKENS);
  }
}
