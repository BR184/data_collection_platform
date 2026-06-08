package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
  private static final List<String> CLOSED_EXCLUSION_LABELS = List.of("申请否决", "数据异常", "需求如此");
  private static final List<String> FIXED_LABELS = List.of("已修复", "已修复/完成", "待合并");
  private static final List<String> UNREPRODUCED_LABELS = List.of("未复现");
  private static final List<String> SYSTEM_TEST_LABEL_TOKENS = List.of("系统测试", "回归测试");
  private static final List<String> TESTING_PHASE_TOKENS = List.of("系统测试", "回归测试", "联调测试", "冒烟测试", "集成测试");
  private static final List<String> LEGACY_PHASE_KEYWORD_TOKENS = List.of("系统测试", "回归测试", "集成测试");
  private static final List<String> FUNCTION_LABEL_TOKENS = List.of(
      "新功能",
      "老功能",
      "增强功能",
      "NEW_FUNCTION",
      "OLD_FUNCTION",
      "ENHANCE_FUNCTION");
  private static final Map<String, String> BARE_MODULE_LABELS = bareModuleLabels(List.of(
      "工具",
      "草图",
      "BOM",
      "渲染",
      "看板",
      "报表",
      "用户管理",
      "装配",
      "工程图",
      "同步",
      "权限",
      "平台"));
  private static final Pattern MODULE_LABEL_PATTERN =
      Pattern.compile("^(?:模块|module|工具箱)\\s*[:：-]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
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
  private static final Set<String> NON_MODULE_TOKENS = new LinkedHashSet<>(List.of(
      "一级缺陷", "一级严重", "二级缺陷", "二级严重", "三级缺陷", "三级严重",
      "建议", "需求", "需求如此", "P1", "P2", "P3",
      "功能屏蔽", "已拒绝", "申请否决", "数据异常", "需求如此",
      "已修复", "已修复/完成", "待合并", "未复现", "申请延期", "响应已延期",
      "系统测试", "联调测试", "冒烟测试",
      "技术卡点", "方案卡点", "资源卡点", "算法问题", "机制问题", "计算效率",
      "新增理解偏差数量", "需求理解有误数量", "新增需求数量", "新增需求问题数量",
      "业务逻辑错误", "编码逻辑错误", "编译/打包/部署问题", "编译打包问题",
      "机制不支持", "算法/机制不支持"));

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

  static String normalizeTestingPhase(List<String> labels) {
    return IssueRuleSupport.firstMatchingLabel(labels, TESTING_PHASE_TOKENS);
  }

  static String normalizeSystemTestLabel(List<String> labels) {
    return IssueRuleSupport.firstMatchingLabel(labels, SYSTEM_TEST_LABEL_TOKENS);
  }

  static List<String> normalizeModuleNames(
      List<String> labels,
      Map<String, List<String>> reasonCategoryTokens,
      Map<String, List<String>> delayReasonTokens) {
    Set<String> modules = new LinkedHashSet<>();
    for (String label : labels) {
      if (IssueRuleSupport.normalizeText(label) == null) {
        continue;
      }
      if (NON_MODULE_TOKENS.contains(label.trim())) {
        continue;
      }
      if (isKnownReasonCategory(label, reasonCategoryTokens)
          || isKnownSeverityAlias(label)
          || isKnownPriorityAlias(label)
          || isKnownDelayReason(label, delayReasonTokens)
          || isKnownFunctionLabel(label)
          || isTestingPhase(label)) {
        continue;
      }
      String moduleName = extractModuleName(label);
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
    int separatorIndex = label.indexOf('：');
    if (separatorIndex <= 0) {
      return null;
    }
    String prefix = label.substring(0, separatorIndex).trim();
    String value = label.substring(separatorIndex + 1).trim();
    if (!LEGACY_PREFIXES.contains(prefix) || IssueRuleSupport.normalizeText(value) == null) {
      return null;
    }
    return new LegacyPrefixedLabel(prefix.equals("工具箱") ? "模块" : prefix, value);
  }

  private static void appendLegacyLabelValue(Map<String, List<String>> result, String groupName, String value) {
    List<String> values = result.computeIfAbsent(groupName, ignored -> new ArrayList<>());
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  private static String extractModuleName(String label) {
    String trimmed = label.trim();
    Matcher matcher = MODULE_LABEL_PATTERN.matcher(trimmed);
    if (matcher.matches()) {
      return normalizeModuleValue(matcher.group(1));
    }
    if (trimmed.endsWith("模块") && trimmed.length() > "模块".length()) {
      return normalizeModuleValue(trimmed);
    }
    return normalizeBareModuleName(trimmed);
  }

  private static String normalizeModuleValue(String value) {
    String cleaned = value.trim().replaceFirst("^[\\s:：-]+", "").trim();
    String normalized = IssueRuleSupport.normalizeText(cleaned);
    return normalized == null ? null : cleaned;
  }

  private static String normalizeBareModuleName(String value) {
    return BARE_MODULE_LABELS.get(moduleLabelKey(value));
  }

  private static Map<String, String> bareModuleLabels(List<String> labels) {
    Map<String, String> result = new java.util.LinkedHashMap<>();
    for (String label : labels) {
      result.put(moduleLabelKey(label), label);
    }
    return Map.copyOf(result);
  }

  private static String moduleLabelKey(String value) {
    String normalized = IssueRuleSupport.normalizeText(value);
    if (normalized == null) {
      return "";
    }
    return normalized.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_\\-./\\\\:：,，;；|｜]+", "");
  }

  private static boolean isKnownSeverityAlias(String label) {
    return IssueRuleSupport.containsAny(List.of(label), "", IssueRuleSupport.flatten(SEVERITY_TOKENS));
  }

  private static boolean isKnownPriorityAlias(String label) {
    return IssueRuleSupport.containsAny(List.of(label), "", IssueRuleSupport.flatten(PRIORITY_TOKENS));
  }

  private static boolean isKnownReasonCategory(String label, Map<String, List<String>> reasonCategoryTokens) {
    return IssueRuleSupport.containsAny(List.of(label), "", IssueRuleSupport.flatten(reasonCategoryTokens));
  }

  private static boolean isKnownDelayReason(String label, Map<String, List<String>> delayReasonTokens) {
    return IssueRuleSupport.containsAny(List.of(label), "", IssueRuleSupport.flatten(delayReasonTokens));
  }

  private static boolean isKnownFunctionLabel(String label) {
    String normalized = IssueRuleSupport.normalizeText(label);
    if (normalized == null) {
      return false;
    }
    for (String token : FUNCTION_LABEL_TOKENS) {
      String normalizedToken = IssueRuleSupport.normalizeText(token);
      if (normalized.equals(normalizedToken)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTestingPhase(String label) {
    return IssueRuleSupport.containsAny(List.of(label), "", TESTING_PHASE_TOKENS);
  }

  private record LegacyPrefixedLabel(String groupName, String value) {
  }
}
