package com.data.collection.platform.domain.customerissue;

import com.data.collection.platform.domain.issue.DefectCauseMetricCatalog;
import java.util.List;
import java.util.Map;

/**
 * 客户问题缺陷原因匹配覆盖规则。
 *
 * <p>老平台客户原因页对部分原因分类使用的 token 与系统测试不同，这里集中登记客户侧覆盖：
 * 覆盖只作用于客户范围，不得回流到系统测试原因页。
 */
public final class CustomerIssueCauseRules {
  private static final Map<String, List<String>> LEGACY_CAUSE_TOKEN_OVERRIDES =
      Map.of(
          "prompt_message", List.of("术语、提示信息不合适"),
          "logic_integration_interface_error", List.of("编码逻辑：集成与接口错误"),
          "precondition_data_exception", List.of("前置数据异常（如缺少模板文件、前置输入文件本身错误等）"));

  private CustomerIssueCauseRules() {}

  /** 客户范围下该原因分类实际使用的匹配 token。 */
  public static List<String> tokensFor(DefectCauseMetricCatalog.Metric metric) {
    return LEGACY_CAUSE_TOKEN_OVERRIDES.getOrDefault(metric.key(), metric.tokens());
  }
}
