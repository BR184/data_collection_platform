package com.data.collection.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class IntegrationTestFactRules {
  private static final Pattern FIRST_INTEGER_PATTERN = Pattern.compile("-?\\d+");
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
    Matcher matcher = FIRST_INTEGER_PATTERN.matcher(value);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.valueOf(matcher.group());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static ValidationResult validateRecord(
      Integer executeCase,
      Integer passCase,
      Integer notPassCaseNow,
      Integer notPassCase,
      Integer problemCase,
      Integer exceptionCount) {
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
    List<String> negativeFields = new ArrayList<>();
    addNegativeField(negativeFields, "执行用例总数", executeCase);
    addNegativeField(negativeFields, "通过用例数", passCase);
    addNegativeField(negativeFields, "本次未通过用例数", notPassCaseNow);
    addNegativeField(negativeFields, "初始未通过用例数", notPassCase);
    addNegativeField(negativeFields, "本次问题用例数", problemCase);
    addNegativeField(negativeFields, "用例外问题数", exceptionCount);
    if (!negativeFields.isEmpty()) {
      return new ValidationResult(
          false, "PARTIAL", String.join("、", negativeFields) + "不能为负数");
    }
    if (!executeCase.equals(passCase + notPassCaseNow)) {
      return new ValidationResult(
          false, "PARSED", "执行用例总数应等于通过用例数 + 本次未通过用例数");
    }
    return new ValidationResult(true, "PARSED", null);
  }

  private static void addNegativeField(List<String> negativeFields, String fieldName, Integer value) {
    if (value != null && value < 0) {
      negativeFields.add(fieldName);
    }
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
