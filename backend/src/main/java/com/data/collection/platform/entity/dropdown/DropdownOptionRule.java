package com.data.collection.platform.entity.dropdown;

import com.data.collection.platform.entity.statistics.StatisticFilterGroup;

/**
 * 下拉框选项设置的单条黑白名单规则。
 *
 * <p>规则在规则列表中的顺序即优先级，越靠上越先判定；filterGroup 复用统计条件筛选线格式，
 * 条件字段统一为 optionValue（选项值）。
 *
 * @param listType 名单类型：BLACKLIST（黑名单，命中剔除）或 WHITELIST（白名单，命中保留）
 * @param name 可选规则名，便于规则列表辨认
 * @param filterGroup 条件组（AND/OR + 条件列表）
 */
public record DropdownOptionRule(
    String listType, String name, StatisticFilterGroup filterGroup) {}
