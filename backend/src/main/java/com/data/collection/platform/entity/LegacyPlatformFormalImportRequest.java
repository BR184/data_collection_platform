package com.data.collection.platform.entity;

public record LegacyPlatformFormalImportRequest(
    boolean importReviewData,
    boolean importCodeReviewData,
    String confirmationText) {
}
