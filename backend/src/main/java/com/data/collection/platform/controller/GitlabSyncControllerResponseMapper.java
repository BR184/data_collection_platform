package com.data.collection.platform.controller;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GitlabSyncControllerResponseMapper {
  private final GitlabMirrorProperties properties;
  private final SyncThreadBudgetResolver threadBudgetResolver;

  public GitlabSyncControllerResponseMapper(
      GitlabMirrorProperties properties,
      SyncThreadBudgetResolver threadBudgetResolver) {
    this.properties = properties;
    this.threadBudgetResolver = threadBudgetResolver;
  }

  public MirrorStatusResponse statusResponse(GitlabSyncConfig config, MirrorStatusResponse status) {
    return new MirrorStatusResponse(
        sanitizeConfigForResponse(config),
        status.currentTask(),
        status.currentStatus(),
        status.currentMessage(),
        status.currentStartedAt(),
        status.progress(),
        status.logs(),
        properties.getSystemHookBaseUrl(),
        status.systemHookRegistration(),
        Runtime.getRuntime().availableProcessors(),
        threadBudgetResolver.resolve(config));
  }

  public GitlabSyncConfig sanitizeConfigForResponse(GitlabSyncConfig source) {
    GitlabSyncConfig sanitized = new GitlabSyncConfig();
    sanitized.setId(source.getId());
    sanitized.setName(source.getName());
    sanitized.setEnabled(source.isEnabled());
    sanitized.setSourceEnabled(source.getSourceEnabled() == null ? source.isEnabled() : source.getSourceEnabled());
    sanitized.setSourceInstance(source.getSourceInstance());
    sanitized.setAutoSyncEnabled(source.isAutoSyncEnabled());
    sanitized.setSourceMode(source.getSourceMode());
    sanitized.setWhitelistMode(source.getWhitelistMode());
    sanitized.setWhitelistTables(source.getWhitelistTables());
    sanitized.setDbHost(source.getDbHost());
    sanitized.setDbPort(source.getDbPort());
    sanitized.setDbName(source.getDbName());
    sanitized.setDbUsername(source.getDbUsername());
    sanitized.setDbPassword("");
    sanitized.setDockerContainerName(source.getDockerContainerName());
    sanitized.setSystemHookSecret("");
    sanitized.setSystemHookEnabled(source.getSystemHookEnabled() != null && source.getSystemHookEnabled());
    sanitized.setSystemHookProjectId(source.getSystemHookProjectId());
    sanitized.setCompensationIntervalMinutes(source.getCompensationIntervalMinutes());
    sanitized.setFullCompensationEnabled(source.getFullCompensationEnabled());
    sanitized.setFullCompensationTime(source.getFullCompensationTime());
    sanitized.setSyncThreadMode(source.getSyncThreadMode());
    sanitized.setSyncThreadValue(source.getSyncThreadValue());
    sanitized.setMaxSyncThreads(source.getMaxSyncThreads());
    sanitized.setLastFullSyncAt(source.getLastFullSyncAt());
    sanitized.setLastIncrementalSyncAt(source.getLastIncrementalSyncAt());
    sanitized.setCreatedAt(source.getCreatedAt());
    sanitized.setUpdatedAt(source.getUpdatedAt());
    return sanitized;
  }

  public Map<String, Object> submissionResponse(SyncRunSubmissionResult result) {
    return Map.of(
        "accepted", result.status() != SyncStatus.IDLE,
        "runId", result.runId() == null ? "" : result.runId(),
        "status", result.status(),
        "statusText", syncStatusLabel(result.status()),
        "action", result.action(),
        "type", result.type(),
        "message", result.message());
  }

  public String syncStatusLabel(SyncStatus status) {
    if (status == null) {
      return "UNKNOWN";
    }
    return switch (status) {
      case PENDING -> "待执行";
      case QUEUED -> "等待执行";
      case RUNNING -> "执行中";
      case RETRYING -> "重试中";
      case SUCCESS -> "成功";
      case PARTIAL_SUCCESS -> "已完成，需查看明细";
      case FAILED -> "需要处理";
      case CANCELLED -> "已取消";
      case TIMEOUT -> "已超时";
      case CANCELLING -> "取消中";
      case IDLE -> "空闲";
    };
  }
}
