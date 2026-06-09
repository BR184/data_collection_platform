package com.data.collection.platform.service;

public record SegmentExecutionPlan(
    String templateName,
    long estimatedRows,
    long estimatedMembers,
    int maxExecutionTimeMs,
    int maxAllowedMembers) {}
