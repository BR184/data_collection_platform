package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
  private static final List<String> FUNCTION_LABEL_TOKENS = List.of(
      "新功能",
      "老功能",
      "增强功能",
      "NEW_FUNCTION",
      "OLD_FUNCTION",
      "ENHANCE_FUNCTION");
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
      modules.add(label.trim());
    }
    return List.copyOf(modules);
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
}
