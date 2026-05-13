package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record RealtimeWorkspaceStatusResponse(
    String workspaceKey,
    boolean supported,
    String status,
    String message,
    boolean refreshing,
    LocalDateTime lastSyncedAt,
    LocalDateTime lastRefreshStartedAt,
    LocalDateTime lastRefreshFinishedAt,
    Long jobId,
    List<String> sourceTables,
    Integer plannedTasks,
    List<String> unsupportedTables,
    Boolean factRefreshPlanned,
    String mirrorStatus,
    String factStatus) {

  public RealtimeWorkspaceStatusResponse {
    sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
    unsupportedTables = unsupportedTables == null ? List.of() : List.copyOf(unsupportedTables);
  }

  public RealtimeWorkspaceStatusResponse(
      String workspaceKey,
      boolean supported,
      String status,
      String message,
      boolean refreshing,
      LocalDateTime lastSyncedAt,
      LocalDateTime lastRefreshStartedAt,
      LocalDateTime lastRefreshFinishedAt) {
    this(
        workspaceKey,
        supported,
        status,
        message,
        refreshing,
        lastSyncedAt,
        lastRefreshStartedAt,
        lastRefreshFinishedAt,
        null,
        List.of(),
        null,
        List.of(),
        null,
        null,
        null);
  }
}
