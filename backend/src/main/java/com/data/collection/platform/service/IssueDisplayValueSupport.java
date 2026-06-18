package com.data.collection.platform.service;

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

  public static String displayPriorityLevel(String rawValue) {
    return StringUtils.hasText(rawValue) ? rawValue.trim() : EMPTY_PRIORITY_LABEL;
  }
}
