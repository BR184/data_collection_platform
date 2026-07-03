package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record CodeReviewMatchModeDbSettingsResponse(
    boolean enabled,
    boolean syncEnabled,
    String mysqlHost,
    int mysqlPort,
    String mysqlDatabase,
    String dgmMysqlDatabase,
    String mysqlUsername,
    boolean mysqlPasswordConfigured,
    String mysqlTableName,
    List<String> selectedTableNames,
    int mysqlFetchSize,
    boolean mongoUriConfigured,
    String mongoDatabase,
    List<String> selectedMongoCollectionNames,
    String reviewReportCollectionName,
    String reviewProblemCollectionName,
    String syncStatus,
    String syncMessage,
    long syncRecordCount,
    LocalDateTime syncStartedAt,
    LocalDateTime syncFinishedAt,
    LocalDateTime updatedAt) {
}
