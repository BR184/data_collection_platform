package com.data.collection.platform.entity;

import java.util.List;

public record CodeReviewMatchModeDbSettingsSaveRequest(
    Boolean enabled,
    Boolean syncEnabled,
    String mysqlHost,
    Integer mysqlPort,
    String mysqlDatabase,
    String mysqlUsername,
    String mysqlPassword,
    String mysqlTableName,
    List<String> selectedTableNames,
    Integer mysqlFetchSize) {
}
