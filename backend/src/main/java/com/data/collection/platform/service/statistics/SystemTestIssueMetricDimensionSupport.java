package com.data.collection.platform.service.statistics;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Shared semantic dimensions for the system-test multi-board and its record drill-down.
 * Keeping these rules here guarantees that a chart point and the detail filter identify
 * the same issue set instead of re-interpreting the displayed label in the frontend.
 */
public final class SystemTestIssueMetricDimensionSupport {
  public static final List<String> MAJOR_CAUSES =
      List.of("需求阶段", "设计阶段", "编码问题", "打包问题", "依赖问题", "精度问题");
  public static final List<String> DELAY_CAUSES =
      List.of("技术卡点", "方案卡点", "资源卡点", "数据异常", "算法问题", "机制问题", "计算效率");

  private SystemTestIssueMetricDimensionSupport() {}

  public static String metricSeverity(
      boolean excluded,
      String exclusionReason,
      String severityLevel,
      String category) {
    if (SystemTestSuggestionMetricSupport.isSuggestionColumnIssue(
        excluded, exclusionReason, severityLevel, category)) {
      return "SUGGESTION";
    }
    if (!SystemTestSuggestionMetricSupport.isRegularMetricIssue(
        excluded, exclusionReason, severityLevel, category)) {
      return "";
    }
    String value = severityLevel == null ? "" : severityLevel;
    if (value.contains("一级") || value.equalsIgnoreCase("LEVEL1") || value.equalsIgnoreCase("LEVEL 1")) {
      return "LEVEL1";
    }
    if (value.contains("二级") || value.equalsIgnoreCase("LEVEL2") || value.equalsIgnoreCase("LEVEL 2")) {
      return "LEVEL2";
    }
    if (value.contains("三级") || value.equalsIgnoreCase("LEVEL3") || value.equalsIgnoreCase("LEVEL 3")) {
      return "LEVEL3";
    }
    return "";
  }

  public static boolean regularMetric(
      boolean excluded,
      String exclusionReason,
      String severityLevel,
      String category) {
    return SystemTestSuggestionMetricSupport.isRegularMetricIssue(
        excluded, exclusionReason, severityLevel, category);
  }

  public static List<String> matchingCauseMetricKeys(String reasonCategory, String labelsText) {
    return DefectCauseMetricCatalog.METRICS.stream()
        .filter(metric -> DefectCauseMetricCatalog.containsAny(reasonCategory, metric.tokens())
            || DefectCauseMetricCatalog.containsAny(labelsText, metric.tokens()))
        .map(DefectCauseMetricCatalog.Metric::key)
        .toList();
  }

  public static String majorCause(String reasonCategory, String labelsText) {
    String text = safe(reasonCategory) + " " + safe(labelsText);
    for (String cause : MAJOR_CAUSES) {
      if ("需求阶段".equals(cause) && containsAny(text, "需求阶段", "需求问题")) {
        return cause;
      }
      if ("设计阶段".equals(cause) && containsAny(text, "设计阶段", "设计问题")) {
        return cause;
      }
      if ("编码问题".equals(cause) && containsAny(text, "编码问题", "编码规范", "编码逻辑")) {
        return cause;
      }
      if (text.contains(cause)) {
        return cause;
      }
    }
    return "";
  }

  public static String delayCause(String delayCause, String delayReason, String labelsText) {
    String text = safe(delayCause) + " " + safe(delayReason) + " " + safe(labelsText);
    return DELAY_CAUSES.stream().filter(text::contains).findFirst().orElse("");
  }

  public static boolean matchesCauseMetric(String metricKey, String reasonCategory, String labelsText) {
    DefectCauseMetricCatalog.Metric metric = DefectCauseMetricCatalog.get(metricKey);
    return DefectCauseMetricCatalog.containsAny(reasonCategory, metric.tokens())
        || DefectCauseMetricCatalog.containsAny(labelsText, metric.tokens());
  }

  public static boolean rollback(boolean regression, String title, String labelsText) {
    return regression || safe(title).contains("回退") || safe(labelsText).contains("回退");
  }

  private static boolean containsAny(String text, String... tokens) {
    if (!StringUtils.hasText(text)) {
      return false;
    }
    for (String token : tokens) {
      if (text.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
