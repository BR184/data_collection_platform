package com.data.collection.platform.entity.sync;

import java.time.LocalDateTime;

public record SyncRunTableStateDiagnostics(
    String sourceTable,
    String mirrorTable,
    String primaryKeyColumns,
    String updatedAtColumn,
    String rowStrategy,
    boolean syncEnabled,
    boolean dirty,
    String dirtyReason,
    String blockingRunId,
    LocalDateTime lastVerifiedAt,
    LocalDateTime lastAppliedAt,
    LocalDateTime lastWatermarkAt,
    String lastCursorPk,
    Long sourceRows,
    Long mirrorRows,
    String schemaFingerprint,
    String lastError,
    Integer retryCount,
    String driftSummary,
    String latestTaskType,
    String latestTaskStatus,
    LocalDateTime latestTaskRunAfter,
    LocalDateTime latestTaskHeartbeatAt,
    LocalDateTime latestTaskLeaseUntil,
    Long latestTaskRowsScanned,
    Long latestTaskRowsApplied,
    String latestTaskError) {}
