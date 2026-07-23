package com.data.collection.platform.service;

/**
 * Keeps the customer-issue testing-phase presentation value separate from the nullable fact.
 *
 * <p>The GitLab source may not carry a testing-phase label. The fact remains empty in that case;
 * this type provides the stable value used by the CC_PRODUCT page and its filters.</p>
 */
final class CustomerIssueTestingPhaseSupport {
  static final String UNSPECIFIED_DISPLAY_VALUE = "未设定测试阶段";

  private CustomerIssueTestingPhaseSupport() {}

  static String display(String rawTestingPhase) {
    String normalized = TextQuerySupport.trimToNull(rawTestingPhase);
    return normalized == null ? UNSPECIFIED_DISPLAY_VALUE : normalized;
  }

  static boolean isUnspecifiedFilter(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return UNSPECIFIED_DISPLAY_VALUE.equals(normalized);
  }

  static boolean matchesFilter(String rawTestingPhase, String filterValue) {
    String normalizedFilter = TextQuerySupport.trimToNull(filterValue);
    if (normalizedFilter == null) {
      return true;
    }
    String normalizedRaw = TextQuerySupport.trimToNull(rawTestingPhase);
    if (isUnspecifiedFilter(normalizedFilter)) {
      return normalizedRaw == null;
    }
    return normalizedRaw != null && normalizedRaw.equalsIgnoreCase(normalizedFilter);
  }
}
