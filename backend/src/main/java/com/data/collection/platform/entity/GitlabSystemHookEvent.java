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
@TableName("gitlab_webhook_events")
public class GitlabSystemHookEvent {
  @TableId(type = IdType.AUTO)
  private Long id;

  @TableField("config_id")
  private Long configId;

  @TableField("event_type")
  private String eventType;

  @TableField("project_id")
  private Long projectId;

  @TableField("object_kind")
  private String objectKind;

  private String payload;
  private boolean processed;

  @TableField("received_at")
  private LocalDateTime receivedAt;
}
