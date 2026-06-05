package com.data.collection.platform.entity;

public record TagGroupAdminMappingResponse(
    long id,
    String sourceType,
    String sourceField,
    String rawValue,
    String matchType,
    String sourceInstance,
    String unmappedReason,
    boolean enabled,
    String remark) {
}
