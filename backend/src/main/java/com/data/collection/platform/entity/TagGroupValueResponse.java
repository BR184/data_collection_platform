package com.data.collection.platform.entity;

public record TagGroupValueResponse(
    String valueKey,
    String label,
    String valueType,
    int sortOrder,
    boolean disabled,
    String unmappedReason) {
}
