package com.data.collection.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName(value = "gitlab_sync_configs", autoResultMap = true)
public class GitlabSyncConfig {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private boolean enabled;

  @TableField("source_enabled")
  private Boolean sourceEnabled;

  @TableField("source_instance")
  private String sourceInstance;

  @TableField("auto_sync_enabled")
  private boolean autoSyncEnabled;

  @TableField("source_mode")
  private SourceMode sourceMode;

  @TableField("whitelist_mode")
  private WhitelistMode whitelistMode;

  @TableField(value = "whitelist_tables", typeHandler = JacksonTypeHandler.class)
  private List<String> whitelistTables;

  @TableField("db_host")
  private String dbHost;

  @TableField("db_port")
  private Integer dbPort;

  @TableField("db_name")
  private String dbName;

  @TableField("db_username")
  private String dbUsername;

  @TableField("db_password")
  private String dbPassword;

  @TableField("docker_container_name")
  private String dockerContainerName;

  @TableField("webhook_secret")
  private String webhookSecret;

  @TableField("webhook_enabled")
  private Boolean webhookEnabled;

  @TableField("webhook_project_id")
  private Long webhookProjectId;

  @TableField("compensation_interval_minutes")
  private Integer compensationIntervalMinutes;

  @TableField("last_full_sync_at")
  private LocalDateTime lastFullSyncAt;

  @TableField("last_incremental_sync_at")
  private LocalDateTime lastIncrementalSyncAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
