package com.data.collection.platform.service;

import java.time.LocalDateTime;

public record SegmentFilterPreset(
    long id,
    String presetName,
    String ownerUserId,
    String visibility,
    String entityType,
    String scenarioKey,
    String scopeKey,
    String dslJson,
    String dslHash,
    String tagSchemaHash,
    String sourceDataWatermarkAtSave,
    LocalDateTime lastUsedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
