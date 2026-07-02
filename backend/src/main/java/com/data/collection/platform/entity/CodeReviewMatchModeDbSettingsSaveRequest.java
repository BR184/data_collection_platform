package com.data.collection.platform.entity;

public record CodeReviewMatchModeDbSettingsSaveRequest(
    Boolean enabled,
    Boolean syncEnabled,
    String mysqlHost,
    Integer mysqlPort,
    String mysqlDatabase,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    Integer mysqlFetchSize,
    String mongoUri,
    String mongoDatabase,
    String mongoAnnotationCollection) {
}
