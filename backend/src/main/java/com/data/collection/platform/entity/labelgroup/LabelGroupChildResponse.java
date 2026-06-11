package com.data.collection.platform.entity.labelgroup;

public record LabelGroupChildResponse(
    Long id,
    String name,
    String groupType,
    String valueType,
    boolean enabled) {}
