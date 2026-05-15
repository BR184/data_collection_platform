package com.data.collection.platform.entity.sync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.data.collection.platform.entity.SyncTriggerType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("sync_runs")
public class SyncRun {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("run_id")
  private String runId;

  @TableField("config_id")
  private Long configId;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("run_type")
  private SyncRunType runType;

  @TableField("trigger_type")
  private SyncTriggerType triggerType;

  @TableField("status")
  private SyncRunStatus status;

  @TableField("priority")
  private Integer priority;

  @TableField("exclusive_scope")
  private String exclusiveScope;

  @TableField("parent_run_id")
  private Long parentRunId;

  @TableField("cancel_requested")
  private Boolean cancelRequested;

  @TableField("submitted_by")
  private String submittedBy;

  @TableField("request_reason")
  private String requestReason;

  @TableField("payload_json")
  private String payloadJson;

  @TableField("thread_mode")
  private String threadMode;

  @TableField("thread_value")
  private BigDecimal threadValue;

  @TableField("planned_table_count")
  private Integer plannedTableCount;

  @TableField("completed_table_count")
  private Integer completedTableCount;

  @TableField("scanned_rows")
  private Long scannedRows;

  @TableField("applied_rows")
  private Long appliedRows;

  @TableField("heartbeat_at")
  private LocalDateTime heartbeatAt;

  @TableField("lease_owner")
  private String leaseOwner;

  @TableField("lease_until")
  private LocalDateTime leaseUntil;

  @TableField("started_at")
  private LocalDateTime startedAt;

  @TableField("finished_at")
  private LocalDateTime finishedAt;

  @TableField("error_message")
  private String errorMessage;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
