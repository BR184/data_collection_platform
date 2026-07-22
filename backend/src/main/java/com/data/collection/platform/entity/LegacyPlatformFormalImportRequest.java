package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record LegacyPlatformFormalImportRequest(
    boolean importReviewData,
    boolean importCodeReviewData,
    String confirmationText,
    LocalDateTime expectedSettingsUpdatedAt) {
}
