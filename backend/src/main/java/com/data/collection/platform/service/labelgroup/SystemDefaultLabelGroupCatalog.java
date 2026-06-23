package com.data.collection.platform.service.labelgroup;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class SystemDefaultLabelGroupCatalog {
  static final String MODULE_FIELD = "moduleName";
  static final String STRING_VALUE_TYPE = "STRING";
  static final String DEFAULT_OPERATOR = "intersects";

  private static final List<Rule> RULES = List.of(
      rule("review-data-home", "评审数据管理"),
      rule("code-review-illegal-records", "代码走查非法数据"),
      rule("system-test-defect-summary", "系统测试缺陷汇总"),
      rule("system-test-delay-analysis", "申请延期缺陷分析"),
      rule("question-metrics-illegal-records", "系统测试非法数据"),
      rule("system-test-defect-cause", "系统测试缺陷原因分析"),
      rule("system-test-phase-statistics", "议题阶段统计"),
      rule("customer-issue-defect-summary", "客户问题缺陷汇总"),
      rule("customer-issues-illegal-records", "客户问题缺陷非法数据"),
      rule("customer-issue-defect-cause", "客户问题缺陷原因分析"),
      rule("customer-issues-cc-product-issues", "CC_PRODUCT议题"),
      rule("customer-issue-delay-issues", "延期问题"),
      rule("customer-issue-response-efficiency", "缺陷响应效率"),
      rule("customer-issue-by-function", "按功能展示缺陷数量"));

  private static final Map<String, Rule> RULES_BY_PAGE_AND_FIELD =
      RULES.stream().collect(Collectors.toUnmodifiableMap(
          rule -> key(rule.pageKey(), rule.fieldKey()),
          rule -> rule));
  private static final Set<String> GROUP_NAMES =
      RULES.stream().map(Rule::groupName).collect(Collectors.toUnmodifiableSet());

  private SystemDefaultLabelGroupCatalog() {}

  static Rule findRule(String pageKey, String fieldKey) {
    return RULES_BY_PAGE_AND_FIELD.get(key(pageKey, fieldKey));
  }

  static boolean isSystemDefaultGroupName(String groupName) {
    return GROUP_NAMES.contains(groupName);
  }

  private static Rule rule(String pageKey, String groupName) {
    return new Rule(pageKey, MODULE_FIELD, STRING_VALUE_TYPE, DEFAULT_OPERATOR, groupName);
  }

  private static String key(String pageKey, String fieldKey) {
    return String.valueOf(pageKey) + ":" + String.valueOf(fieldKey);
  }

  record Rule(
      String pageKey,
      String fieldKey,
      String valueType,
      String operator,
      String groupName) {}
}
