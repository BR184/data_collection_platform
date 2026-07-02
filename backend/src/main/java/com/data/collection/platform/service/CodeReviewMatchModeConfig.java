package com.data.collection.platform.service;

record CodeReviewMatchModeConfig(
    String mysqlJdbcUrl,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    int mysqlFetchSize,
    String mongoUri,
    String mongoDatabase,
    String mongoAnnotationCollection,
    boolean syncEnabled) {
}
