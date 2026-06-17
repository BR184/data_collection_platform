package com.data.collection.platform.service.statistics;

import java.util.Collection;
import java.util.List;
import org.springframework.util.StringUtils;

final class DefectCauseMetricCatalog {
  static final String NOTE_SEPARATOR = "\\R---\\R";
  static final List<Metric> METRICS =
      List.of(
          new Metric("demand_misunderstand", "新增理解偏差", "需求问题", List.of("新增理解偏差", "新增理解偏差数量", "需求理解有误", "需求理解有误数量")),
          new Metric("missing_requirement", "需求遗漏", "需求问题", List.of("需求遗漏", "需求遗漏数量")),
          new Metric("add_demand_2", "新增需求", "需求问题", List.of("新增需求", "新增需求数量", "新增需求问题", "新增需求问题数量")),
          new Metric("demand_change_not_sync", "需求变更未同步", "需求问题", List.of("需求变更未同步", "需求变更未同步数量")),
          new Metric("design_forget", "功能设计遗漏", "设计问题", List.of("功能设计遗漏", "功能设计遗漏数量")),
          new Metric("design_scheme", "设计方案不合理", "设计问题", List.of("设计方案不合理", "设计方案不合理数量")),
          new Metric("incomplete", "场景考虑不全", "设计问题", List.of("场景考虑不全", "场景考虑不全数量")),
          new Metric("prompt_message", "术语、提示信息不合适", "设计问题", List.of("术语、提示信息不合适", "提示信息不合理")),
          new Metric("standard_error", "编码规范错误", "编码规范", List.of("编码规范错误", "编码规范错误数量")),
          new Metric("function_forget", "功能编码遗漏", "编码规范", List.of("功能编码遗漏", "功能编码遗漏数量")),
          new Metric("logic_calculation_algorithm_error", "编码逻辑：计算与算法错误", "编码规范", List.of("编码逻辑：计算与算法错误")),
          new Metric("logic_flow_control_error", "编码逻辑：流程控制错误", "编码规范", List.of("编码逻辑：流程控制错误")),
          new Metric("logic_data_state_process_error", "编码逻辑：数据与状态处理错误", "编码规范", List.of("编码逻辑：数据与状态处理错误")),
          new Metric("logic_business_logic_error", "编码逻辑：业务逻辑错误", "编码规范", List.of("编码逻辑：业务逻辑错误", "编码逻辑错误")),
          new Metric("logic_integration_interface_error", "编码逻辑：集成与接口错误", "编码规范", List.of("编码逻辑：集成与接口错误", "调用接口错误")),
          new Metric("environment_config_issue", "环境配置问题", "打包问题", List.of("环境配置问题")),
          new Metric("compilation_package_deployment_issue", "编译/打包/部署问题", "打包问题", List.of("编译/打包/部署问题", "编译打包问题")),
          new Metric("other_thirdParty", "第三方库问题", "依赖问题", List.of("第三方库问题")),
          new Metric("algorithm_not_support", "算法不支持", "依赖问题", List.of("算法不支持")),
          new Metric("mechanism_not_support", "机制不支持", "依赖问题", List.of("机制不支持", "算法/机制不支持")),
          new Metric("precondition_data_exception", "前置数据异常", "依赖问题", List.of("前置数据异常", "前置数据异常（如缺少模板文件、前置输入文件本身错误等）")),
          new Metric("other_unIdentifyTask", "未识别的前后置任务", "依赖问题", List.of("未识别的前后置任务")),
          new Metric("precision_constraint_exception", "精度导致约束求解异常", "精度问题", List.of("精度导致约束求解异常")),
          new Metric("precision_algorithm_exception", "精度导致算法执行异常", "精度问题", List.of("精度导致算法执行异常")));

  private DefectCauseMetricCatalog() {}

  static Metric get(String key) {
    return METRICS.stream()
        .filter(metric -> metric.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown defect cause metric key: " + key));
  }

  static boolean containsAny(String text, Collection<String> tokens) {
    if (!StringUtils.hasText(text) || tokens == null || tokens.isEmpty()) {
      return false;
    }
    for (String token : tokens) {
      if (StringUtils.hasText(token) && text.contains(token)) {
        return true;
      }
    }
    return false;
  }

  static String latestReasonText(String text) {
    if (!StringUtils.hasText(text)) {
      return "";
    }
    String[] notes = text.split(NOTE_SEPARATOR);
    for (int index = notes.length - 1; index >= 0; index--) {
      String candidate = notes[index];
      if (METRICS.stream().anyMatch(metric -> containsAny(candidate, metric.tokens()))) {
        return candidate;
      }
    }
    return text;
  }

  record Metric(String key, String label, String groupLabel, List<String> tokens) {}
}
