package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncJob;
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
// GitLab 鍚屾鎺у埗鍣ㄦ槸闀滃儚鏁版嵁鍏ュ彛鐨?API 闂ㄩ潰锛屽彧璐熻矗閰嶇疆銆佷换鍔°€乄ebhook 鍜屾竻鐞嗗姩浣滅紪鎺掋€?// 澶栭儴搴撹闂€佷换鍔″幓閲嶅拰浜嬪疄閲嶅缓閮戒笅娌夊埌 service锛岄伩鍏嶆帶鍒跺眰鎸佹湁鍚屾缁嗚妭銆?public class GitlabSyncController {
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
    return "%s / %s".formatted(job.getJobType(), job.getStatus());
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
    String connectionMessage = "GitLab PostgreSQL connection succeeded";
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
    String whitelistMessage = "GitLab whitelist options resolved";
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
    return ApiResponse.success("閰嶇疆宸蹭繚瀛?, sanitizeConfigForResponse(configService.saveConfig(config)));
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
    return ApiResponse.success("GitLab PostgreSQL 杩炴帴鎴愬姛", Map.of("checked", true));
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
    return ApiResponse.success("GitLab System Hook registered", result);
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
      return ApiResponse.success("褰撳墠娌℃湁鍙腑姝㈢殑浠诲姟", Map.of("accepted", false));
    }
    return ApiResponse.success(
        "宸叉彁浜や腑姝㈣姹?,
        Map.of("accepted", true, "taskId", task.getId(), "status", task.getStatus()));
  }

  @PostMapping("/purge")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    GitlabSyncConfig config = resolveConfig(request.configId());
    MirrorPurgeResult result = purgeService.purge(request.scope(), config.getId());
    String sourceLabel = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String message = switch (request.scope()) {
      case MIRROR_DATA_ONLY -> "宸茬湡瀹炲垹闄?" + sourceLabel + " 闀滃儚鏁版嵁锛孏itLab 婧愮鍜屾湰鍦伴潪闀滃儚鏁版嵁鍧囦笉鍙楀奖鍝?;
      case MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST ->
          "宸茬湡瀹炲垹闄?" + sourceLabel + " 褰撳墠鐧藉悕鍗曚箣澶栫殑闀滃儚鏁版嵁锛孏itLab 婧愮鍜屾湰鍦伴潪闀滃儚鏁版嵁鍧囦笉鍙楀奖鍝?;
    };
    return ApiResponse.success(message, result);
  }

  @PostMapping({"/system-hook", "/webhook"})
  public ApiResponse<Map<String, Object>> systemHook(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String secret,
      @RequestBody Map<String, Object> payload) {
    webhookService.accept(eventType, payload, secret);
    return ApiResponse.success("GitLab System Hook accepted", Map.of("accepted", true));
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
      message = "System Hook receiver disabled";
    } else if (!secretConfigured) {
      message = "System Hook requires a unique secret";
    } else if (!secretUnique) {
      message = "System Hook secret is already used by another GitLab source";
    } else if (config.getSourceMode() == SourceMode.DIRECT) {
      message = "Direct mode supports System Hook reception; register it in GitLab Admin Area";
    } else {
      message = "System Hook configuration is available";
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
        "action", result.action(),
        "message", submissionMessage(result, task.getTaskType()));
  }

  private String submissionMessage(SyncTaskSubmissionResult result, SyncType requestedType) {
    SyncStatus status = result.task().getStatus();
    if (status == SyncStatus.SUCCESS) {
      return switch (requestedType) {
        case FULL -> "鍏ㄩ噺鍚屾宸插畬鎴?;
        case COMPENSATION -> "琛ュ伩鍚屾宸插畬鎴?;
        case INCREMENTAL -> "鎵嬪伐鎭㈠澧為噺宸插畬鎴?;
        case WEBHOOK -> "绮剧‘鏇存柊宸插畬鎴?;
        case PURGE -> "鍒犻櫎闀滃儚鏁版嵁宸插畬鎴?;
      };
    }
    if (status == SyncStatus.PARTIAL_SUCCESS) {
      return "閮ㄥ垎琛ㄥ悓姝ュけ璐ワ紝璇锋煡鐪嬪悓姝ユ棩蹇楀拰琛ㄧ骇璇婃柇";
    }
    if (status == SyncStatus.FAILED) {
      return "鍚屾浠诲姟澶辫触锛岃鏌ョ湅鍚屾鏃ュ織鍜岃〃绾ц瘖鏂?;
    }
    if (status == SyncStatus.TIMEOUT) {
      return "鍚屾浠诲姟瓒呮椂锛岃绋嶅悗閲嶈瘯鎴栨煡鐪嬭〃绾ц瘖鏂?;
    }
    SyncSubmissionAction action = result.action();
    return switch (action) {
      case CREATED -> switch (requestedType) {
        case FULL -> "鍏ㄩ噺鍚屾宸插紑濮?;
        case COMPENSATION -> "琛ュ伩鍚屾宸插紑濮?;
        case INCREMENTAL -> "鎵嬪伐鎭㈠澧為噺宸插紑濮?;
        case WEBHOOK -> "绮剧‘鏇存柊宸插紑濮?;
        case PURGE -> "鍒犻櫎闀滃儚鏁版嵁宸插紑濮?;
      };
      case QUEUED -> "褰撳墠宸叉湁鍚屾浠诲姟鎵ц涓紝鏈璇锋眰宸茬櫥璁板埌涓嬩竴杞?;
      case REUSED_ACTIVE -> "褰撳墠宸叉湁鍚岃寖鍥村悓姝ヤ换鍔℃墽琛屼腑锛屾湰娆¤姹傚凡鎺ユ敹锛屾棤闇€閲嶅鎿嶄綔";
      case REUSED_QUEUED -> "褰撳墠宸叉湁鍚庣画鍚屾浠诲姟鎺掗槦涓紝鏈璇锋眰宸插悎骞跺埌鍚庣画鍚屾";
      case DEDUPED -> "鍚屾浠诲姟宸叉彁浜わ紝璇峰嬁閲嶅鎿嶄綔";
    };
  }
}

