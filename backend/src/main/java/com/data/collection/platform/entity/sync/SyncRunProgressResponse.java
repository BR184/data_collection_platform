package com.data.collection.platform.entity.sync;

import java.time.LocalDateTime;
import java.util.List;

public record SyncRunProgressResponse(
    String runId,
    String runType,
    String status,
    int queuedRunsAhead,
    int totalTables,
    int runningTables,
    int completedTables,
    int failedTables,
    int dirtyTables,
    long scannedRows,
    long appliedRows,
    double recordsPerSecond,
    Long estimatedRemainingSeconds,
    String factRefreshStatus,
    List<String> activeTableTasks,
    LocalDateTime startedAt) {

  public SyncRunProgressResponse {
    activeTableTasks = activeTableTasks == null ? List.of() : List.copyOf(activeTableTasks);
  }
}
