package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabMirrorSyncService {
  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  @SuppressWarnings("unused")
  private final GitlabMirrorTableStorageService mirrorTableStorageService;
  @SuppressWarnings("unused")
  private final GitlabMirrorRecordMapper mirrorRecordMapper;
  private final GitlabSyncLogService logService;
  private final GitlabSyncTaskService taskService;
  @SuppressWarnings("unused")
  private final GitlabWebhookPreciseSyncPlanner webhookPreciseSyncPlanner;
  @SuppressWarnings("unused")
  private final GitlabMirrorProperties properties;
  @SuppressWarnings("unused")
  private final JsonUtils jsonUtils;
  @SuppressWarnings("unused")
  private final FactBuildTaskService factBuildTaskService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;
  private final GitlabTableSyncWorkerService tableSyncWorkerService;
  @SuppressWarnings("unused")
  private final GitlabMirrorSyncService self;

  public GitlabMirrorSyncService(
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService mirrorTableStorageService,
      GitlabMirrorRecordMapper mirrorRecordMapper,
      GitlabSyncLogService logService,
      GitlabSyncTaskService taskService,
      GitlabWebhookPreciseSyncPlanner webhookPreciseSyncPlanner,
      GitlabMirrorProperties properties,
      JsonUtils jsonUtils,
      FactBuildTaskService factBuildTaskService,
      GitlabTableSyncPlanningService tableSyncPlanningService,
      GitlabTableSyncWorkerService tableSyncWorkerService,
      @Lazy GitlabMirrorSyncService self) {
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.mirrorTableStorageService = mirrorTableStorageService;
    this.mirrorRecordMapper = mirrorRecordMapper;
    this.logService = logService;
    this.taskService = taskService;
    this.webhookPreciseSyncPlanner = webhookPreciseSyncPlanner;
    this.properties = properties;
    this.jsonUtils = jsonUtils;
    this.factBuildTaskService = factBuildTaskService;
    this.tableSyncPlanningService = tableSyncPlanningService;
    this.tableSyncWorkerService = tableSyncWorkerService;
    this.self = self;
  }

  public boolean hasActiveTask(Long configId) {
    return taskService.hasActiveTask(configId);
  }

  public boolean hasExecutingTask(Long configId) {
    return taskService.hasExecutingTask(configId);
  }

  // 新方案以表级持久化任务为准，旧内存进度不再维护。
  public SyncProgress getProgress(Long taskId) {
    return null;
  }

  public void recoverTimedOutTasks() {
    taskService.recoverTimedOutTasks();
    mirrorSchemaService.recoverStaleSyncingStatuses();
  }

  public void testConnection() {
    testConnection(null);
  }

  public void testConnection(Long configId) {
    externalDbService.testConnection(resolveConfig(configId));
  }

  public SyncTaskSubmissionResult startFullSync() {
    return startFullSync(null);
  }

  public SyncTaskSubmissionResult startFullSync(Long configId) {
    return submitManualFullTablePlan(resolveConfig(configId), "Manual full sync");
  }

  public SyncTaskSubmissionResult startIncrementalSync(SyncTriggerType triggerType, String message) {
    return startIncrementalSync(null, triggerType, message);
  }

  public SyncTaskSubmissionResult startIncrementalSync(Long configId, SyncTriggerType triggerType, String message) {
    return submitIncrementalTablePlan(resolveConfig(configId), triggerType, message);
  }

  public int refreshTablesOnDemand(List<String> sourceTableNames, String reason) {
    return refreshTablesOnDemand(null, sourceTableNames, reason);
  }

  public int refreshTablesOnDemand(Long configId, List<String> sourceTableNames, String reason) {
    if (sourceTableNames == null || sourceTableNames.isEmpty()) {
      return 0;
    }

    GitlabSyncConfig config = resolveConfig(configId);
    if (config.getId() == null || !config.isEnabled()) {
      return 0;
    }
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "ON_DEMAND_REFRESH");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("QUEUED")) {
      List<TableWhitelistOption> tables = whitelistService.listOptionsStrict(config);
      GitlabTableSyncPlanningService.CompensationPlanResult result =
          tableSyncPlanningService.createManualRefreshPlan(config, tables, sourceTableNames, reason);
      if (result == null) {
        throw new BizException("当前页面刷新任务规划失败，请查看同步诊断");
      }
      if (result.plannedTasks() == 0 && hasUnsupportedRefreshTargets(result, sourceTableNames)) {
        throw new BizException("当前页面依赖表不支持主动刷新，请等待每日校验或联系管理员调整同步策略");
      }
      log.info(
          "Queued on-demand table refresh, reason={}, jobId={}, targetTables={}, plannedTasks={}, verifyOnlyTables={}",
          reason,
          result.jobId(),
          sourceTableNames,
          result.plannedTasks(),
          result.verifyOnlyTables());
      int processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      log.info(
          "On-demand table refresh drained, jobId={}, plannedTasks={}, processedTasks={}",
          result.jobId(),
          result.plannedTasks(),
          processedTasks);
      return result.plannedTasks();
    }
  }

  private boolean hasUnsupportedRefreshTargets(
      GitlabTableSyncPlanningService.CompensationPlanResult result,
      List<String> sourceTableNames) {
    long requestedTableCount = sourceTableNames == null
        ? 0
        : sourceTableNames.stream()
            .filter(tableName -> tableName != null && !tableName.isBlank())
            .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
            .distinct()
            .count();
    return result.verifyOnlyTables() > 0 || result.discoveredTables() < requestedTableCount;
  }

  public GitlabSyncTask requestCancel(Long configId) {
    return taskService.requestCancelLatest(configId);
  }

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private SyncTaskSubmissionResult submitIncrementalTablePlan(
      GitlabSyncConfig config,
      SyncTriggerType triggerType,
      String message) {
    GitlabSyncJob activeJob = tableSyncPlanningService.findActiveJob(config.getId());
    if (activeJob != null) {
      return new SyncTaskSubmissionResult(
          buildSummaryTaskFromJob(activeJob, syncTypeFromJob(activeJob.getJobType())),
          SyncSubmissionAction.REUSED_ACTIVE);
    }
    List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
    List<String> sourceTableNames = tables.stream().map(TableWhitelistOption::tableName).toList();
    long logId = logService.start(config.getId(), SyncType.INCREMENTAL, sourceTableNames, message);
    GitlabTableSyncPlanningService.CompensationPlanResult result;
    int processedTasks = 0;
    try {
      result = tableSyncPlanningService.createManualRefreshPlan(config, tables, sourceTableNames, message);
      processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      SyncStatus status = normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId()));
      finishVisibleLogIfTerminal(logId, SyncType.INCREMENTAL, status, result.plannedTasks(), processedTasks);
    } catch (Exception e) {
      logService.finish(logId, SyncStatus.FAILED, e.getMessage(), processedTasks, 0);
      throw e;
    }
    return buildSummaryTask(
        result.jobId(),
        config.getId(),
        SyncType.INCREMENTAL,
        triggerType == null ? SyncTriggerType.MANUAL : triggerType,
        normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId())));
  }

  private SyncTaskSubmissionResult submitManualFullTablePlan(GitlabSyncConfig config, String message) {
    GitlabSyncJob activeJob = tableSyncPlanningService.findActiveJob(config.getId());
    if (activeJob != null) {
      return new SyncTaskSubmissionResult(
          buildSummaryTaskFromJob(activeJob, syncTypeFromJob(activeJob.getJobType())),
          SyncSubmissionAction.REUSED_ACTIVE);
    }
    List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
    List<String> sourceTableNames = tables.stream().map(TableWhitelistOption::tableName).toList();
    long logId = logService.start(config.getId(), SyncType.FULL, sourceTableNames, message);
    GitlabTableSyncPlanningService.CompensationPlanResult result;
    int processedTasks = 0;
    try {
      result = tableSyncPlanningService.createManualVerificationPlan(config, tables);
      processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      SyncStatus status = normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId()));
      finishVisibleLogIfTerminal(logId, SyncType.FULL, status, result.plannedTasks(), processedTasks);
    } catch (Exception e) {
      logService.finish(logId, SyncStatus.FAILED, e.getMessage(), processedTasks, 0);
      throw e;
    }
    return buildSummaryTask(
        result.jobId(),
        config.getId(),
        SyncType.FULL,
        SyncTriggerType.MANUAL,
        normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId())));
  }

  private SyncTaskSubmissionResult buildSummaryTask(
      Long jobId,
      Long configId,
      SyncType type,
      SyncTriggerType triggerType,
      SyncStatus status) {
    GitlabSyncTask task = new GitlabSyncTask();
    LocalDateTime now = LocalDateTime.now();
    task.setId(jobId);
    task.setConfigId(configId);
    task.setTaskType(type);
    task.setTriggerType(triggerType);
    task.setStatus(status);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return new SyncTaskSubmissionResult(task, SyncSubmissionAction.CREATED);
  }

  private GitlabSyncTask buildSummaryTaskFromJob(GitlabSyncJob job, SyncType type) {
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(job.getId());
    task.setConfigId(job.getConfigId());
    task.setTaskType(type);
    task.setTriggerType(job.getTriggerType());
    task.setStatus(normalizeJobStatus(job.getStatus()));
    task.setCreatedAt(job.getCreatedAt());
    task.setStartedAt(job.getStartedAt());
    task.setFinishedAt(job.getFinishedAt());
    task.setUpdatedAt(job.getUpdatedAt());
    return task;
  }

  private SyncType syncTypeFromJob(GitlabSyncJobType jobType) {
    if (jobType == null) {
      return SyncType.INCREMENTAL;
    }
    return switch (jobType) {
      case DAILY_VERIFY -> SyncType.FULL;
      case COMPENSATION_SCAN -> SyncType.COMPENSATION;
      case HOOK_WAKEUP -> SyncType.WEBHOOK;
      case MANUAL_REFRESH, FACT_REFRESH -> SyncType.INCREMENTAL;
    };
  }

  private String buildTablePlanCompletionMessage(
      SyncType type,
      SyncStatus status,
      int plannedTasks,
      int processedTasks) {
    String label = type == SyncType.FULL ? "全量校验" : "增量刷新";
    return switch (status) {
      case SUCCESS ->
          "%s表级同步已完成，计划表任务 %d 个，已处理 %d 个".formatted(label, plannedTasks, processedTasks);
      case PARTIAL_SUCCESS ->
          "%s表级同步部分成功，计划表任务 %d 个，已处理 %d 个，请查看表级诊断".formatted(label, plannedTasks, processedTasks);
      case FAILED ->
          "%s表级同步失败，计划表任务 %d 个，已处理 %d 个，请查看表级诊断".formatted(label, plannedTasks, processedTasks);
      case TIMEOUT ->
          "%s表级同步超时，计划表任务 %d 个，已处理 %d 个，请稍后重试或查看表级诊断".formatted(label, plannedTasks, processedTasks);
      default ->
          "%s表级同步状态为 %s，计划表任务 %d 个，已处理 %d 个".formatted(label, status, plannedTasks, processedTasks);
    };
  }

  private SyncStatus normalizeJobStatus(SyncStatus status) {
    return status == null ? SyncStatus.PENDING : status;
  }

  private void finishVisibleLogIfTerminal(
      long logId,
      SyncType type,
      SyncStatus status,
      int plannedTasks,
      int processedTasks) {
    if (status == SyncStatus.PENDING
        || status == SyncStatus.QUEUED
        || status == SyncStatus.RUNNING
        || status == SyncStatus.RETRYING
        || status == SyncStatus.CANCELLING) {
      return;
    }
    logService.finish(
        logId,
        status,
        buildTablePlanCompletionMessage(type, status, plannedTasks, processedTasks),
        plannedTasks,
        0);
  }
}
