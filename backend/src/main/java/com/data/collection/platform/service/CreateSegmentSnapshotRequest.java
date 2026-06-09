package com.data.collection.platform.service;

public record CreateSegmentSnapshotRequest(
    String name,
    String entityType,
    String scenarioKey,
    String scopeKey,
    String snapshotReason,
    String createdBy) {}
