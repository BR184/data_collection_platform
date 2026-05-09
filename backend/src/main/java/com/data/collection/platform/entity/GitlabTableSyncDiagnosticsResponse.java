package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record GitlabTableSyncDiagnosticsResponse(
    Long configId,
    String sourceInstance,
    LocalDateTime generatedAt,
    int tableCount,
    int dirtyTableCount,
    long pendingTaskCount,
    long runningTaskCount,
    long retryingTaskCount,
    long failedTaskCount,
    long timedOutTaskCount,
    List<GitlabTableSyncStateDiagnostics> tables) {
}
