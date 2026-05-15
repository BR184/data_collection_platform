package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabExternalDbService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabSystemHookRegistrationService;
import com.data.collection.platform.service.GitlabSystemHookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gitlab-sync")
@Slf4j
public class GitlabSyncController {
  private final GitlabConfigService configService;
  private final GitlabMirrorSyncService syncService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorProperties properties;
  private final GitlabSystemHookService systemHookService;
  private final GitlabSystemHookRegistrationService systemHookRegistrationService;
  private final GitlabMirrorPurgeService purgeService;
  private final GitlabSourceHealthService sourceHealthService;
  private final GitlabExternalDbService externalDbService;

  public GitlabSyncController(
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties properties,
      GitlabSystemHookService systemHookService,
      GitlabSystemHookRegistrationService systemHookRegistrationService,
      GitlabMirrorPurgeService purgeService,
      GitlabSourceHealthService sourceHealthService,
      GitlabExternalDbService externalDbService) {
    this.configService = configService;
    this.syncService = syncService;
    this.whitelistService = whitelistService;
    this.properties = properties;
    this.systemHookService = systemHookService;
    this.systemHookRegistrationService = systemHookRegistrationService;
    this.purgeService = purgeService;
    this.sourceHealthService = sourceHealthService;
    this.externalDbService = externalDbService;
  }

  @GetMapping("/status")
  public ApiResponse<MirrorStatusResponse> status(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(
        new MirrorStatusResponse(
            sanitizeConfigForResponse(config),
            null,
            SyncStatus.IDLE,
            "Sync orchestrator cutover is in progress. No legacy runtime status is exposed.",
            null,
            null,
            List.of(),
            properties.getSystemHookBaseUrl(),
            null));
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
  public ApiResponse<Map<String, Object>> tableSyncDiagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("configId", config.getId());
    response.put("sourceInstance", GitlabSourceInstanceSupport.sourceInstanceOf(config));
    response.put("generatedAt", java.time.LocalDateTime.now().toString());
    response.put("status", "UNAVAILABLE_DURING_CUTOVER");
    response.put("message", "Legacy table diagnostics have been removed. Unified run diagnostics will replace this endpoint.");
    response.put("tableCount", 0);
    response.put("dirtyTableCount", 0);
    response.put("pendingTaskCount", 0);
    response.put("runningTaskCount", 0);
    response.put("retryingTaskCount", 0);
    response.put("failedTaskCount", 0);
    response.put("timedOutTaskCount", 0);
    response.put("tables", List.of());
    return ApiResponse.success(response);
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
    String whitelistMessage = "GitLab whitelist options loaded";
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

    GitlabSystemHookRegistrationStatus systemHookStatus;
    try {
      systemHookStatus = systemHookRegistrationService.getStatus(config, properties.getSystemHookBaseUrl());
    } catch (Exception e) {
      systemHookStatus = new GitlabSystemHookRegistrationStatus(
          false,
          false,
          false,
          config.getSystemHookProjectId(),
          properties.getSystemHookBaseUrl(),
          e.getMessage(),
          List.of());
    }
    SystemHookConfigDiagnostics systemHookConfigDiagnostics = diagnoseSystemHookConfig(config);
    List<String> runtimeWarnings = diagnoseRuntimeConfig();
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
        properties.getSystemHookBaseUrl(),
        Boolean.TRUE.equals(config.getSystemHookEnabled()),
        systemHookConfigDiagnostics.secretConfigured(),
        systemHookConfigDiagnostics.secretUnique(),
        systemHookConfigDiagnostics.message(),
        systemHookStatus.supported(),
        systemHookStatus.registered(),
        systemHookStatus.message(),
        runtimeWarnings);
    return ApiResponse.success(response);
  }

  @GetMapping("/system-hook-registration-status")
  public ApiResponse<GitlabSystemHookRegistrationStatus> systemHookRegistrationStatus(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(systemHookRegistrationService.getStatus(config, properties.getSystemHookBaseUrl()));
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
    config.setSystemHookSecret(request.systemHookSecret());
    config.setSystemHookEnabled(request.systemHookEnabled());
    config.setSystemHookProjectId(request.systemHookProjectId());
    config.setCompensationIntervalMinutes(request.compensationIntervalMinutes());
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "CONFIG");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Config_Save")) {
      log.info(
          "Saving GitLab sync config, enabled={}, autoSyncEnabled={}, sourceMode={}, whitelistMode={}",
          sourceEnabled,
          syncEnabled,
          request.sourceMode(),
          request.whitelistMode());
    }
    return ApiResponse.success("Config saved", sanitizeConfigForResponse(configService.saveConfig(config)));
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
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "TEST_CONNECTION");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Connection_Test")) {
      log.info("Manual test connection requested");
    }
    if (configId == null) {
      syncService.testConnection();
    } else {
      syncService.testConnection(config.getId());
    }
    return ApiResponse.success("GitLab PostgreSQL connection succeeded", Map.of("checked", true));
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
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, SyncType.FULL.name());
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info("Manual full sync requested during cutover");
    }
    SyncRunSubmissionResult result =
        configId == null ? syncService.startFullSync() : syncService.startFullSync(config.getId());
    return ApiResponse.success(result.message(), buildSubmissionResponse(result));
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
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, SyncType.INCREMENTAL.name());
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info("Manual incremental sync requested during cutover");
    }
    SyncRunSubmissionResult result =
        configId == null
            ? syncService.startIncrementalSync(null, "Manual incremental sync")
            : syncService.startIncrementalSync(config.getId(), null, "Manual incremental sync");
    return ApiResponse.success(result.message(), buildSubmissionResponse(result));
  }

  @PostMapping("/register-system-hook")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSystemHookRegistrationStatus> registerSystemHook() {
    return registerSystemHook(null);
  }

  @PostMapping("/register-system-hook/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSystemHookRegistrationStatus> registerSystemHook(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    GitlabSystemHookRegistrationStatus result =
        systemHookRegistrationService.ensureRegistered(config, properties.getSystemHookBaseUrl());
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
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "CANCEL");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Cancel_Request")) {
      log.info("Manual cancellation requested during cutover");
    }
    boolean accepted = syncService.requestCancel(config.getId());
    return ApiResponse.success(
        accepted ? "Cancellation requested" : "Cancellation is unavailable until the unified run model is wired",
        Map.of("accepted", accepted));
  }

  @PostMapping("/purge")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    GitlabSyncConfig config = resolveConfig(request.configId());
    MirrorPurgeResult result = purgeService.purge(request.scope(), config.getId());
    return ApiResponse.success("Mirror data purged", result);
  }

  @PostMapping("/system-hook")
  public ApiResponse<Map<String, Object>> systemHook(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String secret,
      @RequestBody Map<String, Object> payload) {
    systemHookService.accept(eventType, payload, secret);
    return ApiResponse.success("GitLab System Hook accepted", Map.of("accepted", true));
  }

  public record SaveConfigRequest(
      Long id,
      @NotBlank String name,
      boolean enabled,
      Boolean sourceEnabled,
      boolean autoSyncEnabled,
      Boolean systemHookEnabled,
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
      String systemHookSecret,
      Long systemHookProjectId,
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
    sanitized.setSystemHookSecret("");
    sanitized.setSystemHookEnabled(source.getSystemHookEnabled() != null && source.getSystemHookEnabled());
    sanitized.setSystemHookProjectId(source.getSystemHookProjectId());
    sanitized.setCompensationIntervalMinutes(source.getCompensationIntervalMinutes());
    sanitized.setLastFullSyncAt(source.getLastFullSyncAt());
    sanitized.setLastIncrementalSyncAt(source.getLastIncrementalSyncAt());
    sanitized.setCreatedAt(source.getCreatedAt());
    sanitized.setUpdatedAt(source.getUpdatedAt());
    return sanitized;
  }

  private SystemHookConfigDiagnostics diagnoseSystemHookConfig(GitlabSyncConfig config) {
    boolean systemHookEnabled = Boolean.TRUE.equals(config.getSystemHookEnabled());
    boolean secretConfigured = config.getSystemHookSecret() != null && !config.getSystemHookSecret().isBlank();
    boolean secretUnique = true;
    if (systemHookEnabled && secretConfigured) {
      String secret = config.getSystemHookSecret();
      List<GitlabSyncConfig> configs = configService.listConfigs();
      secretUnique = (configs == null ? List.<GitlabSyncConfig>of() : configs).stream()
          .filter(candidate -> candidate.getId() != null)
          .filter(candidate -> !candidate.getId().equals(config.getId()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getSourceEnabled()))
          .filter(candidate -> Boolean.TRUE.equals(candidate.getSystemHookEnabled()))
          .noneMatch(candidate -> secret.equals(candidate.getSystemHookSecret()));
    }
    String message;
    if (!systemHookEnabled) {
      message = "System Hook receiver is disabled";
    } else if (!secretConfigured) {
      message = "System Hook requires a unique secret";
    } else if (!secretUnique) {
      message = "System Hook secret is used by another GitLab source";
    } else {
      message = "System Hook configuration is available";
    }
    return new SystemHookConfigDiagnostics(secretConfigured, secretUnique, message);
  }

  private record SystemHookConfigDiagnostics(
      boolean secretConfigured,
      boolean secretUnique,
      String message) {
  }

  private List<String> diagnoseRuntimeConfig() {
    List<String> warnings = new ArrayList<>();
    int heartbeatTimeoutSeconds = properties.getHeartbeatTimeoutSeconds();
    int queryTimeoutSeconds = properties.getExternalQueryTimeoutSeconds();
    int minimumRecommendedSeconds = queryTimeoutSeconds + 30;
    if (heartbeatTimeoutSeconds <= queryTimeoutSeconds) {
      warnings.add("heartbeat-timeout-seconds must be greater than external-query-timeout-seconds");
    } else if (heartbeatTimeoutSeconds <= minimumRecommendedSeconds) {
      warnings.add("heartbeat-timeout-seconds is close to external query timeout; long writes may be marked stale");
    }
    warnings.add("Legacy sync runtime has been removed; unified run runtime is not wired yet.");
    return warnings;
  }

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private Map<String, Object> buildSubmissionResponse(SyncRunSubmissionResult result) {
    return Map.of(
        "accepted", result.status() != SyncStatus.IDLE,
        "runId", result.runId() == null ? "" : result.runId(),
        "status", result.status(),
        "statusText", syncStatusLabel(result.status()),
        "action", result.action(),
        "type", result.type(),
        "message", result.message());
  }

  private String syncStatusLabel(SyncStatus status) {
    if (status == null) {
      return "UNKNOWN";
    }
    return switch (status) {
      case PENDING -> "PENDING";
      case QUEUED -> "QUEUED";
      case RUNNING -> "RUNNING";
      case RETRYING -> "RETRYING";
      case SUCCESS -> "SUCCESS";
      case PARTIAL_SUCCESS -> "PARTIAL_SUCCESS";
      case FAILED -> "FAILED";
      case CANCELLED -> "CANCELLED";
      case TIMEOUT -> "TIMEOUT";
      case CANCELLING -> "CANCELLING";
      case IDLE -> "IDLE";
    };
  }
}
