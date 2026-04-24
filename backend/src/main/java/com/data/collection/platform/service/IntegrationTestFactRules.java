package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

final class IntegrationTestFactRules {
  private static final Map<String, List<String>> FUNCTION_LABEL_ALIASES =
      Map.of(
          "新功能", List.of("新功能", "NEW_FUNCTION"),
          "老功能", List.of("老功能", "OLD_FUNCTION"),
          "增强功能", List.of("增强功能", "ENHANCE_FUNCTION"));

  private IntegrationTestFactRules() {}

  static List<String> extractFunctionLabels(List<String> labels) {
    if (labels == null || labels.isEmpty()) {
      return List.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (String label : labels) {
      String normalized = normalizeFunctionLabel(label);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return List.copyOf(result);
  }

  static String normalizeFunctionLabel(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String compact = compactKey(normalized);
    for (Map.Entry<String, List<String>> entry : FUNCTION_LABEL_ALIASES.entrySet()) {
      for (String alias : entry.getValue()) {
        if (compact.equals(compactKey(alias))) {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  static boolean matchesKey(String source, String... aliases) {
    String compactSource = compactKey(source);
    if (compactSource == null) {
      return false;
    }
    for (String alias : aliases) {
      String compactAlias = compactKey(alias);
      if (compactAlias != null && compactSource.contains(compactAlias)) {
        return true;
      }
    }
    return false;
  }

  static Integer parseNumericValue(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String digits = value.replaceAll("[^0-9-]", "");
    if (!StringUtils.hasText(digits)) {
      return null;
    }
    try {
      return Integer.valueOf(digits);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static ValidationResult validateRecord(
      Integer executeCase, Integer passCase, Integer notPassCaseNow) {
    List<String> missingFields = new ArrayList<>();
    if (executeCase == null) {
      missingFields.add("执行用例总数");
    }
    if (passCase == null) {
      missingFields.add("通过用例数");
    }
    if (notPassCaseNow == null) {
      missingFields.add("本次未通过用例数");
    }
    if (!missingFields.isEmpty()) {
      return new ValidationResult(
          false, "PARTIAL", "缺少核心统计字段：" + String.join("、", missingFields));
    }
    if (executeCase < 0 || passCase < 0 || notPassCaseNow < 0) {
      return new ValidationResult(false, "PARTIAL", "核心统计字段不能为负数");
    }
    if (!executeCase.equals(passCase + notPassCaseNow)) {
      return new ValidationResult(
          false, "PARSED", "执行用例总数应等于通过用例数 + 本次未通过用例数");
    }
    return new ValidationResult(true, "PARSED", null);
  }

  private static String compactKey(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    return normalized
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s:：*\\-_/()（）\\[\\]{}<>·,，。；;]+", "");
  }

  record ValidationResult(boolean legal, String parseStatus, String validationReason) {}
}
