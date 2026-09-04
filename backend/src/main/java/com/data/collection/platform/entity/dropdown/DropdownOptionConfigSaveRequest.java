package com.data.collection.platform.entity.dropdown;

import java.util.List;

/**
 * 下拉框配置整体保存请求（整行替换）。
 *
 * @param rules 双套黑白名单规则
 * @param manualOptions 手动添加的选项（走 manualRules 判定）
 * @param version 当前配置版本号（乐观锁）；字段首次保存尚无绑定配置时忽略
 */
public record DropdownOptionConfigSaveRequest(
    DropdownOptionRulesPayload rules, List<String> manualOptions, Long version) {}
