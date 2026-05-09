package com.data.collection.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("gitlab_sync_jobs")
public class GitlabSyncJob {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("run_id")
  private String runId;

  @TableField("config_id")
  private Long configId;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("job_type")
  private GitlabSyncJobType jobType;

  @TableField("trigger_type")
  private SyncTriggerType triggerType;

  private SyncStatus status;
  private Integer priority;

  @TableField("run_after")
  private LocalDateTime runAfter;

  @TableField("heartbeat_at")
  private LocalDateTime heartbeatAt;

  @TableField("lease_owner")
  private String leaseOwner;

  @TableField("lease_until")
  private LocalDateTime leaseUntil;

  @TableField("retry_count")
  private Integer retryCount;

  @TableField("max_retry_count")
  private Integer maxRetryCount;

  @TableField("started_at")
  private LocalDateTime startedAt;

  @TableField("finished_at")
  private LocalDateTime finishedAt;

  @TableField("error_code")
  private String errorCode;

  @TableField("error_message")
  private String errorMessage;

  @TableField("payload_json")
  private String payloadJson;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
