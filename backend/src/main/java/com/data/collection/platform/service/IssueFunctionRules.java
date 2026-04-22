package com.data.collection.platform.service;

import org.springframework.util.StringUtils;

final class IssueFunctionRules {
  private IssueFunctionRules() {
  }

  static String normalizeFunctionName(String title) {
    if (!StringUtils.hasText(title)) {
      return null;
    }
    String normalized = title.trim();
    String fullWidth = extractBracketValue(normalized, '【', '】');
    return fullWidth == null ? extractBracketValue(normalized, '[', ']') : fullWidth;
  }

  private static String extractBracketValue(String value, char start, char end) {
    if (value.length() < 3 || value.charAt(0) != start) {
      return null;
    }
    int endIndex = value.indexOf(end, 1);
    if (endIndex <= 1) {
      return null;
    }
    String functionName = value.substring(1, endIndex).trim();
    return StringUtils.hasText(functionName) ? functionName : null;
  }
}
