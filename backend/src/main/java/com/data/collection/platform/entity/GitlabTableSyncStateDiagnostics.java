package com.data.collection.platform.entity;

import java.time.LocalDateTime;

public record GitlabTableSyncStateDiagnostics(
    String sourceTable,
    String mirrorTable,
    String primaryKeyColumns,
    String updatedAtColumn,
    GitlabTableRowStrategy rowStrategy,
    boolean syncEnabled,
    boolean dirty,
    LocalDateTime lastSuccessAt,
    LocalDateTime lastFullVerifiedAt,
    LocalDateTime lastWatermarkAt,
    String lastCursorPk,
    Long sourceRowCount,
    Long mirrorRowCount,
    String schemaFingerprint,
    String lastError,
    Integer retryCount,
    GitlabTableSyncTaskType latestTaskType,
    SyncStatus latestTaskStatus,
    LocalDateTime latestTaskRunAfter,
    LocalDateTime latestTaskHeartbeatAt,
    LocalDateTime latestTaskLeaseUntil,
    Long latestTaskRowsScanned,
    Long latestTaskRowsApplied,
    String latestTaskError) {
}
