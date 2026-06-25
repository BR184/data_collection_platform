package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

final class IssueLabelRules {
  private static final Map<String, List<String>> SEVERITY_TOKENS = IssueRuleSupport.ordered(
      Map.entry("LEVEL1", List.of("一级缺陷", "一级严重")),
      Map.entry("LEVEL2", List.of("二级缺陷", "二级严重")),
      Map.entry("LEVEL3", List.of("三级缺陷", "三级严重")),
      Map.entry("SUGGESTION", List.of("建议", "需求", "需求如此")));
  private static final Map<String, List<String>> PRIORITY_TOKENS = IssueRuleSupport.ordered(
      Map.entry("P1", List.of("P1")),
      Map.entry("P2", List.of("P2")),
      Map.entry("P3", List.of("P3")));
  private static final List<String> EXCLUDED_LABELS = List.of("功能屏蔽", "已拒绝", "建议");
  private static final List<String> CLOSED_EXCLUSION_LABELS = List.of("申请否决", "需求如此", "设计如此");
  private static final List<String> FIXED_LABELS = List.of("已修复", "已修复/完成", "待合并");
  private static final List<String> UNREPRODUCED_LABELS = List.of("未复现");
  private static final List<String> LEGACY_BUG_STATUS_LABELS = List.of(
      "已修复/完成",
      "已修复",
      "待合并",
      "未更新",
      "未复现",
      "未修复",
      "申请延期",
      "历史遗留",
      "申请否决",
      "数据异常",
      "需求如此",
      "设计如此",
      "已拒绝");
  private static final List<String> SYSTEM_TEST_LABEL_TOKENS = List.of("系统测试", "回归测试");
  private static final List<String> TESTING_PHASE_TOKENS = List.of("系统测试", "回归测试", "集成测试");
  private static final List<String> LEGACY_PHASE_KEYWORD_TOKENS = List.of("系统测试", "回归测试", "集成测试");
  private static final List<Character> LEGACY_PREFIX_SEPARATORS = List.of('：', ':', '-');
  private static final List<String> LEGACY_PREFIXES = List.of(
      "模块",
      "工具箱",
      "软件",
      "项目",
      "状态",
      "测试阶段",
      "严重程度",
      "类别");
  private static final List<String> LEGACY_URGENCY_LABELS = List.of("P1", "P2", "P3");
  private static final List<String> LEGACY_DELAY_CAUSE_LABELS = List.of(
      "技术卡点",
      "方案卡点",
      "资源卡点",
      "数据异常",
      "算法问题",
      "机制问题",
      "计算效率");
  private IssueLabelRules() {
  }

  static String normalizeSeverityLevel(List<String> labels) {
    for (Map.Entry<String, List<String>> entry : SEVERITY_TOKENS.entrySet()) {
      if (IssueRuleSupport.containsAnyLabel(labels, entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  static String normalizeSeverityAlias(List<String> labels) {
    for (List<String> aliases : SEVERITY_TOKENS.values()) {
      for (String alias : aliases) {
        if (IssueRuleSupport.hasLabel(labels, alias)) {
          return alias;
        }
      }
    }
    return null;
  }

  static String normalizePriorityLevel(List<String> labels) {
    for (Map.Entry<String, List<String>> entry : PRIORITY_TOKENS.entrySet()) {
      if (IssueRuleSupport.containsAnyLabel(labels, entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  static boolean isExcluded(List<String> labels, boolean closed) {
    return exclusionReason(labels, closed) != null;
  }

  static String exclusionReason(List<String> labels, boolean closed) {
    for (String excluded : EXCLUDED_LABELS) {
      if (IssueRuleSupport.hasLabel(labels, excluded)) {
        return excluded;
      }
    }
    if (closed) {
      for (String excluded : CLOSED_EXCLUSION_LABELS) {
        if (IssueRuleSupport.hasLabel(labels, excluded)) {
          return excluded + "+Closed";
        }
      }
    }
    return null;
  }

  static boolean isFixed(List<String> labels, boolean closed) {
    if (IssueRuleSupport.containsAnyLabel(labels, FIXED_LABELS)) {
      return true;
    }
    return closed && IssueRuleSupport.containsAnyLabel(labels, UNREPRODUCED_LABELS);
  }

  static String normalizeBugStatus(List<String> labels, boolean closed) {
    List<String> legacyStatuses = parseLegacyLabelMap(labels).getOrDefault("状态", List.of());
    LinkedHashSet<String> statuses = new LinkedHashSet<>();
    for (String status : legacyStatuses) {
      if (StringUtils.hasText(status)) {
        statuses.add(status.trim());
      }
    }
    for (String label : labels) {
      for (String status : LEGACY_BUG_STATUS_LABELS) {
        if (StringUtils.hasText(label) && label.trim().equals(status)) {
          statuses.add(status);
        }
      }
    }
    if (!statuses.isEmpty()) {
      return String.join("、", statuses);
    }
    return closed ? "已关闭" : "未关闭";
  }

  static String normalizeTestingPhase(List<String> labels) {
    return IssueRuleSupport.firstMatchingLabel(labels, TESTING_PHASE_TOKENS);
  }

  static String normalizeSystemTestLabel(List<String> labels) {
    return IssueRuleSupport.firstMatchingLabel(labels, SYSTEM_TEST_LABEL_TOKENS);
  }

  static List<String> normalizeModuleNames(List<String> labels) {
    Set<String> modules = new LinkedHashSet<>();
    List<String> legacyModules = parseLegacyLabelMap(labels).getOrDefault("模块", List.of());
    for (String label : legacyModules) {
      String moduleName = normalizeModuleValue(label);
      if (moduleName != null) {
        modules.add(moduleName);
      }
    }
    return List.copyOf(modules);
  }

  static List<String> normalizeMergeRequestModuleNames(List<String> labels) {
    Set<String> modules = new LinkedHashSet<>();
    for (String label : labels) {
      String moduleName = extractMergeRequestModuleName(label);
      if (moduleName != null) {
        modules.add(moduleName);
      }
    }
    return List.copyOf(modules);
  }

  static Map<String, List<String>> parseLegacyLabelMap(List<String> labels) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (String label : labels) {
      String normalizedLabel = IssueRuleSupport.normalizeText(label);
      if (normalizedLabel == null) {
        continue;
      }
      String trimmed = label.trim();
      LegacyPrefixedLabel prefixedLabel = parseLegacyPrefixedLabel(trimmed);
      if (prefixedLabel != null) {
        appendLegacyLabelValue(result, prefixedLabel.groupName(), prefixedLabel.value());
        continue;
      }
      if (IssueRuleSupport.containsToken(trimmed, LEGACY_PHASE_KEYWORD_TOKENS)) {
        appendLegacyLabelValue(result, "测试阶段", trimmed);
        continue;
      }
      if (LEGACY_URGENCY_LABELS.contains(trimmed)) {
        appendLegacyLabelValue(result, "紧急程度", trimmed);
        continue;
      }
      if (LEGACY_DELAY_CAUSE_LABELS.contains(trimmed)) {
        appendLegacyLabelValue(result, "延期原因", trimmed);
      }
    }
    return result.entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue()),
            (left, right) -> left,
            LinkedHashMap::new));
  }

  private static LegacyPrefixedLabel parseLegacyPrefixedLabel(String label) {
    int separatorIndex = firstLegacyPrefixSeparatorIndex(label);
    if (separatorIndex <= 0) {
      return null;
    }
    String prefix = label.substring(0, separatorIndex).trim();
    String value = label.substring(separatorIndex + 1).trim();
    String normalizedValue = IssueRuleSupport.normalizeText(value);
    if (!LEGACY_PREFIXES.contains(prefix)
        || normalizedValue == null
        || startsWithLegacyPrefixSeparator(normalizedValue)) {
      return null;
    }
    return new LegacyPrefixedLabel(prefix.equals("工具箱") ? "模块" : prefix, value);
  }

  private static int firstLegacyPrefixSeparatorIndex(String label) {
    int separatorIndex = -1;
    for (char separator : LEGACY_PREFIX_SEPARATORS) {
      int currentIndex = label.indexOf(separator);
      if (currentIndex > 0 && (separatorIndex < 0 || currentIndex < separatorIndex)) {
        separatorIndex = currentIndex;
      }
    }
    return separatorIndex;
  }

  private static boolean startsWithLegacyPrefixSeparator(String value) {
    for (char separator : LEGACY_PREFIX_SEPARATORS) {
      if (value.charAt(0) == separator) {
        return true;
      }
    }
    return false;
  }

  private static void appendLegacyLabelValue(Map<String, List<String>> result, String groupName, String value) {
    List<String> values = result.computeIfAbsent(groupName, ignored -> new ArrayList<>());
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  private static String extractMergeRequestModuleName(String label) {
    String trimmed = label.trim();
    LegacyPrefixedLabel prefixedLabel = parseLegacyPrefixedLabel(trimmed);
    if (prefixedLabel != null && "模块".equals(prefixedLabel.groupName())) {
      return normalizeModuleValue(prefixedLabel.value());
    }
    return null;
  }

  private static String normalizeModuleValue(String value) {
    String cleaned = value.trim();
    String normalized = IssueRuleSupport.normalizeText(cleaned);
    return normalized == null ? null : cleaned;
  }

  private record LegacyPrefixedLabel(String groupName, String value) {
  }
}
