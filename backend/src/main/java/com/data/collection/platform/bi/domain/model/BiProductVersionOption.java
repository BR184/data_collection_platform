package com.data.collection.platform.bi.domain.model;

/** BI 顶部产品版本选项，稳定 ID 与显示文本明确分离。 */
public record BiProductVersionOption(
    long id,
    String businessKey,
    String displayName,
    int sortOrder) {}
