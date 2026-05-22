package com.data.collection.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.math.BigDecimal;
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

  @TableField("system_hook_secret")
  private String systemHookSecret;

  @TableField("system_hook_enabled")
  private Boolean systemHookEnabled;

  @TableField("system_hook_project_id")
  private Long systemHookProjectId;

  @TableField("compensation_interval_minutes")
  private Integer compensationIntervalMinutes;

  @TableField("full_compensation_enabled")
  private Boolean fullCompensationEnabled;

  @TableField("full_compensation_time")
  private String fullCompensationTime;

  @TableField("sync_thread_mode")
  private String syncThreadMode;

  @TableField("sync_thread_value")
  private BigDecimal syncThreadValue;

  @TableField("max_sync_threads")
  private Integer maxSyncThreads;

  @TableField("last_full_sync_at")
  private LocalDateTime lastFullSyncAt;

  @TableField("last_incremental_sync_at")
  private LocalDateTime lastIncrementalSyncAt;

  @TableField("created_at")
  private LocalDateTime createdAt;

  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
