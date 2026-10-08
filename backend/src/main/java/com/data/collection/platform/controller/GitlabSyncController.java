package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.entity.FactTaskKind;
import com.data.collection.platform.entity.FactTaskResolutionAction;
import com.data.collection.platform.entity.FactTaskResolutionResult;
import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.service.FactTaskResolutionService;
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
@RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
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
  private final FactTaskResolutionService factTaskResolutionService;

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
      GitlabSyncConfigFacade configFacade,
      FactTaskResolutionService factTaskResolutionService) {
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
    this.factTaskResolutionService = factTaskResolutionService;
  }

  /**
   * 当前同步状态与最近同步日志。
   *
   * <p>可选详情参数按选中运行分页读取全部定位项或相关事件；可选待处理参数分页读取人工待处理列表。
   * 两者都不改变默认响应，只有显式传参时才返回对应区块。
   */
  @GetMapping("/status")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<MirrorStatusResponse> status(
      @RequestParam(value = "configId", required = false) Long configId,
      @RequestParam(value = "detailsRunId", required = false) Long detailsRunId,
      @RequestParam(value = "detailsSection", required = false) String detailsSection,
      @RequestParam(value = "detailsOffset", required = false) Integer detailsOffset,
      @RequestParam(value = "detailsLimit", required = false) Integer detailsLimit,
      @RequestParam(value = "pendingOffset", required = false) Integer pendingOffset,
      @RequestParam(value = "pendingLimit", required = false) Integer pendingLimit) {
    GitlabSyncConfig config = resolveConfig(configId);
    MirrorStatusResponse status =
        statusService.getStatus(
            config,
            SyncRunStatusService.DetailsQuery.of(
                detailsRunId, detailsSection, detailsOffset, detailsLimit),
            SyncRunStatusService.PendingQuery.of(pendingOffset, pendingLimit));
    return ApiResponse.success(responseMapper.statusResponse(config, status));
  }

  @GetMapping("/configs")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<List<GitlabSyncConfig>> configs() {
    return ApiResponse.success(configFacade.configs());
  }

  @GetMapping("/source-health")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<List<GitlabSourceHealthResponse>> sourceHealth() {
    return ApiResponse.success(sourceHealthService.listHealth());
  }

  @GetMapping("/table-sync-diagnostics")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<Map<String, Object>> tableSyncDiagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(tableDiagnosticsService.tableDiagnostics(config));
  }

  @PostMapping("/diagnostics")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<GitlabSyncDiagnosticsResponse> diagnostics() {
    return diagnostics(null);
  }

  @PostMapping("/diagnostics/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<GitlabSyncDiagnosticsResponse> diagnostics(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(diagnosticsFacade.diagnose(config, configId == null));
  }

  @GetMapping("/system-hook-registration-status")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<GitlabSystemHookRegistrationStatus> systemHookRegistrationStatus(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return ApiResponse.success(systemHookRegistrationService.getStatus(config, properties.getSystemHookBaseUrl()));
  }

  @GetMapping("/whitelist-options")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
  public ApiResponse<List<TableWhitelistOption>> whitelistOptions(
      @RequestParam(value = "configId", required = false) Long configId) {
    return ApiResponse.success(configFacade.whitelistOptions(configId));
  }

  @PutMapping("/config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<GitlabSyncConfig> saveConfig(@RequestBody GitlabSyncSaveConfigRequest request) {
    return configFacade.saveConfig(request);
  }

  @PostMapping("/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<Map<String, Object>> testConnection() {
    return testConnection(null);
  }

  @PostMapping("/test-connection/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<Map<String, Object>> testConnection(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.testConnection(config, configId == null);
  }

  @PostMapping("/full-sync")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> fullSync() {
    return fullSync(null);
  }

  @PostMapping("/full-sync/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> fullSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.fullSync(config);
  }

  @PostMapping("/incremental-sync")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> incrementalSync() {
    return incrementalSync(null);
  }

  @PostMapping("/incremental-sync/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> incrementalSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.incrementalSync(config);
  }

  @PostMapping("/full-compensation-sync")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> fullCompensationSync() {
    return fullCompensationSync(null);
  }

  @PostMapping("/full-compensation-sync/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> fullCompensationSync(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.fullCompensationSync(config);
  }

  @PostMapping("/retry-failed/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> retryFailedSync(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.retryFailedSync(config);
  }

  @PostMapping("/register-system-hook")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<GitlabSystemHookRegistrationStatus> registerSystemHook() {
    return registerSystemHook(null);
  }

  @PostMapping("/register-system-hook/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<GitlabSystemHookRegistrationStatus> registerSystemHook(
      @RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    GitlabSystemHookRegistrationStatus result =
        systemHookRegistrationService.ensureRegistered(config, properties.getSystemHookBaseUrl());
    return ApiResponse.success("GitLab System Hook 已注册", result);
  }

  @PostMapping("/cancel")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> cancel() {
    return cancel(null);
  }

  @PostMapping("/cancel/by-config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Map<String, Object>> cancel(@RequestParam(value = "configId", required = false) Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return commandFacade.cancel(config);
  }

  @PostMapping("/purge")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_PURGE)
  public ApiResponse<MirrorPurgeResult> purge(@RequestBody PurgeRequest request) {
    GitlabSyncConfig config = resolveConfig(request.configId());
    MirrorPurgeResult result = purgeService.purge(request.scope(), config.getId());
    return ApiResponse.success("镜像数据已清理", result);
  }

  /**
   * 处置一个待人工决定的事实或投影任务。
   *
   * <p>命令必须携带界面所见任务的原运行编号；归属已变化时拒绝执行，避免旧点击触发第二次接管。
   */
  @PostMapping("/fact-tasks/resolve")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<FactTaskResolutionResult> resolveFactTask(
      @RequestBody FactTaskResolveRequest request) {
    FactTaskResolutionResult result =
        factTaskResolutionService.resolvePendingTask(
            request.configId(),
            request.kind(),
            request.taskId(),
            request.expectedTaskRunId(),
            request.action(),
            request.resumeMode());
    return ApiResponse.success(
        request.action() == FactTaskResolutionAction.RESUME ? "已移交人工继续" : "已取消本次执行意图",
        result);
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

  /**
   * 人工处置请求。
   *
   * @param expectedTaskRunId 界面所见任务的原运行编号；服务端据此校验原归属未被接管
   * @param resumeMode 继续时的意图恢复模式，默认 {@code ORIGINAL}
   */
  public record FactTaskResolveRequest(
      @NotNull Long configId,
      @NotNull FactTaskKind kind,
      @NotNull Long taskId,
      @NotNull String expectedTaskRunId,
      @NotNull FactTaskResolutionAction action,
      String resumeMode) {}

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configFacade.resolveConfig(configId);
  }

}
