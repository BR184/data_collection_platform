package com.data.collection.platform.entity.dropdown;

import java.util.List;

/**
 * 预览结果：自动获取值经自动规则、手动选项经手动规则判定后的并集。
 *
 * <p>去重保序：自动获取值按值池原顺序在前，手动添加项按录入顺序排后。
 */
public record DropdownOptionPreviewResponse(List<String> finalOptions) {}
