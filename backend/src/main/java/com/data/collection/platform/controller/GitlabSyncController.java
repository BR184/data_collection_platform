package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSystemHookRegistrationService;
import com.data.collection.platform.service.GitlabSystemHookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import com.data.collection.platform.service.sync.SyncRunStatusService;
import com.data.collection.platform.service.sync.SyncRunTableDiagnosticsService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorProperties properties;
  private final GitlabSystemHookService systemHookService;
  private final GitlabSystemHookRegistrationService systemHookRegistrationService;
  private final GitlabMirrorPurgeService purgeService;
  private final GitlabSourceHealthService sourceHealthService;
  private final SyncRunStatusService statusService;
  private final SyncRunTableDiagnosticsService tableDiagnosticsService;
  private final GitlabSyncControllerResponseMapper responseMapper;
  private final GitlabSyncDiagnosticsFacade diagnosticsFacade;
  private final GitlabSyncCommandFacade commandFacade;

  public GitlabSyncController(
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorProperties properties,
      GitlabSystemHookService systemHookService,
      GitlabSystemHookRegistrationService systemHookRegistrationService,
      GitlabMirrorPurgeService purgeService,
      GitlabSourceHealthService sourceHealthService,
      SyncRunStatusService statusService,
      SyncRunTableDiagnosticsService tableDiagnosticsService,
      GitlabSyncControllerResponseMapper responseMapper,
      GitlabSyncDiagnosticsFacade diagnosticsFacade,
      GitlabSyncCommandFacade commandFacade) {
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.properties = properties;
    this.systemHookService = systemHookService;
    this.systemHookRegistrationService = systemHookRegistrationService;
    this.purgeService = purgeService;
    this.sourceHealthService = sourceHealthService;
    this.statusService = statusService;
    this.tableDiagnosticsService = tableDiagnosticsService;
    this.responseMapper = responseMapper;
    this.diagnosticsFacade = diagnosticsFacade;
    this.commandFacade = commandFacade;
  }

  @GetMapping("/status")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<MirrorStatusResponse> status(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    MirrorStatusResponse status = statusService.getStatus(config);
    return ApiResponse.success(responseMapper.statusResponse(config, status));
  }

  @GetMapping("/configs")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<List<GitlabSyncConfig>> configs() {
    return ApiResponse.success(configService.listConfigs().stream().map(responseMapper::sanitizeConfigForResponse).toList());
  }

  @GetMapping("/source-health")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<List<GitlabSourceHealthResponse>> sourceHealth() {
    return ApiResponse.success(sourceHealthService.listHealth());
  }

  @GetMapping("/table-sync-diagnostics")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> tableSyncDiagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(tableDiagnosticsService.tableDiagnostics(config));
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
    return ApiResponse.success(diagnosticsFacade.diagnose(config, configId == null));
  }

  @GetMapping("/system-hook-registration-status")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSystemHookRegistrationStatus> systemHookRegistrationStatus(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(systemHookRegistrationService.getStatus(config, properties.getSystemHookBaseUrl()));
  }

  @GetMapping("/whitelist-options")
  @RequireRole(AuthRole.ADMIN)
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
    config.setFullCompensationEnabled(request.fullCompensationEnabled());
    config.setFullCompensationTime(request.fullCompensationTime());
    config.setSyncThreadMode(request.syncThreadMode());
    config.setSyncThreadValue(request.syncThreadValue());
    config.setMaxSyncThreads(request.maxSyncThreads());
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "CONFIG");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Config_Save")) {
      log.info(
          "Saving GitLab sync config, enabled={}, autoSyncEnabled={}, sourceMode={}, whitelistMode={}",
          sourceEnabled,
          syncEnabled,
          request.sourceMode(),
          request.whitelistMode());
    }
    return ApiResponse.success("配置已保存", responseMapper.sanitizeConfigForResponse(configService.saveConfig(config)));
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
    return commandFacade.testConnection(config, configId == null);
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
    return commandFacade.fullSync(config);
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
    return commandFacade.incrementalSync(config);
  }

  @PostMapping("/full-compensation-sync")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> fullCompensationSync() {
    return fullCompensationSync(null);
  }

  @PostMapping("/full-compensation-sync/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> fullCompensationSync(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.fullCompensationSync(config);
  }

  @PostMapping("/retry-failed/by-config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Map<String, Object>> retryFailedSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.retryFailedSync(config);
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
    return commandFacade.cancel(config);
  }

  @PostMapping("/purge")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    GitlabSyncConfig config = resolveConfig(request.configId());
    MirrorPurgeResult result = purgeService.purge(request.scope(), config.getId());
    return ApiResponse.success("镜像数据已清理", result);
  }

  @PostMapping("/system-hook")
  public ApiResponse<Map<String, Object>> systemHook(
      @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
      @RequestHeader(value = "X-Gitlab-Token", required = false) String secret,
      @RequestBody Map<String, Object> payload) {
    systemHookService.accept(eventType, payload, secret);
    return ApiResponse.success("GitLab System Hook 已接收", Map.of("accepted", true));
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
      @NotNull Integer compensationIntervalMinutes,
      Boolean fullCompensationEnabled,
      String fullCompensationTime,
      @NotBlank String syncThreadMode,
      @NotNull BigDecimal syncThreadValue,
      Integer maxSyncThreads) {}

  public record PurgeRequest(@NotNull MirrorPurgeScope scope, Long configId) {}

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

}
