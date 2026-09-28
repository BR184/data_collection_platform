package com.data.collection.platform.entity.dropdown;

import java.util.List;

/**
 * 下拉框配置整体保存请求（整行替换）。
 *
 * @param configId 当前字段绑定的配置身份；未绑定时为 null
 * @param rules 双套黑白名单规则
 * @param manualOptions 手动添加的选项（走 manualRules 判定）
 * @param version 当前配置版本号（乐观锁）；首次保存未绑定字段时必须为 0
 */
public record DropdownOptionConfigSaveRequest(
    Long configId, DropdownOptionRulesPayload rules, List<String> manualOptions, Long version) {}
