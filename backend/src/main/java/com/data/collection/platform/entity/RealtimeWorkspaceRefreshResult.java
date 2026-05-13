package com.data.collection.platform.entity;

import java.util.List;

public record RealtimeWorkspaceRefreshResult(
    Long jobId,
    List<String> sourceTables,
    int plannedTasks,
    List<String> unsupportedTables,
    boolean factRefreshPlanned,
    String mirrorStatus,
    String factStatus,
    String message) {

  public RealtimeWorkspaceRefreshResult {
    sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
    unsupportedTables = unsupportedTables == null ? List.of() : List.copyOf(unsupportedTables);
  }
}
