package com.data.collection.platform.entity.labelgroup;

public record LabelGroupDefaultFilterUpdateRequest(
    String pageKey,
    String fieldKey,
    String operator,
    Long labelGroupId,
    Boolean enabled,
    String description) {}

