package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.source.BiSystemTestSource;
import java.util.List;

/** BI 自有、可版本化的系统测试缺陷原因分类规则。 */
public final class BiSystemTestCauseClassifier {
  public static final String RULE_VERSION = "bi-system-test-cause-v1";
  private static final BiSystemTestSource.CauseRef UNCLASSIFIED =
      new BiSystemTestSource.CauseRef("unclassified", "未归类", "unclassified", "未归类");
  private static final List<Metric> METRICS = List.of(
      metric("demand_misunderstand", "新增理解偏差", "需求问题", "新增理解偏差", "新增理解偏差数量", "需求理解有误", "需求理解有误数量"),
      metric("missing_requirement", "需求遗漏", "需求问题", "需求遗漏", "需求遗漏数量"),
      metric("add_demand_2", "新增需求", "需求问题", "新增需求", "新增需求数量", "新增需求问题", "新增需求问题数量"),
      metric("demand_change_not_sync", "需求变更未同步", "需求问题", "需求变更未同步", "需求变更未同步数量"),
      metric("design_forget", "功能设计遗漏", "设计问题", "功能设计遗漏", "功能设计遗漏数量"),
      metric("design_scheme", "设计方案不合理", "设计问题", "设计方案不合理", "设计方案不合理数量"),
      metric("incomplete", "场景考虑不全", "设计问题", "场景考虑不全", "场景考虑不全数量"),
      metric("prompt_message", "术语、提示信息不合适", "设计问题", "术语、提示信息不合适", "提示信息不合理"),
      metric("standard_error", "编码规范错误", "编码规范", "编码规范错误", "编码规范错误数量"),
      metric("function_forget", "功能编码遗漏", "编码规范", "功能编码遗漏", "功能编码遗漏数量"),
      metric("logic_calculation_algorithm_error", "编码逻辑：计算与算法错误", "编码规范", "编码逻辑：计算与算法错误"),
      metric("logic_flow_control_error", "编码逻辑：流程控制错误", "编码规范", "编码逻辑：流程控制错误"),
      metric("logic_data_state_process_error", "编码逻辑：数据与状态处理错误", "编码规范", "编码逻辑：数据与状态处理错误"),
      metric("logic_business_logic_error", "编码逻辑：业务逻辑错误", "编码规范", "编码逻辑：业务逻辑错误", "编码逻辑错误"),
      metric("logic_integration_interface_error", "编码逻辑：集成与接口错误", "编码规范", "编码逻辑：集成与接口错误", "调用接口错误"),
      metric("environment_config_issue", "环境配置问题", "打包问题", "环境配置问题"),
      metric("compilation_package_deployment_issue", "编译/打包/部署问题", "打包问题", "编译/打包/部署问题", "编译打包问题"),
      metric("other_thirdParty", "第三方库问题", "依赖问题", "第三方库问题"),
      metric("algorithm_not_support", "算法不支持", "依赖问题", "算法不支持"),
      metric("mechanism_not_support", "机制不支持", "依赖问题", "机制不支持", "算法/机制不支持"),
      metric("precondition_data_exception", "前置数据异常", "依赖问题", "前置数据异常", "前置数据异常（如缺少模板文件、前置输入文件本身错误等）"),
      metric("other_unIdentifyTask", "未识别的前后置任务", "依赖问题", "未识别的前后置任务"),
      metric("precision_constraint_exception", "精度导致约束求解异常", "精度问题", "精度导致约束求解异常"),
      metric("precision_algorithm_exception", "精度导致算法执行异常", "精度问题", "精度导致算法执行异常"));

  /**
   * 按已确认的原因大类/子类词典匹配一条缺陷；没有命中时保留为“未归类”。
   *
   * @param reasonCategory 事实层原因分类文本
   * @param labelsText 事实层完整标签文本
   * @return 至少包含一个成员的不可变原因列表
   */
  public List<BiSystemTestSource.CauseRef> classify(String reasonCategory, String labelsText) {
    String text = safe(reasonCategory) + " " + safe(labelsText);
    List<BiSystemTestSource.CauseRef> matches = METRICS.stream()
        .filter(metric -> metric.tokens().stream().anyMatch(text::contains))
        .map(metric -> new BiSystemTestSource.CauseRef(
            categoryId(metric.categoryName()), metric.categoryName(), metric.id(), metric.name()))
        .toList();
    return matches.isEmpty() ? List.of(UNCLASSIFIED) : matches;
  }

  private static Metric metric(String id, String name, String categoryName, String... tokens) {
    return new Metric(id, name, categoryName, List.of(tokens));
  }

  private static String categoryId(String categoryName) {
    return switch (categoryName) {
      case "需求问题" -> "requirement";
      case "设计问题" -> "design";
      case "编码规范" -> "coding";
      case "打包问题" -> "packaging";
      case "依赖问题" -> "dependency";
      case "精度问题" -> "precision";
      default -> throw new IllegalArgumentException("unknown cause category: " + categoryName);
    };
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private record Metric(String id, String name, String categoryName, List<String> tokens) {}
}
