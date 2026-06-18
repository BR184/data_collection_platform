package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import java.util.List;
import org.springframework.util.StringUtils;

public final class IssueDisplayValueSupport {
  public static final String EMPTY_MODULE_LABEL = "未设定模块";
  public static final String EMPTY_SEVERITY_LABEL = "未设定严重程度";
  public static final String EMPTY_PRIORITY_LABEL = "未设定紧急程度";

  private IssueDisplayValueSupport() {}

  public static String displaySeverityLevel(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return EMPTY_SEVERITY_LABEL;
    }
    return switch (rawValue.trim()) {
      case "LEVEL1" -> "一级缺陷";
      case "LEVEL2" -> "二级缺陷";
      case "LEVEL3" -> "三级缺陷";
      case "SUGGESTION" -> "建议类";
      default -> rawValue.trim();
    };
  }

  public static String displaySeverityLevelOrBlank(String rawValue) {
    return StringUtils.hasText(rawValue) ? displaySeverityLevel(rawValue) : "";
  }

  public static String displaySeverityLevelOrFallback(String rawValue, String fallback) {
    return StringUtils.hasText(rawValue) ? displaySeverityLevel(rawValue) : fallback;
  }

  public static List<OptionItemResponse> severityLevelOptions(boolean includeSuggestion) {
    List<String> values = includeSuggestion
        ? List.of("LEVEL1", "LEVEL2", "LEVEL3", "SUGGESTION")
        : List.of("LEVEL1", "LEVEL2", "LEVEL3");
    return values.stream()
        .map(value -> new OptionItemResponse(displaySeverityLevel(value), value))
        .toList();
  }

  public static List<StatisticFilterOption> severityFilterOptions(boolean includeSuggestion) {
    return severityLevelOptions(includeSuggestion).stream()
        .map(option -> new StatisticFilterOption(option.label(), option.value()))
        .toList();
  }

  public static String displayPriorityLevel(String rawValue) {
    return StringUtils.hasText(rawValue) ? rawValue.trim() : EMPTY_PRIORITY_LABEL;
  }
}
