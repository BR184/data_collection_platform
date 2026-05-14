package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabWebhookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.MirrorStatusLogView;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.MirrorStatusTaskView;
import com.data.collection.platform.entity.AuthRole;
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
import com.data.collection.platform.service.GitlabExternalDbService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSyncLogService;
import com.data.collection.platform.service.GitlabSyncTaskService;
import com.data.collection.platform.service.GitlabTableSyncPlanningService;
import com.data.collection.platform.service.GitlabTableSyncDiagnosticsService;
import com.data.collection.platform.service.GitlabWebhookRegistrationService;
import com.data.collection.platform.service.GitlabWebhookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import com.data.collection.platform.security.RequireRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gitlab-sync")
@Slf4j
// GitLab 同步控制器是镜像数据入口的 API 门面，只负责配置、任务、System Hook 和清理动作编排。
// 外部库访问、任务去重和事实重建都下沉到 service，避免控制层持有同步细节。
public class GitlabSyncController {
  private static final Set<SyncStatus> ACTIVE_SYNC_STATUSES = Set.of(
      SyncStatus.PENDING,
      SyncStatus.QUEUED,
      SyncStatus.RUNNING,
      SyncStatus.RETRYING,
      SyncStatus.CANCELLING);

  private final GitlabConfigService configService;
  private final GitlabMirrorSyncService syncService;
  private final GitlabSyncLogService logService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorProperties properties;
  private final GitlabWebhookService webhookService;
  private final GitlabSyncTaskService taskService;
  private final GitlabWebhookRegistrationService webhookRegistrationService;
  private final GitlabMirrorPurgeService purgeService;
  private final GitlabSourceHealthService sourceHealthService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;
  private final GitlabTableSyncDiagnosticsService tableSyncDiagnosticsService;

  public GitlabSyncController(
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabSyncLogService logService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties properties,
      GitlabWebhookService webhookService,
      GitlabSyncTaskService taskService,
      GitlabWebhookRegistrationService webhookRegistrationService,
      GitlabMirrorPurgeService purgeService,
      GitlabSourceHealthService sourceHealthService,
      GitlabExternalDbService externalDbService,
      GitlabTableSyncPlanningService tableSyncPlanningService,
      GitlabTableSyncDiagnosticsService tableSyncDiagnosticsService) {
    this.configService = configService;
    this.syncService = syncService;
    this.logService = logService;
    this.whitelistService = whitelistService;
    this.properties = properties;
    this.webhookService = webhookService;
    this.taskService = taskService;
    this.webhookRegistrationService = webhookRegistrationService;
    this.purgeService = purgeService;
    this.sourceHealthService = sourceHealthService;
    this.externalDbService = externalDbService;
    this.tableSyncPlanningService = tableSyncPlanningService;
    this.tableSyncDiagnosticsService = tableSyncDiagnosticsService;
  }

  @GetMapping("/status")
  public ApiResponse<MirrorStatusResponse> status(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    List<GitlabSyncLog> logs = config.getId() == null ? List.of() : logService.listRecent(config.getId(), 20);
    GitlabSyncTask displayTask = taskService.findDisplayTask(config.getId());
    GitlabSyncJob displayJob = tableSyncPlanningService.findDisplayJob(config.getId());

    MirrorStatusTaskView currentTask;
    SyncProgress progress;
    SyncStatus currentStatus;
    String currentMessage;
    LocalDateTime currentStartedAt;
    if (preferJobStatus(displayJob, displayTask)) {
      currentTask = MirrorStatusTaskView.fromJob(displayJob, config.getSourceMode());
      progress = displayJob == null ? null : tableSyncDiagnosticsService.buildProgress(displayJob);
      currentStatus = displayJob == null || displayJob.getStatus() == null ? SyncStatus.IDLE : displayJob.getStatus();
      currentMessage = extractJobMessage(displayJob);
      currentStartedAt = displayJob == null ? null : displayJob.getStartedAt();
    } else {
      currentTask = displayTask == null ? null : MirrorStatusTaskView.from(displayTask);
      progress = displayTask == null ? null : syncService.getProgress(displayTask.getId());
      currentStatus = displayTask == null ? SyncStatus.IDLE : displayTask.getStatus();
      currentMessage = displayTask == null ? "" : taskService.extractMessage(displayTask);
      currentStartedAt = displayTask == null ? null : displayTask.getStartedAt();
    }
    return ApiResponse.success(
        new MirrorStatusResponse(
            sanitizeConfigForResponse(config),
            currentTask,
            currentStatus,
            currentMessage,
            currentStartedAt,
            progress,
            logs.stream().map(MirrorStatusLogView::from).toList(),
            properties.getWebhookBaseUrl(),
            null,
            properties.getWebhookBaseUrl(),
            null));
  }

  private boolean preferJobStatus(GitlabSyncJob job, GitlabSyncTask task) {
    if (job == null) {
      return false;
    }
    if (task == null) {
      return true;
    }
    boolean jobActive = isActiveStatus(job.getStatus());
    boolean taskActive = isActiveStatus(task.getStatus());
    if (jobActive != taskActive) {
      return jobActive;
    }
    LocalDateTime jobTime = latestJobTime(job);
    LocalDateTime taskTime = latestTaskTime(task);
    if (jobTime == null) {
      return false;
    }
    if (taskTime == null) {
      return true;
    }
    return !jobTime.isBefore(taskTime);
  }

  private boolean isActiveStatus(SyncStatus status) {
    return status != null && ACTIVE_SYNC_STATUSES.contains(status);
  }

  private LocalDateTime latestJobTime(GitlabSyncJob job) {
    if (job == null) {
      return null;
    }
    if (job.getUpdatedAt() != null) {
      return job.getUpdatedAt();
    }
    if (job.getFinishedAt() != null) {
      return job.getFinishedAt();
    }
    if (job.getStartedAt() != null) {
      return job.getStartedAt();
    }
    return job.getCreatedAt();
  }

  private LocalDateTime latestTaskTime(GitlabSyncTask task) {
    if (task == null) {
      return null;
    }
    if (task.getUpdatedAt() != null) {
      return task.getUpdatedAt();
    }
    if (task.getFinishedAt() != null) {
      return task.getFinishedAt();
    }
    if (task.getStartedAt() != null) {
      return task.getStartedAt();
    }
    return task.getCreatedAt();
  }

  private String extractJobMessage(GitlabSyncJob job) {
    if (job == null) {
      return "";
    }
    if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
      return job.getErrorMessage();
    }
    return "%s / %s".formatted(syncTypeLabel(job.getJobType()), syncStatusLabel(job.getStatus()));
  }

  private String syncTypeLabel(SyncType type) {
    if (type == null) {
      return "同步任务";
    }
    return switch (type) {
      case FULL -> "全量同步";
      case INCREMENTAL -> "增量同步";
      case COMPENSATION -> "补偿扫描";
      case WEBHOOK -> "System Hook 唤醒";
      case PURGE -> "删除镜像数据";
    };
  }

  private String syncTypeLabel(GitlabSyncJobType type) {
    if (type == null) {
      return "同步任务";
    }
    return switch (type) {
      case DAILY_VERIFY -> "每日全量校验";
      case COMPENSATION_SCAN -> "补偿扫描";
      case MANUAL_REFRESH -> "手动刷新";
      case HOOK_WAKEUP -> "System Hook 唤醒";
      case FACT_REFRESH -> "事实层刷新";
    };
  }

  private String syncStatusLabel(SyncStatus status) {
    if (status == null) {
      return "空闲";
    }
    return switch (status) {
      case PENDING -> "待执行";
      case QUEUED -> "排队中";
      case RUNNING -> "执行中";
      case RETRYING -> "重试中";
      case SUCCESS -> "成功";
      case PARTIAL_SUCCESS -> "部分成功";
      case FAILED -> "失败";
      case CANCELLED -> "已取消";
      case TIMEOUT -> "已超时";
      case CANCELLING -> "取消中";
      case IDLE -> "空闲";
    };
  }

  @GetMapping("/configs")
  public ApiResponse<List<GitlabSyncConfig>> configs() {
    return ApiResponse.success(configService.listConfigs().stream().map(this::sanitizeConfigForResponse).toList());
  }

  @GetMapping("/source-health")
  public ApiResponse<List<GitlabSourceHealthResponse>> sourceHealth() {
    return ApiResponse.success(sourceHealthService.listHealth());
  }

  @GetMapping("/table-sync-diagnostics")
  public ApiResponse<GitlabTableSyncDiagnosticsResponse> tableSyncDiagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(tableSyncDiagnosticsService.diagnose(config.getId()));
  }

  @PostMapping("/diagnostics")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSyncDiagnosticsResponse> diagnostics() {
    return diagnostics(null);
  }

  @PostMapping("/diagnostics/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSyncDiagnosticsResponse> diagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    boolean connectionOk = true;
    String connectionMessage = "GitLab PostgreSQL 连接成功";
    try {
      if (configId == null) {
        syncService.testConnection();
      } else {
        syncService.testConnection(config.getId());
      }
    } catch (Exception e) {
      connectionOk = false;
      connectionMessage = e.getMessage();
    }

    boolean whitelistOk = true;
    String whitelistMessage = "GitLab 白名单选项已加载";
    int whitelistOptionCount = 0;
    List<TableWhitelistOption> whitelistOptions = List.of();
    try {
      whitelistOptions = whitelistService.listOptionsStrict(config);
      whitelistOptionCount = whitelistOptions.size();
    } catch (Exception e) {
      whitelistOk = false;
      whitelistMessage = e.getMessage();
    }

    GitlabSourceMetadataDiagnosticsResponse metadataDiagnostics;
    try {
      metadataDiagnostics = externalDbService.inspectSourceMetadata(config, whitelistOptions);
    } catch (Exception e) {
      metadataDiagnostics = GitlabSourceMetadataDiagnosticsResponse.failure(e.getMessage());
    }

    GitlabWebhookRegistrationStatus webhookStatus;
    try {
      webhookStatus = webhookRegistrationService.getStatus(config, properties.getWebhookBaseUrl());
    } catch (Exception e) {
      webhookStatus = new GitlabWebhookRegistrationStatus(
          false,
          false,
          false,
          config.getWebhookProjectId(),
          properties.getWebhookBaseUrl(),
          e.getMessage(),
          List.of());
    }
    WebhookConfigDiagnostics webhookConfigDiagnostics = diagnoseWebhookConfig(config);
    GitlabSyncDiagnosticsResponse response = new GitlabSyncDiagnosticsResponse(
        config.getId(),
        GitlabSourceInstanceSupport.sourceInstanceOf(config),
        config.getSourceMode(),
        connectionOk,
        connectionMessage,
        whitelistOk,
        whitelistMessage,
        whitelistOptionCount,
        metadataDiagnostics.metadataOk(),
        metadataDiagnostics.metadataMessage(),
        metadataDiagnostics.sourceTableCount(),
        metadataDiagnostics.primaryKeyTableCount(),
        metadataDiagnostics.missingPrimaryKeyTableCount(),
        metadataDiagnostics.missingUpdatedAtTableCount(),
        metadataDiagnostics.sourceTables(),
        properties.getWebhookBaseUrl(),
        Boolean.TRUE.equals(config.getWebhookEnabled()),
        webhookConfigDiagnostics.secretConfigured(),
        webhookConfigDiagnostics.secretUnique(),
        webhookConfigDiagnostics.message(),
        webhookStatus.supported(),
        webhookStatus.registered(),
        webhookStatus.message(),
        properties.getWebhookBaseUrl(),
        Boolean.TRUE.equals(config.getWebhookEnabled()),
        webhookConfigDiagnostics.secretConfigured(),
        webhookConfigDiagnostics.secretUnique(),
        webhookConfigDiagnostics.message(),
        webhookStatus.supported(),
        webhookStatus.registered(),
        webhookStatus.message());
    return ApiResponse.success(response);
  }

  @GetMapping({"/system-hook-registration-status", "/webhook-registration-status"})
  public ApiResponse<GitlabWebhookRegistrationStatus> webhookRegistrationStatus(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(
        webhookRegistrationService.getStatus(config, properties.getWebhookBaseUrl()));
  }

  @GetMapping("/whitelist-options")
  public ApiResponse<List<TableWhitelistOption>> whitelistOptions(
      @RequestParam(value = "configId", required = false) Long configId) {
    return ApiResponse.success(whitelistService.listOptions(resolveConfig(configId)));
  }

  @PutMapping("/config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSyncConfig> saveConfig(@RequestBody SaveConfigRequest request) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    boolean sourceEnabled = request.sourceEnabled() == null ? request.enabled() : request.sourceEnabled();
    boolean syncEnabled = request.autoSyncEnabled();
    config.setId(request.id());
    config.setName(request.name());
    config.setEnabled(sourceEnabled);
    config.setSourceEnabled(sourceEnabled);
    config.setAutoSyncEnabled(syncEnabled);
    config.setSourceInstance(request.sourceInstance());
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
    config.setWebhookEnabled(request.webhookEnabled());
    config.setWebhookProjectId(request.webhookProjectId());
    config.setCompensationIntervalMinutes(request.compensationIntervalMinutes());
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "CONFIG");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Config_Save")) {
      log.info(
          "Saving GitLab sync config, enabled={}, autoSyncEnabled={}, sourceMode={}, whitelistMode={}",
          sourceEnabled,
          syncEnabled,
          request.sourceMode(),
          request.whitelistMode());
    }
    return ApiResponse.success("配置已保存", sanitizeConfigForResponse(configService.saveConfig(config)));
  }

  @PostMapping("/test-connection")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> testConnection() {
    return testConnection(null);
  }

  @PostMapping("/test-connection/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> testConnection(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(config, "TEST_CONNECTION");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Connection_Test")) {
      log.info("Manual test connection requested");
    }
    if (configId == null) {
      syncService.testConnection();
    } else {
      syncService.testConnection(config.getId());
    }
    return ApiResponse.success("GitLab PostgreSQL 连接成功", Map.of("checked", true));
  }

  @PostMapping("/full-sync")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> fullSync() {
    return fullSync(null);
  }

  @PostMapping("/full-sync/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> fullSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(config, SyncType.FULL.name());
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      log.info("Manual full sync requested");
    }
    SyncTaskSubmissionResult result =
        configId == null ? syncService.startFullSync() : syncService.startFullSync(config.getId());
    return ApiResponse.success(submissionMessage(result, SyncType.FULL), buildSubmissionResponse(result));
  }

  @PostMapping("/incremental-sync")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> incrementalSync() {
    return incrementalSync(null);
  }

  @PostMapping("/incremental-sync/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> incrementalSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    try (GitlabSyncLogContext.Scope context =
            GitlabSyncLogContext.openConfig(config, SyncType.INCREMENTAL.name());
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      log.info("Manual recovery incremental sync requested");
    }
    SyncTaskSubmissionResult result =
        configId == null
            ? syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync")
            : syncService.startIncrementalSync(config.getId(), SyncTriggerType.MANUAL, "Manual recovery incremental sync");
    return ApiResponse.success(submissionMessage(result, SyncType.INCREMENTAL), buildSubmissionResponse(result));
  }

  @PostMapping({"/register-system-hook", "/register-webhook"})
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabWebhookRegistrationStatus> registerWebhook() {
    return registerWebhook(null);
  }

  @PostMapping({"/register-system-hook/by-config", "/register-webhook/by-config"})
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabWebhookRegistrationStatus> registerWebhook(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    GitlabWebhookRegistrationStatus result =
        webhookRegistrationService.ensureRegistered(config, properties.getWebhookBaseUrl());
    return ApiResponse.success("GitLab System Hook 已注册", result);
  }

  @PostMapping("/cancel")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> cancel() {
    return cancel(null);
  }

  @PostMapping("/cancel/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> cancel(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
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
        Map.of(
            "accepted", true,
            "taskId", task.getId(),
            "status", task.getStatus(),
            "statusText", syncStatusLabel(task.getStatus())));
  }

  @PostMapping("/purge")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    GitlabSyncConfig config = resolveConfig(request.configId());
    MirrorPurgeResult result = purgeService.purge(request.scope(), config.getId());
    String sourceLabel = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String message = switch (request.scope()) {
      case MIRROR_DATA_ONLY -> "已真实删除 " + sourceLabel + " 镜像数据，GitLab 源端和本地非镜像数据均不受影响";
      case MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST ->
          "已真实删除 " + sourceLabel + " 当前白名单之外的镜像数据，GitLab 源端和本地非镜像数据均不受影响";
    };
    return ApiResponse.success(message, result);
  }

  @PostMapping({"/system-hook", "/webhook"})
  public ApiResponse<Map<String, Object>> systemHook(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String secret,
      @RequestBody Map<String, Object> payload) {
    webhookService.accept(eventType, payload, secret);
    return ApiResponse.success("GitLab System Hook 已接收", Map.of("accepted", true));
  }

  public record SaveConfigRequest(
      Long id,
      @NotBlank String name,
      boolean enabled,
      Boolean sourceEnabled,
      boolean autoSyncEnabled,
      Boolean webhookEnabled,
      String sourceInstance,
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

  public record PurgeRequest(@NotNull MirrorPurgeScope scope, Long configId) {}

  private GitlabSyncConfig sanitizeConfigForResponse(GitlabSyncConfig source) {
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
    sanitized.setWebhookSecret("");
    sanitized.setWebhookEnabled(source.getWebhookEnabled() != null && source.getWebhookEnabled());
    sanitized.setWebhookProjectId(source.getWebhookProjectId());
    sanitized.setCompensationIntervalMinutes(source.getCompensationIntervalMinutes());
    sanitized.setLastFullSyncAt(source.getLastFullSyncAt());
    sanitized.setLastIncrementalSyncAt(source.getLastIncrementalSyncAt());
    sanitized.setCreatedAt(source.getCreatedAt());
    sanitized.setUpdatedAt(source.getUpdatedAt());
    return sanitized;
  }

  private WebhookConfigDiagnostics diagnoseWebhookConfig(GitlabSyncConfig config) {
    boolean webhookEnabled = Boolean.TRUE.equals(config.getWebhookEnabled());
    boolean secretConfigured = config.getWebhookSecret() != null && !config.getWebhookSecret().isBlank();
    boolean secretUnique = true;
    if (webhookEnabled && secretConfigured) {
      String secret = config.getWebhookSecret();
      List<GitlabSyncConfig> configs = configService.listConfigs();
      secretUnique = (configs == null ? List.<GitlabSyncConfig>of() : configs).stream()
          .filter(candidate -> candidate.getId() != null)
          .filter(candidate -> !candidate.getId().equals(config.getId()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getSourceEnabled()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getWebhookEnabled()))
          .noneMatch(candidate -> secret.equals(candidate.getWebhookSecret()));
    }
    String message;
    if (!webhookEnabled) {
      message = "System Hook 接收器未启用";
    } else if (!secretConfigured) {
      message = "System Hook 需要配置唯一密钥";
    } else if (!secretUnique) {
      message = "System Hook 密钥已被另一个 GitLab 源使用";
    } else if (config.getSourceMode() == SourceMode.DIRECT) {
      message = "直连模式支持接收 System Hook，请在 GitLab 管理后台注册";
    } else {
      message = "System Hook 配置可用";
    }
    return new WebhookConfigDiagnostics(secretConfigured, secretUnique, message);
  }

  private record WebhookConfigDiagnostics(
      boolean secretConfigured,
      boolean secretUnique,
      String message) {
  }

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private Map<String, Object> buildSubmissionResponse(SyncTaskSubmissionResult result) {
    GitlabSyncTask task = result.task();
    return Map.of(
        "accepted", true,
        "taskId", task.getId(),
        "status", task.getStatus(),
        "statusText", syncStatusLabel(task.getStatus()),
        "action", result.action(),
        "typeText", syncTypeLabel(task.getTaskType()),
        "message", submissionMessage(result, task.getTaskType()));
  }

  private String submissionMessage(SyncTaskSubmissionResult result, SyncType requestedType) {
    SyncStatus status = result.task().getStatus();
    if (status == SyncStatus.SUCCESS) {
      return switch (requestedType) {
        case FULL -> "全量同步已完成";
        case COMPENSATION -> "补偿同步已完成";
        case INCREMENTAL -> "手动增量同步已完成";
        case WEBHOOK -> "精确更新已完成";
        case PURGE -> "镜像数据删除已完成";
      };
    }
    if (status == SyncStatus.PARTIAL_SUCCESS) {
      return "部分表同步失败，请查看同步日志和表级诊断";
    }
    if (status == SyncStatus.FAILED) {
      return "同步任务失败，请查看同步日志和表级诊断";
    }
    if (status == SyncStatus.TIMEOUT) {
      return "同步任务超时，请稍后重试或查看表级诊断";
    }
    SyncSubmissionAction action = result.action();
    return switch (action) {
      case CREATED -> switch (requestedType) {
        case FULL -> "全量同步已开始";
        case COMPENSATION -> "补偿同步已开始";
        case INCREMENTAL -> "手动增量同步已开始";
        case WEBHOOK -> "精确更新已开始";
        case PURGE -> "镜像数据删除已开始";
      };
      case QUEUED -> "当前已有同步任务执行中，本次请求已登记到下一轮";
      case REUSED_ACTIVE -> "当前已有同范围同步任务执行中，本次请求已接收，无需重复操作";
      case REUSED_QUEUED -> "当前已有后续同步任务排队中，本次请求已合并到后续同步";
      case DEDUPED -> "同步任务已提交，请勿重复操作";
    };
  }
}

