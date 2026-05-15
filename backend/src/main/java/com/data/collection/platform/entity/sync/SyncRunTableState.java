package com.data.collection.platform.entity.sync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("sync_run_table_states")
public class SyncRunTableState {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("config_id")
  private Long configId;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("source_table")
  private String sourceTable;

  @TableField("mirror_table")
  private String mirrorTable;

  @TableField("primary_key_columns")
  private String primaryKeyColumns;

  @TableField("updated_at_column")
  private String updatedAtColumn;

  @TableField("row_strategy")
  private String rowStrategy;

  @TableField("sync_enabled")
  private Boolean syncEnabled;

  @TableField("dirty_flag")
  private Boolean dirtyFlag;

  @TableField("dirty_reason")
  private String dirtyReason;

  @TableField("last_success_at")
  private LocalDateTime lastSuccessAt;

  @TableField("last_full_verified_at")
  private LocalDateTime lastFullVerifiedAt;

  @TableField("last_watermark_at")
  private LocalDateTime lastWatermarkAt;

  @TableField("last_cursor_pk")
  private String lastCursorPk;

  @TableField("source_max_updated_at")
  private LocalDateTime sourceMaxUpdatedAt;

  @TableField("source_row_count")
  private Long sourceRowCount;

  @TableField("mirror_row_count")
  private Long mirrorRowCount;

  @TableField("schema_fingerprint")
  private String schemaFingerprint;

  @TableField("last_error")
  private String lastError;

  @TableField("retry_count")
  private Integer retryCount;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
