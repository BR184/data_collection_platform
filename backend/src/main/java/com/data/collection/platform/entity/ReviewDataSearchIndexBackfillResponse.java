package com.data.collection.platform.entity;

public record ReviewDataSearchIndexBackfillResponse(
    int requestedBatchSize,
    boolean hasMissingSearchIndexes,
    boolean hasMissingTitleSearchIndexes) {}
