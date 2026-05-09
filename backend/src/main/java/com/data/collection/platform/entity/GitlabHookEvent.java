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
@TableName("gitlab_hook_events")
public class GitlabHookEvent {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("config_id")
  private Long configId;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("event_type")
  private String eventType;

  @TableField("project_id")
  private Long projectId;

  @TableField("object_kind")
  private String objectKind;

  @TableField("dedupe_key")
  private String dedupeKey;

  @TableField("dirty_scope")
  private String dirtyScope;

  @TableField("coalesced_count")
  private Integer coalescedCount;

  private String status;
  private String payload;

  @TableField("received_at")
  private LocalDateTime receivedAt;

  @TableField("processed_at")
  private LocalDateTime processedAt;
}
