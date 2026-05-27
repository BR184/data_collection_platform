package com.data.collection.platform.controller;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.sync.SyncRunCancellationService;
import com.data.collection.platform.service.sync.SyncRunCancellationService.SyncRunCancellationResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.service.sync.SyncRunTableDiagnosticsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabSyncCommandFacade {
  private final GitlabMirrorSyncService syncService;
  private final SyncRunSubmissionService submissionService;
  private final SyncRunCancellationService cancellationService;
  private final SyncRunTableDiagnosticsService tableDiagnosticsService;
  private final GitlabSyncControllerResponseMapper responseMapper;

  public GitlabSyncCommandFacade(
      GitlabMirrorSyncService syncService,
      SyncRunSubmissionService submissionService,
      SyncRunCancellationService cancellationService,
      SyncRunTableDiagnosticsService tableDiagnosticsService,
      GitlabSyncControllerResponseMapper responseMapper) {
    this.syncService = syncService;
    this.submissionService = submissionService;
    this.cancellationService = cancellationService;
    this.tableDiagnosticsService = tableDiagnosticsService;
    this.responseMapper = responseMapper;
  }

  public ApiResponse<Map<String, Object>> testConnection(GitlabSyncConfig config, boolean defaultConfig) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "TEST_CONNECTION");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Connection_Test")) {
      log.info("Manual test connection requested");
    }
    if (defaultConfig) {
      syncService.testConnection();
    } else {
      syncService.testConnection(config.getId());
    }
    return ApiResponse.success("GitLab PostgreSQL 连接成功", Map.of("checked", true));
  }

  public ApiResponse<Map<String, Object>> fullSync(GitlabSyncConfig config) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, SyncType.FULL.name());
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info("Manual full sync requested during cutover");
    }
    SyncRunSubmissionResult result = submissionService.submitFullSync(config, "手动全量同步");
    return ApiResponse.success(result.message(), responseMapper.submissionResponse(result));
  }

  public ApiResponse<Map<String, Object>> incrementalSync(GitlabSyncConfig config) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, SyncType.INCREMENTAL.name());
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info("Manual incremental sync requested during cutover");
    }
    SyncRunSubmissionResult result =
        submissionService.submitIncrementalSync(config, null, "手动增量同步");
    return ApiResponse.success(result.message(), responseMapper.submissionResponse(result));
  }

  public ApiResponse<Map<String, Object>> fullCompensationSync(GitlabSyncConfig config) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, SyncType.COMPENSATION.name());
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info("Manual full compensation reconciliation requested");
    }
    SyncRunSubmissionResult result =
        submissionService.submitFullCompensationSync(config, SyncTriggerType.MANUAL, "手动全量补偿对账");
    return ApiResponse.success(result.message(), responseMapper.submissionResponse(result));
  }

  public ApiResponse<Map<String, Object>> retryFailedSync(GitlabSyncConfig config) {
    List<String> retryableTables = tableDiagnosticsService.retryableTables(config);
    if (retryableTables.isEmpty()) {
      return ApiResponse.success(
          "没有需要重试的失败或待修复表任务",
          Map.of(
              "accepted",
              false,
              "runId",
              "",
              "status",
              SyncStatus.IDLE,
              "statusText",
              responseMapper.syncStatusLabel(SyncStatus.IDLE),
              "action",
              SyncSubmissionAction.DEDUPED,
              "type",
              SyncType.INCREMENTAL,
              "message",
              "没有需要重试的失败或待修复表任务"));
    }
    SyncRunSubmissionResult result =
        submissionService.submitTableRefresh(config, retryableTables, "重试失败表任务");
    return ApiResponse.success(result.message(), responseMapper.submissionResponse(result));
  }

  public ApiResponse<Map<String, Object>> cancel(GitlabSyncConfig config) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "CANCEL");
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Cancel_Request")) {
      log.info("Manual cancellation requested during cutover");
    }
    SyncRunCancellationResult result =
        cancellationService.requestCancel(config.getId(), null, "用户手动请求取消");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("accepted", result.accepted());
    response.put("runId", result.runId());
    response.put("externalRunId", result.externalRunId());
    response.put("status", result.status() == null ? null : result.status().name());
    response.put("message", result.message());
    return ApiResponse.success(
        result.accepted() ? result.message() : "当前没有可取消的同步任务",
        response);
  }
}
