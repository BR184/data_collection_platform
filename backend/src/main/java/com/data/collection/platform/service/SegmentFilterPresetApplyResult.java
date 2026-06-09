package com.data.collection.platform.service;

public record SegmentFilterPresetApplyResult(
    SegmentFilterPreset preset,
    boolean schemaCompatible,
    String compatibilityMessage) {}
