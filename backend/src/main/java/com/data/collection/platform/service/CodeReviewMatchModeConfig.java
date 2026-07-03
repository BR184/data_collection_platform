package com.data.collection.platform.service;

import java.util.List;

record CodeReviewMatchModeConfig(
    String mysqlJdbcUrl,
    String dgmMysqlJdbcUrl,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    List<String> selectedTableNames,
    int mysqlFetchSize,
    String mongoUri,
    String mongoDatabase,
    List<String> selectedMongoCollectionNames,
    String reviewReportCollectionName,
    String reviewProblemCollectionName,
    boolean syncEnabled) {
}
