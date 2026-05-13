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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    return refreshTablesOnDemandDetailed(configId, sourceTableNames, reason).plannedTasks();
  }

  public OnDemandRefreshResult refreshTablesOnDemandDetailed(List<String> sourceTableNames, String reason) {
    return refreshTablesOnDemandDetailed(null, sourceTableNames, reason);
  }

  public OnDemandRefreshResult refreshTablesOnDemandDetailed(
      Long configId,
      List<String> sourceTableNames,
      String reason) {
    if (sourceTableNames == null || sourceTableNames.isEmpty()) {
      return new OnDemandRefreshResult(null, List.of(), 0, List.of(), SyncStatus.SUCCESS);
    }

    GitlabSyncConfig config = resolveConfig(configId);
    if (config.getId() == null || !config.isEnabled()) {
      return new OnDemandRefreshResult(
          null, normalizeRequestedTables(sourceTableNames), 0, List.of(), SyncStatus.SUCCESS);
    }
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, "ON_DEMAND_REFRESH");
        GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("QUEUED")) {
      List<TableWhitelistOption> tables = whitelistService.listOptionsStrict(config);
      List<String> requestedTables = normalizeRequestedTables(sourceTableNames);
      GitlabTableSyncPlanningService.CompensationPlanResult result =
          tableSyncPlanningService.createManualRefreshPlan(config, tables, requestedTables, reason);
      if (result == null) {
        throw new BizException("当前页面刷新任务规划失败，请查看同步诊断");
      }
      List<String> unsupportedTables = resolveUnsupportedRefreshTables(tables, requestedTables);
      if (result.plannedTasks() == 0 && !unsupportedTables.isEmpty()) {
        throw new BizException("当前页面依赖表不支持主动刷新，请等待每日校验或联系管理员调整同步策略");
      }
      log.info(
          "Queued on-demand table refresh, reason={}, jobId={}, targetTables={}, plannedTasks={}, verifyOnlyTables={}",
          reason,
          result.jobId(),
          requestedTables,
          result.plannedTasks(),
          result.verifyOnlyTables());
      int processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      log.info(
          "On-demand table refresh drained, jobId={}, plannedTasks={}, processedTasks={}",
          result.jobId(),
          result.plannedTasks(),
          processedTasks);
      return new OnDemandRefreshResult(
          result.jobId(),
          requestedTables,
          result.plannedTasks(),
          unsupportedTables,
          normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId())));
    }
  }

  private List<String> normalizeRequestedTables(List<String> sourceTableNames) {
    if (sourceTableNames == null) {
      return List.of();
    }
    Set<String> normalizedTables = new LinkedHashSet<>();
    for (String sourceTableName : sourceTableNames) {
      if (sourceTableName == null || sourceTableName.isBlank()) {
        continue;
      }
      normalizedTables.add(GitlabSourceInstanceSupport.normalizeSourceTableName(sourceTableName));
    }
    return List.copyOf(normalizedTables);
  }

  private List<String> resolveUnsupportedRefreshTables(
      List<TableWhitelistOption> whitelistOptions,
      List<String> requestedTables) {
    if (requestedTables == null || requestedTables.isEmpty()) {
      return List.of();
    }
    Map<String, TableWhitelistOption> optionsByTable = new LinkedHashMap<>();
    for (TableWhitelistOption option : whitelistOptions == null ? List.<TableWhitelistOption>of() : whitelistOptions) {
      if (option == null || option.tableName() == null || option.tableName().isBlank()) {
        continue;
      }
      optionsByTable.put(GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName()), option);
    }
    return requestedTables.stream()
        .filter(table -> {
          TableWhitelistOption option = optionsByTable.get(table);
          return option == null || option.updatedAtColumn() == null || option.updatedAtColumn().isBlank();
        })
        .toList();
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
    long startedNanos = System.nanoTime();
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
      long planningStartedNanos = System.nanoTime();
      result = tableSyncPlanningService.createManualRefreshPlan(config, tables, sourceTableNames, message);
      long planningDurationMs = elapsedMs(planningStartedNanos);
      long drainStartedNanos = System.nanoTime();
      processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      long mirrorTaskDurationMs = elapsedMs(drainStartedNanos);
      SyncStatus status = normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId()));
      finishVisibleLogIfTerminal(logId, SyncType.INCREMENTAL, status, result.plannedTasks(), processedTasks);
      log.info(
          "Incremental table plan completed, jobId={}, plannedTasks={}, processedTasks={}, planningDurationMs={}, mirrorTaskDurationMs={}, totalDurationMs={}",
          result.jobId(),
          result.plannedTasks(),
          processedTasks,
          planningDurationMs,
          mirrorTaskDurationMs,
          elapsedMs(startedNanos));
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
    long startedNanos = System.nanoTime();
    GitlabSyncJob activeJob = tableSyncPlanningService.findActiveJob(config.getId());
    if (activeJob != null) {
      return new SyncTaskSubmissionResult(
          buildSummaryTaskFromJob(activeJob, syncTypeFromJob(activeJob.getJobType())),
          SyncSubmissionAction.REUSED_ACTIVE);
    }
    GitlabSyncJob recentJob = tableSyncPlanningService.findRecentCompletedJob(
        config.getId(),
        GitlabSyncJobType.DAILY_VERIFY,
        LocalDateTime.now().minusSeconds(Math.max(0, properties.getDedupeWindowSeconds())));
    if (recentJob != null) {
      log.info(
          "Reusing recent completed full verification job, jobId={}, status={}, dedupeWindowSeconds={}",
          recentJob.getId(),
          recentJob.getStatus(),
          properties.getDedupeWindowSeconds());
      return new SyncTaskSubmissionResult(
          buildSummaryTaskFromJob(recentJob, SyncType.FULL),
          SyncSubmissionAction.DEDUPED);
    }
    List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
    List<String> sourceTableNames = tables.stream().map(TableWhitelistOption::tableName).toList();
    long logId = logService.start(config.getId(), SyncType.FULL, sourceTableNames, message);
    GitlabTableSyncPlanningService.CompensationPlanResult result;
    int processedTasks = 0;
    try {
      long planningStartedNanos = System.nanoTime();
      result = tableSyncPlanningService.createManualVerificationPlan(config, tables);
      long planningDurationMs = elapsedMs(planningStartedNanos);
      long drainStartedNanos = System.nanoTime();
      processedTasks = tableSyncWorkerService.drainReadyTasksForJob(result.jobId());
      long mirrorTaskDurationMs = elapsedMs(drainStartedNanos);
      SyncStatus status = normalizeJobStatus(tableSyncPlanningService.findJobStatus(result.jobId()));
      finishVisibleLogIfTerminal(logId, SyncType.FULL, status, result.plannedTasks(), processedTasks);
      log.info(
          "Full table verification completed, jobId={}, plannedTasks={}, processedTasks={}, planningDurationMs={}, mirrorTaskDurationMs={}, totalDurationMs={}",
          result.jobId(),
          result.plannedTasks(),
          processedTasks,
          planningDurationMs,
          mirrorTaskDurationMs,
          elapsedMs(startedNanos));
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

  private long elapsedMs(long startedNanos) {
    return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
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

  public record OnDemandRefreshResult(
      Long jobId,
      List<String> sourceTables,
      int plannedTasks,
      List<String> unsupportedTables,
      SyncStatus status) {

    public OnDemandRefreshResult {
      sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
      unsupportedTables = unsupportedTables == null ? List.of() : List.copyOf(unsupportedTables);
    }
  }
}
