package com.data.collection.platform.entity;

public record GitlabSourceTableDiagnosticsResponse(
    String tableName,
    String primaryKey,
    String updatedAtColumn,
    String rowStrategy,
    String schemaFingerprint,
    boolean recommended) {
}
