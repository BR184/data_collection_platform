package com.data.collection.platform.entity;

import java.util.List;

public record CodeReviewMatchModeDbSettingsSaveRequest(
    Boolean enabled,
    Boolean syncEnabled,
    String mysqlHost,
    Integer mysqlPort,
    String mysqlDatabase,
    String dgmMysqlDatabase,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    String legacyApiBaseUrl,
    String dgmLegacyApiBaseUrl,
    List<String> selectedTableNames,
    Integer mysqlFetchSize,
    String mongoUri,
    String mongoDatabase,
    List<String> selectedMongoCollectionNames,
    String reviewReportCollectionName,
    String reviewProblemCollectionName,
    String reviewDataReadMode,
    String codeReviewReadMode) {
}
