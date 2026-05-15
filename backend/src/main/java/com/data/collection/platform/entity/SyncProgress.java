package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SyncProgress {
  private String phase;
  private int totalTables;
  private int completedTables;
  private int runningTables;
  private int failedTables;
  private int dirtyTables;
  private int syncedRecords;
  private long scannedRows;
  private long appliedRows;
  private double recordsPerSecond;
  private Long estimatedRemainingSeconds;
  private String factRefreshStatus;
  private java.util.List<String> activeTableTasks = java.util.List.of();
  private String currentTable;
  private LocalDateTime startedAt;
}
