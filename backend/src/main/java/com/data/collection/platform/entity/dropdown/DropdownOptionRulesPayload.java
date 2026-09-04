package com.data.collection.platform.entity.dropdown;

import java.util.List;

/**
 * 一个下拉框配置内的双套黑白名单规则：自动获取值与手动添加值各自独立判定后取并集。
 *
 * @param acquiredRules 作用于自动获取值的有序规则列表（顺序即优先级，首位最高）
 * @param manualRules 作用于手动添加值的有序规则列表（顺序即优先级，首位最高）
 */
public record DropdownOptionRulesPayload(
    List<DropdownOptionRule> acquiredRules, List<DropdownOptionRule> manualRules) {

  public static DropdownOptionRulesPayload empty() {
    return new DropdownOptionRulesPayload(List.of(), List.of());
  }

  /** 存储与请求载荷的空数组归一化视图，解析方无需再判空。 */
  public DropdownOptionRulesPayload withNullsAsEmpty() {
    return new DropdownOptionRulesPayload(
        acquiredRules == null ? List.of() : List.copyOf(acquiredRules),
        manualRules == null ? List.of() : List.copyOf(manualRules));
  }
}
