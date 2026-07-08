package com.data.collection.platform.entity;

public record QualityBoardMetricResponse(
    String key,
    String label,
    Double value,
    String unit,
    String targetDescription) {}
