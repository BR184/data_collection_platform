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
  /** 业务内容由完整发布点读取；管理/源库预览仍服从当前控制面。 */
  private boolean publishedReadRequired;
}
