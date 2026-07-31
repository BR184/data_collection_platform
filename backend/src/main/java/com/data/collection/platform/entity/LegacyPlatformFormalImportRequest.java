package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record LegacyPlatformFormalImportRequest(
    String confirmationText,
    LocalDateTime expectedSettingsUpdatedAt) {
}
