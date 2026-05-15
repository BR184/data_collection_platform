package com.data.collection.platform.entity.sync;

import java.time.LocalDateTime;

public record SyncRunLogResponse(
    Long id,
    String runId,
    String syncType,
    String triggerType,
    String status,
    String message,
    int tableCount,
    int completedTableCount,
    long recordCount,
    LocalDateTime queuedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String errorSummary) {
}
