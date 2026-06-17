package com.data.collection.platform.service;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

final class CustomerIssueIllegalReasonSupport {
  static final String MISSING_SEVERITY = IssueClassificationRules.MISSING_SEVERITY;
  static final String MISSING_MODULE = IssueClassificationRules.MISSING_MODULE;
  static final String TEMPLATE_NOT_FOLLOWED = IssueClassificationRules.TEMPLATE_NOT_FOLLOWED;
  static final String NON_UNIQUE_REASON = IssueClassificationRules.NON_UNIQUE_REASON;
  static final String INVALID_RESEARCH_TEMPLATE = IssueClassificationRules.INVALID_RESEARCH_TEMPLATE;
  static final List<String> SUPPORTED_REASONS =
      List.of(MISSING_SEVERITY, MISSING_MODULE, TEMPLATE_NOT_FOLLOWED, NON_UNIQUE_REASON, INVALID_RESEARCH_TEMPLATE);

  private static final Map<String, String> ALIASES =
      Map.of(
          "缺失严重程度", MISSING_SEVERITY,
          "未设定严重程度", MISSING_SEVERITY,
          "缺失模块", MISSING_MODULE,
          "未设定模块", MISSING_MODULE,
          "未按照模板回复", TEMPLATE_NOT_FOLLOWED,
          "缺陷原因不唯一", NON_UNIQUE_REASON,
          "未按照要求填写缺陷调研模板", INVALID_RESEARCH_TEMPLATE);

  private CustomerIssueIllegalReasonSupport() {}

  static String normalize(String reason) {
    String normalized = TextQuerySupport.trimToNull(reason);
    if (normalized == null) {
      return null;
    }
    return ALIASES.get(normalized);
  }

  static boolean matches(String actualReason, String expectedReason) {
    String normalizedExpected = normalize(expectedReason);
    return normalizedExpected == null
        || normalizedExpected.equals(normalize(actualReason));
  }

  static List<String> supportedRawReasons() {
    return List.copyOf(ALIASES.keySet());
  }

  static List<String> rawReasonsFor(String expectedReason) {
    String normalizedExpected = normalize(expectedReason);
    if (normalizedExpected == null) {
      return List.of();
    }
    return ALIASES.entrySet().stream()
        .filter(entry -> normalizedExpected.equals(entry.getValue()))
        .map(Entry::getKey)
        .toList();
  }
}
