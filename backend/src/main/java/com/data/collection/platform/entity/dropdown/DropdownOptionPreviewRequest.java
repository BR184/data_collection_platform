package com.data.collection.platform.entity.dropdown;

import java.util.List;

/** 下拉框选项预览请求：提交草稿规则与手动选项，返回最终显示列表，不经保存。 */
public record DropdownOptionPreviewRequest(
    DropdownOptionRulesPayload rules, List<String> manualOptions) {}
