package com.data.collection.platform.entity.dropdown;

/**
 * 下拉框选项设置页面的字段列表项。
 *
 * @param fieldKey 注册字段键
 * @param displayName 字段位置路径显示名
 * @param configured 绑定的配置是否包含有效规则或手动选项（未配置即直通现状）
 * @param configId 当前绑定配置 ID（未绑定为 null）
 * @param configLabel 配置推导名：单字段绑定=该字段位置路径，多字段共用=全部位置路径 + 共用提示
 */
public record DropdownOptionFieldSummaryResponse(
    String fieldKey, String displayName, boolean configured, Long configId, String configLabel) {}
