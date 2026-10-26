package com.data.collection.platform.domain.form;

/** 版本表期望列，用于生成 DDL 与启动漂移核验。 */
public record FormVersionColumn(
    String tableName, String columnName, String columnType, boolean nullable) {}
