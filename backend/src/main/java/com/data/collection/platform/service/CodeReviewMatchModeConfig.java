package com.data.collection.platform.service;

import java.util.List;

record CodeReviewMatchModeConfig(
    String mysqlJdbcUrl,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    List<String> selectedTableNames,
    int mysqlFetchSize,
    boolean syncEnabled) {
}
