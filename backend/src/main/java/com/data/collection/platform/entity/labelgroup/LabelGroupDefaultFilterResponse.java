package com.data.collection.platform.entity.labelgroup;

import java.time.OffsetDateTime;

public record LabelGroupDefaultFilterResponse(
    Long id,
    String pageKey,
    String pageName,
    String fieldKey,
    String fieldName,
    String operator,
    Long labelGroupId,
    String labelGroupName,
    String labelGroupValueType,
    Boolean enabled,
    String description,
    OffsetDateTime updatedAt) {}

