package com.data.collection.platform.bi.domain.model;

/** BI 页面内一个可独立判断完整性的展示分区。 */
public record BiPageSection(
    String key,
    String label,
    BiDataStatus status,
    String message) {}
