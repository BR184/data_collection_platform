package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.GitlabWebhookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.MirrorStatusLogView;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.MirrorStatusTaskView;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSyncLogService;
import com.data.collection.platform.service.GitlabSyncTaskService;
import com.data.collection.platform.service.GitlabWebhookRegistrationService;
import com.data.collection.platform.service.GitlabWebhookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gitlab-sync")
@Slf4j
public class GitlabSyncController {
  private final GitlabConfigService configService;
  private final GitlabMirrorSyncService syncService;
  private final GitlabSyncLogService logService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorProperties properties;
  private final GitlabWebhookService webhookService;
  private final GitlabSyncTaskService taskService;
  private final GitlabWebhookRegistrationService webhookRegistrationService;
  private final GitlabMirrorPurgeService purgeService;

  public GitlabSyncController(
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabSyncLogService logService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties properties,
      GitlabWebhookService webhookService,
      GitlabSyncTaskService taskService,
      GitlabWebhookRegistrationService webhookRegistrationService,
      GitlabMirrorPurgeService purgeService) {
    this.configService = configService;
    this.syncService = syncService;
    this.logService = logService;
    this.whitelistService = whitelistService;
    this.properties = properties;
    this.webhookService = webhookService;
    this.taskService = taskService;
    this.webhookRegistrationService = webhookRegistrationService;
    this.purgeService = purgeService;
  }

  @GetMapping("/status")
  public ApiResponse<MirrorStatusResponse> status() {
    GitlabSyncConfig config = configService.getConfig();
    List<GitlabSyncLog> logs = config.getId() == null ? List.of() : logService.listRecent(config.getId(), 20);
    GitlabSyncTask currentTask = taskService.findDisplayTask(config.getId());
    SyncProgress progress = currentTask == null ? null : syncService.getProgress(currentTask.getId());
    SyncStatus currentStatus = currentTask == null ? SyncStatus.IDLE : currentTask.getStatus();
    String currentMessage = currentTask == null ? "" : taskService.extractMessage(currentTask);
    LocalDateTime currentStartedAt = currentTask == null ? null : currentTask.getStartedAt();
    return ApiResponse.success(
        new MirrorStatusResponse(
            sanitizeConfigForResponse(config),
            currentTask == null ? null : MirrorStatusTaskView.from(currentTask),
            currentStatus,
            currentMessage,
            currentStartedAt,
            progress,
            logs.stream().map(MirrorStatusLogView::from).toList(),
            properties.getWebhookBaseUrl(),
            null));
  }

  @GetMapping("/webhook-registration-status")
  public ApiResponse<GitlabWebhookRegistrationStatus> webhookRegistrationStatus() {
    GitlabSyncConfig config = configService.getConfig();
    return ApiResponse.success(
        webhookRegistrationService.getStatus(config, properties.getWebhookBaseUrl()));
  }

  @GetMapping("/whitelist-options")
  public ApiResponse<List<TableWhitelistOption>> whitelistOptions() {
    return ApiResponse.success(whitelistService.listOptions(configService.getConfig()));
  }

  @PutMapping("/config")
  public ApiResponse<GitlabSyncConfig> saveConfig(@RequestBody SaveConfigRequest request) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    boolean syncEnabled = request.autoSyncEnabled();
    config.setName(request.name());
    config.setEnabled(syncEnabled);
    config.setAutoSyncEnabled(syncEnabled);
    config.setSourceMode(request.sourceMode());
    config.setWhitelistMode(request.whitelistMode());
    config.setWhitelistTables(request.whitelistTables());
    config.setDbHost(request.dbHost());
    config.setDbPort(request.dbPort());
    config.setDbName(request.dbName());
    config.setDbUsername(request.dbUsername());
    config.setDbPassword(request.dbPassword());
    config.setDockerContainerName(request.dockerContainerName());
    config.setWebhookSecret(request.webhookSecret());
    config.setWebhookProjectId(request.webhookProjectId());
    config.setCompensationIntervalMinutes(request.compensationIntervalMinutes());
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "CONFIG");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Config_Save")) {
      log.info(
          "Saving GitLab sync config, enabled={}, autoSyncEnabled={}, sourceMode={}, whitelistMode={}",
          syncEnabled,
          syncEnabled,
          request.sourceMode(),
          request.whitelistMode());
    }
    return ApiResponse.success("配置已保存", sanitizeConfigForResponse(configService.saveConfig(config)));
  }

  @PostMapping("/test-connection")
  public ApiResponse<Map<String, Object>> testConnection() {
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(configService.getConfig(), "TEST_CONNECTION");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Connection_Test")) {
      log.info("Manual test connection requested");
    }
    syncService.testConnection();
    return ApiResponse.success("GitLab PostgreSQL 连接成功", Map.of("checked", true));
  }

  @PostMapping("/full-sync")
  public ApiResponse<Map<String, Object>> fullSync() {
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(configService.getConfig(), SyncType.FULL.name());
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      log.info("Manual full sync requested");
    }
    SyncTaskSubmissionResult result = syncService.startFullSync();
    return ApiResponse.success(submissionMessage(result, SyncType.FULL), buildSubmissionResponse(result));
  }

  @PostMapping("/incremental-sync")
  public ApiResponse<Map<String, Object>> incrementalSync() {
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(configService.getConfig(), SyncType.INCREMENTAL.name());
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      log.info("Manual recovery incremental sync requested");
    }
    SyncTaskSubmissionResult result =
        syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync");
    return ApiResponse.success(submissionMessage(result, SyncType.INCREMENTAL), buildSubmissionResponse(result));
  }

  @PostMapping("/register-webhook")
  public ApiResponse<GitlabWebhookRegistrationStatus> registerWebhook() {
    GitlabSyncConfig config = configService.getConfig();
    GitlabWebhookRegistrationStatus result =
        webhookRegistrationService.ensureRegistered(config, properties.getWebhookBaseUrl());
    return ApiResponse.success("GitLab Webhook 已注册", result);
  }

  @PostMapping("/cancel")
  public ApiResponse<Map<String, Object>> cancel() {
    GitlabSyncConfig config = configService.getConfig();
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "CANCEL");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Cancel_Request")) {
      log.info("Manual task cancel requested");
    }
    GitlabSyncTask task = syncService.requestCancel(config.getId());
    if (task == null) {
      return ApiResponse.success("当前没有可中止的任务", Map.of("accepted", false));
    }
    return ApiResponse.success(
        "已提交中止请求",
        Map.of("accepted", true, "taskId", task.getId(), "status", task.getStatus()));
  }

  @PostMapping("/purge")
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    MirrorPurgeResult result = purgeService.purge(request.scope());
    String message = switch (request.scope()) {
      case MIRROR_DATA_ONLY -> "已真实删除全部镜像数据，GitLab 源端和本地非镜像数据均不受影响";
      case MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST ->
          "已真实删除当前白名单之外的镜像数据，GitLab 源端和本地非镜像数据均不受影响";
    };
    return ApiResponse.success(message, result);
  }

  @PostMapping("/webhook")
  public ApiResponse<Map<String, Object>> webhook(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String secret,
      @RequestBody Map<String, Object> payload) {
    webhookService.accept(eventType, payload, secret);
    return ApiResponse.success("Webhook 已接收", Map.of("accepted", true));
  }

  public record SaveConfigRequest(
      @NotBlank String name,
      boolean enabled,
      boolean autoSyncEnabled,
      @NotNull SourceMode sourceMode,
      @NotNull WhitelistMode whitelistMode,
      List<String> whitelistTables,
      String dbHost,
      Integer dbPort,
      String dbName,
      String dbUsername,
      String dbPassword,
      String dockerContainerName,
      String webhookSecret,
      Long webhookProjectId,
      @NotNull Integer compensationIntervalMinutes) {}

  public record PurgeRequest(@NotNull MirrorPurgeScope scope) {}

  private GitlabSyncConfig sanitizeConfigForResponse(GitlabSyncConfig source) {
    GitlabSyncConfig sanitized = new GitlabSyncConfig();
    sanitized.setId(source.getId());
    sanitized.setName(source.getName());
    sanitized.setEnabled(source.isEnabled());
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
    sanitized.setWebhookSecret("");
    sanitized.setWebhookProjectId(source.getWebhookProjectId());
    sanitized.setCompensationIntervalMinutes(source.getCompensationIntervalMinutes());
    sanitized.setLastFullSyncAt(source.getLastFullSyncAt());
    sanitized.setLastIncrementalSyncAt(source.getLastIncrementalSyncAt());
    sanitized.setCreatedAt(source.getCreatedAt());
    sanitized.setUpdatedAt(source.getUpdatedAt());
    return sanitized;
  }

  private Map<String, Object> buildSubmissionResponse(SyncTaskSubmissionResult result) {
    GitlabSyncTask task = result.task();
    return Map.of(
        "accepted", true,
        "taskId", task.getId(),
        "status", task.getStatus(),
        "action", result.action(),
        "message", submissionMessage(result, task.getTaskType()));
  }

  private String submissionMessage(SyncTaskSubmissionResult result, SyncType requestedType) {
    SyncSubmissionAction action = result.action();
    return switch (action) {
      case CREATED -> switch (requestedType) {
        case FULL -> "全量同步已开始";
        case COMPENSATION -> "补偿同步已开始";
        case INCREMENTAL -> "手工恢复增量已开始";
        case WEBHOOK -> "精确更新已开始";
      };
      case QUEUED -> "当前已有同步任务执行中，本次请求已登记到下一轮";
      case REUSED_ACTIVE -> "当前已有同范围同步任务执行中，本次请求已接收，无需重复操作";
      case REUSED_QUEUED -> "当前已有后续同步任务排队中，本次请求已合并到后续同步";
      case DEDUPED -> "同步任务已提交，请勿重复操作";
    };
  }
}
