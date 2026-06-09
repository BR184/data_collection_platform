package com.data.collection.platform.service;

public record UpdateSegmentFilterPresetRequest(
    String presetName,
    String visibility,
    String dslJson,
    String tagSchemaHash,
    String sourceDataWatermarkAtSave) {}
