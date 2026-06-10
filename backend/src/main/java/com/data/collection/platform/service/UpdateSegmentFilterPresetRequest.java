package com.data.collection.platform.service;

public record UpdateSegmentFilterPresetRequest(
    String presetName,
    String visibility,
    String entityType,
    String scenarioKey,
    String scopeKey,
    String dslJson,
    String tagSchemaHash,
    String sourceDataWatermarkAtSave) {}
