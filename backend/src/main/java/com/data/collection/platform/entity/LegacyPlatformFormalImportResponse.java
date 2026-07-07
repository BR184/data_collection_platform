package com.data.collection.platform.entity;

public record LegacyPlatformFormalImportResponse(
    boolean accepted,
    String message,
    long reviewInsertedCount,
    long reviewUpdatedCount,
    long codeReviewInsertedCount,
    long codeReviewUpdatedCount) {
}
