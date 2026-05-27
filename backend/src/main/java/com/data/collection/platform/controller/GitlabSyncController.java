package com.data.collection.platform.controller;

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
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSystemHookRegistrationService;
import com.data.collection.platform.service.GitlabSystemHookService;
import com.data.collection.platform.service.sync.SyncRunStatusService;
import com.data.collection.platform.service.sync.SyncRunTableDiagnosticsService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
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
public class GitlabSyncController {
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
  private final GitlabSyncConfigFacade configFacade;

  public GitlabSyncController(
      GitlabMirrorProperties properties,
      GitlabSystemHookService systemHookService,
      GitlabSystemHookRegistrationService systemHookRegistrationService,
      GitlabMirrorPurgeService purgeService,
      GitlabSourceHealthService sourceHealthService,
      SyncRunStatusService statusService,
      SyncRunTableDiagnosticsService tableDiagnosticsService,
      GitlabSyncControllerResponseMapper responseMapper,
      GitlabSyncDiagnosticsFacade diagnosticsFacade,
      GitlabSyncCommandFacade commandFacade,
      GitlabSyncConfigFacade configFacade) {
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
    this.configFacade = configFacade;
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
    return ApiResponse.success(configFacade.configs());
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
    return ApiResponse.success(configFacade.whitelistOptions(configId));
  }

  @PutMapping("/config")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<GitlabSyncConfig> saveConfig(@RequestBody GitlabSyncSaveConfigRequest request) {
    return configFacade.saveConfig(request);
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

  public record PurgeRequest(@NotNull MirrorPurgeScope scope, Long configId) {}

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configFacade.resolveConfig(configId);
  }

}
