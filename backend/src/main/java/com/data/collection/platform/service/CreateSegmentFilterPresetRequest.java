package com.data.collection.platform.service;

public record CreateSegmentFilterPresetRequest(
    String presetName,
    String ownerUserId,
    String visibility,
    String entityType,
    String scenarioKey,
    String scopeKey,
    String dslJson,
    String tagSchemaHash,
    String sourceDataWatermarkAtSave) {}
