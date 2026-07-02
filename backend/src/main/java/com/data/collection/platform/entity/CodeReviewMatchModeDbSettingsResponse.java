package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record CodeReviewMatchModeDbSettingsResponse(
    boolean enabled,
    boolean syncEnabled,
    String mysqlHost,
    int mysqlPort,
    String mysqlDatabase,
    String mysqlUsername,
    boolean mysqlPasswordConfigured,
    String mysqlTableName,
    int mysqlFetchSize,
    String mongoDatabase,
    String mongoAnnotationCollection,
    boolean mongoUriConfigured,
    String syncStatus,
    String syncMessage,
    long syncRecordCount,
    LocalDateTime syncStartedAt,
    LocalDateTime syncFinishedAt,
    LocalDateTime updatedAt) {
}
