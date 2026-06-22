package com.data.collection.platform.service.labelgroup;

import java.time.OffsetDateTime;

record LabelGroupDefaultFilterRecord(
    Long id,
    String pageKey,
    String fieldKey,
    String operator,
    Long labelGroupId,
    String labelGroupName,
    String labelGroupValueType,
    boolean enabled,
    String description,
    OffsetDateTime updatedAt) {}

