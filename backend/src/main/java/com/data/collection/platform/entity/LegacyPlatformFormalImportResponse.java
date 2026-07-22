package com.data.collection.platform.entity;

public record LegacyPlatformFormalImportResponse(
    long runId,
    boolean accepted,
    String status,
    String message,
    LegacyPlatformFormalImportDomainResponse review,
    LegacyPlatformFormalImportDomainResponse codeReview) {
}
