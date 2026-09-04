package com.data.collection.platform.entity.dropdown;

import java.util.List;

/**
 * 单个下拉框字段的完整配置视图。
 *
 * @param fieldKey 注册字段键
 * @param displayName 字段位置路径显示名
 * @param configId 当前绑定配置 ID（未绑定为 null）
 * @param configLabel 配置推导名（与字段列表项同规则）
 * @param rules 双套黑白名单规则
 * @param manualOptions 手动添加的选项
 * @param version 配置版本号，保存时回传用于乐观锁；未绑定字段为 0
 * @param consumerFields 正在使用该配置的全部字段位置路径（含当前字段；未绑定时只含自身）
 */
public record DropdownOptionFieldConfigResponse(
    String fieldKey,
    String displayName,
    Long configId,
    String configLabel,
    DropdownOptionRulesPayload rules,
    List<String> manualOptions,
    long version,
    List<String> consumerFields) {}
