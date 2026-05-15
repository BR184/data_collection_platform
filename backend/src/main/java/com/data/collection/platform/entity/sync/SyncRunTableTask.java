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
@TableName("sync_run_table_tasks")
public class SyncRunTableTask {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("run_id")
  private Long runId;

  @TableField("config_id")
  private Long configId;

  @TableField("state_id")
  private Long stateId;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("source_table")
  private String sourceTable;

  @TableField("mirror_table")
  private String mirrorTable;

  @TableField("task_type")
  private String taskType;

  @TableField("status")
  private SyncRunStatus status;

  @TableField("row_strategy")
  private String rowStrategy;

  @TableField("watermark_at")
  private LocalDateTime watermarkAt;

  @TableField("cursor_updated_at")
  private LocalDateTime cursorUpdatedAt;

  @TableField("cursor_pk")
  private String cursorPk;

  @TableField("batch_size")
  private Integer batchSize;

  @TableField("run_after")
  private LocalDateTime runAfter;

  @TableField("lease_owner")
  private String leaseOwner;

  @TableField("lease_until")
  private LocalDateTime leaseUntil;

  @TableField("heartbeat_at")
  private LocalDateTime heartbeatAt;

  @TableField("retry_count")
  private Integer retryCount;

  @TableField("max_retry_count")
  private Integer maxRetryCount;

  @TableField("last_error")
  private String lastError;

  @TableField("rows_scanned")
  private Long rowsScanned;

  @TableField("rows_applied")
  private Long rowsApplied;

  @TableField("started_at")
  private LocalDateTime startedAt;

  @TableField("finished_at")
  private LocalDateTime finishedAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
