package com.data.collection.platform.entity.database;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DatabaseTableOption {
  private String tableName;
  private String label;
  private String syncStatus;
  private LocalDateTime lastSyncTime;
  private String tableKind;
  private boolean refreshable;
}
