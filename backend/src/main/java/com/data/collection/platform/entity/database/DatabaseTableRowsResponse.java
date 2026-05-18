package com.data.collection.platform.entity.database;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DatabaseTableRowsResponse {
  private String tableName;
  private String label;
  private List<DatabaseTableColumn> columns;
  private List<Map<String, Object>> rows;
  private long total;
  private int page;
  private int size;
  private String sortField;
  private String sortOrder;
  private String keyword;
  private String syncStatus;
  private LocalDateTime lastSyncTime;
  private String statusMessage;
  private String tableKind;
  private boolean refreshable;
}
