package com.data.collection.platform.entity;

import java.util.List;

public record TagGroupAdminValueResponse(
    long id,
    String valueKey,
    String label,
    String valueType,
    int sortOrder,
    boolean enabled,
    boolean disabled,
    String remark,
    List<TagGroupAdminMappingResponse> mappings) {
}
