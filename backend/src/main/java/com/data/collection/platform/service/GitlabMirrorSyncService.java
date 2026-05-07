package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabMirrorRecord;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
// GitLab 镜像同步服务负责把外部库数据拉入本地 ODS 表和镜像记录表，是整个平台的数据入口。
// 同步过程同时维护任务进度、日志和精确 Webhook 补偿，避免事实构建直接访问外部库。
public class GitlabMirrorSyncService {
  private static final int UPSERT_BATCH_SIZE = 200;
  private static final int COMPENSATION_EXTRA_LOOKBACK_MINUTES = 60;

  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final GitlabMirrorTableStorageService mirrorTableStorageService;
  private final GitlabMirrorRecordMapper mirrorRecordMapper;
  private final GitlabSyncLogService logService;
  private final GitlabSyncTaskService taskService;
  private final GitlabWebhookPreciseSyncPlanner webhookPreciseSyncPlanner;
  private final GitlabMirrorProperties properties;
  private final JsonUtils jsonUtils;
  private final GitlabMirrorSyncService self;
  private final ConcurrentMap<Long, SyncProgress> progressMap = new ConcurrentHashMap<>();
  // 每个服务实例持有独立锁 owner，用于区分本机正在处理的长同步任务和历史遗留任务。
  private final String lockOwner = java.util.UUID.randomUUID().toString();

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
    this.self = self;
  }

  public boolean hasActiveTask(Long configId) {
    return taskService.hasActiveTask(configId);
  }

  public boolean hasExecutingTask(Long configId) {
    return taskService.hasExecutingTask(configId);
  }

  public SyncProgress getProgress(Long taskId) {
    return taskId == null ? null : progressMap.get(taskId);
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
    return submitTask(resolveConfig(configId), SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync");
  }

  public SyncTaskSubmissionResult startIncrementalSync(SyncTriggerType triggerType, String message) {
    return startIncrementalSync(null, triggerType, message);
  }

  public SyncTaskSubmissionResult startIncrementalSync(Long configId, SyncTriggerType triggerType, String message) {
    return submitTask(resolveConfig(configId), SyncType.INCREMENTAL, triggerType, message);
  }

  public SyncTaskSubmissionResult startWebhookSync(String eventType, Map<String, Object> payload) {
    return startWebhookSync(configService.getConfig(), eventType, payload);
  }

  public SyncTaskSubmissionResult startWebhookSync(GitlabSyncConfig config, String eventType, Map<String, Object> payload) {
    String effectiveEventType = eventType != null && !eventType.isBlank()
        ? eventType
        : String.valueOf(payload.getOrDefault("object_kind", "webhook"));
    return submitTask(
        config,
        SyncType.WEBHOOK,
        SyncTriggerType.WEBHOOK,
        "Triggered by webhook: " + effectiveEventType,
        Map.of(
            "eventType", effectiveEventType,
            "webhookPayload", payload));
  }

  public SyncTaskSubmissionResult startCompensationSync() {
    return startCompensationSync(configService.getConfig());
  }

  public SyncTaskSubmissionResult startCompensationSync(GitlabSyncConfig config) {
    return submitTask(config, SyncType.COMPENSATION, SyncTriggerType.SCHEDULE, "Scheduled compensation sync");
  }

  public int refreshTablesOnDemand(List<String> sourceTableNames, String reason) {
    if (sourceTableNames == null || sourceTableNames.isEmpty()) {
      return 0;
    }

    GitlabSyncConfig config = configService.getConfig();
    if (config.getId() == null || !config.isEnabled()) {
      return 0;
    }
    taskService.recoverTimedOutTasks();
    if (hasExecutingTask(config.getId())) {
      try (GitlabSyncLogContext.Scope context =
               GitlabSyncLogContext.openConfig(config, "ON_DEMAND_REFRESH");
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("SKIPPED")) {
        log.info("Skipped on-demand refresh because another sync task is executing, reason={}", reason);
      }
      return 0;
    }

    Set<String> targetTables = new LinkedHashSet<>(sourceTableNames);
    List<TableWhitelistOption> tables = whitelistService.listOptions(config).stream()
        .filter(option -> targetTables.contains(option.tableName()))
        .toList();
    if (tables.isEmpty()) {
      return 0;
    }

    int recordCount = 0;
    try (GitlabSyncLogContext.Scope context =
             GitlabSyncLogContext.openConfig(config, "ON_DEMAND_REFRESH");
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("EXECUTING")) {
      log.info("Starting on-demand mirror refresh, reason={}, tables={}", reason, targetTables);
      externalDbService.testConnection(config);
      for (TableWhitelistOption table : tables) {
        try {
          GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
              mirrorSchemaService.getPreparedMirrorTableForSync(config, table);
          mirrorSchemaService.markTableSyncing(config.getId(), table.tableName());
          LocalDateTime since = resolveOnDemandSince(config, preparedMirrorTable.registry(), table);
          List<Map<String, Object>> rows =
              externalDbService.incrementalScan(config, table, since);
          recordCount = writeMirrorRows(
              null,
              reason,
              config,
              table,
              preparedMirrorTable.mirrorSchema(),
              rows,
              recordCount,
              null);
          mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), LocalDateTime.now());
        } catch (Exception e) {
          mirrorSchemaService.markTableError(config.getId(), table.tableName());
          throw e;
        }
      }
      configService.updateSyncTime(config.getId(), false);
      log.info("Finished on-demand mirror refresh, reason={}, syncedRecords={}", reason, recordCount);
      return recordCount;
    }
  }

  public GitlabSyncTask requestCancel(Long configId) {
    return taskService.requestCancelLatest(configId);
  }

  public int executeRealtimeWebhookSync(
      GitlabSyncConfig config,
      Map<String, Object> webhookPayload,
      String objectId) {
    List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
    GitlabWebhookPreciseSyncPlan plan = webhookPreciseSyncPlanner.plan(webhookPayload);
    if (plan.targets().isEmpty()) {
      return 0;
    }
    long logId = logService.start(
        config.getId(),
        SyncType.WEBHOOK,
        plan.targets().stream().map(GitlabWebhookPreciseSyncTarget::tableName).toList(),
        "Webhook precise sync: " + plan.objectKey());
    int tableCount = 0;
    int recordCount = 0;
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openConfig(config, SyncType.WEBHOOK.name(), plan.objectKey());
         GitlabSyncLogContext.Scope objectScope = GitlabSyncLogContext.object(objectId);
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("EXECUTING")) {
      log.info("Executing realtime webhook sync, objectKey={}, targetCount={}", plan.objectKey(), plan.targets().size());
      Map<String, TableWhitelistOption> tableMap = tables.stream()
          .collect(java.util.stream.Collectors.toMap(TableWhitelistOption::tableName, table -> table, (left, right) -> left));
      for (GitlabWebhookPreciseSyncTarget target : plan.targets()) {
        TableWhitelistOption table = tableMap.get(target.tableName());
        if (table == null) {
          continue;
        }
        try {
          GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
              mirrorSchemaService.getPreparedMirrorTableForSync(config, table);
          SourceTableSchema mirrorSchema = preparedMirrorTable.mirrorSchema();
          mirrorSchemaService.markTableSyncing(config.getId(), table.tableName());
          try (GitlabSyncLogContext.Scope fetchAction = GitlabSyncLogContext.action("Data_Fetching")) {
            log.info(
                "Fetching precise webhook data, objectKey={}, tableName={}, lookupColumn={}, lookupValue={}",
                plan.objectKey(),
                table.tableName(),
                target.lookupColumn(),
                target.lookupValue());
          }
          List<Map<String, Object>> rows =
              externalDbService.preciseScan(config, table, target.lookupColumn(), target.lookupValue());
          recordCount = writeMirrorRows(
              null,
              objectId,
              config,
              table,
              mirrorSchema,
              rows,
              recordCount,
              null);
          tableCount++;
          mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), LocalDateTime.now());
        } catch (Exception e) {
          mirrorSchemaService.markTableError(config.getId(), table.tableName());
          throw e;
        }
      }
      logService.finish(logId, SyncStatus.SUCCESS, "Webhook precise sync completed", tableCount, recordCount);
      return recordCount;
    } catch (Exception e) {
      logService.finish(logId, SyncStatus.FAILED, e.getMessage(), tableCount, recordCount);
      throw e;
    }
  }

  private SyncTaskSubmissionResult submitTask(
      GitlabSyncConfig config,
      SyncType type,
      SyncTriggerType triggerType,
      String message) {
    return submitTask(config, type, triggerType, message, Map.of());
  }

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  private SyncTaskSubmissionResult submitTask(
      GitlabSyncConfig config,
      SyncType type,
      SyncTriggerType triggerType,
      String message,
      Map<String, Object> payload) {
    SyncTaskSubmissionResult result = taskService.submitTaskResult(config, type, triggerType, message, payload);
    GitlabSyncTask task = result.task();
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(task, config);
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Submit")) {
      log.info(
          "Sync task submission processed, triggerType={}, action={}, currentStatus={}, message={}",
          triggerType,
          result.action(),
          task.getStatus(),
          message);
    }
    if (task.getStatus() == SyncStatus.PENDING) {
      self.executeTaskAsync(task.getId());
    }
    return result;
  }

  @Async
  public void executeTaskAsync(Long taskId) {
    executeTask(taskId);
  }

  private void executeTask(Long taskId) {
    GitlabSyncTask task = taskService.claimPendingTask(taskId, lockOwner);
    if (task == null) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
    try (GitlabSyncLogContext.Scope context = GitlabSyncLogContext.openTask(task, config)) {
      List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
      long logId = logService.start(
          config.getId(),
          task.getTaskType(),
          tables.stream().map(TableWhitelistOption::tableName).toList(),
          taskService.extractMessage(task));
      int tableCount = 0;
      int recordCount = 0;
      int skippedTableCount = 0;
      try {
        try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_Start")) {
          log.info(
              "Task started, sourceMode={}, totalTables={}, triggerType={}, compensationWindowMinutes={}",
              task.getSourceMode(),
              tables.size(),
              task.getTriggerType(),
              task.getTaskType() == SyncType.COMPENSATION
                  ? config.getCompensationIntervalMinutes() + COMPENSATION_EXTRA_LOOKBACK_MINUTES
                  : 0);
        }

        SyncProgress currentProgress = new SyncProgress();
        currentProgress.setPhase(resolvePhase(task.getTaskType()));
        currentProgress.setTotalTables(tables.size());
        currentProgress.setCompletedTables(0);
        currentProgress.setSyncedRecords(0);
        currentProgress.setStartedAt(LocalDateTime.now());
        progressMap.put(taskId, currentProgress);

        checkCancelled(taskId);
        externalDbService.testConnection(config);
        LocalDateTime since = resolveSince(config, task.getTaskType());
        Map<String, Object> taskPayload = task.getPayloadJson() == null || task.getPayloadJson().isBlank()
            ? Map.of()
            : jsonUtils.toMap(task.getPayloadJson());
        boolean preciseWebhookHandled = false;
        if (task.getTaskType() == SyncType.WEBHOOK) {
          preciseWebhookHandled = executeWebhookPreciseSync(task, config, tables, taskPayload, currentProgress);
        }

        if (preciseWebhookHandled) {
          tableCount = currentProgress.getCompletedTables();
          recordCount = currentProgress.getSyncedRecords();
        } else {

          for (TableWhitelistOption table : tables) {
            try {
              checkCancelled(taskId);
              GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
                  mirrorSchemaService.getPreparedMirrorTableForSync(config, table);
              SourceTableSchema mirrorSchema = preparedMirrorTable.mirrorSchema();
              mirrorSchemaService.markTableSyncing(config.getId(), table.tableName());
              currentProgress.setCurrentTable(table.tableName());
              currentProgress.setCompletedTables(tableCount);
              currentProgress.setSyncedRecords(recordCount);
              taskService.heartbeat(taskId);

              boolean missingTimeColumn = table.updatedAtColumn() == null || table.updatedAtColumn().isBlank();
              boolean skipWindowedScan = missingTimeColumn
                  && (task.getTaskType() == SyncType.COMPENSATION
                      || task.getTaskType() == SyncType.INCREMENTAL
                      || task.getTaskType() == SyncType.WEBHOOK);
              if (skipWindowedScan) {
                skippedTableCount++;
                tableCount++;
                currentProgress.setCompletedTables(tableCount);
                mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), LocalDateTime.now());
                try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
                  log.info(
                      "Skipped table during {} because no time column exists, tableName={}",
                      resolveScanMode(task.getTaskType()),
                      table.tableName());
                }
                continue;
              }

              try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
                log.info(
                    "Fetching source data, tableName={}, scanMode={}, since={}, updatedAtColumn={}",
                    table.tableName(),
                    resolveScanMode(task.getTaskType()),
                    since,
                    table.updatedAtColumn());
              }

              List<Map<String, Object>> rows = switch (task.getTaskType()) {
                case FULL -> externalDbService.fullTableScan(config, table);
                case COMPENSATION -> externalDbService.compensationScan(config, table, since);
                case INCREMENTAL, WEBHOOK -> externalDbService.incrementalScan(config, table, since);
                case PURGE -> throw new IllegalStateException("Purge tasks are not executed by sync worker");
              };

              recordCount = writeMirrorRows(taskId, null, config, table, mirrorSchema, rows, recordCount, currentProgress);
              tableCount++;
              currentProgress.setCompletedTables(tableCount);
              currentProgress.setSyncedRecords(recordCount);
              mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), LocalDateTime.now());
            } catch (SyncCancelledException e) {
              mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), null);
              throw e;
            } catch (Exception e) {
              mirrorSchemaService.markTableError(config.getId(), table.tableName());
              throw e;
            }
          }
        }

        configService.updateSyncTime(config.getId(), task.getTaskType() == SyncType.FULL);
        String successMessage = buildCompletionMessage(task.getTaskType(), skippedTableCount);
        logService.finish(logId, SyncStatus.SUCCESS, successMessage, tableCount, recordCount);
        taskService.finish(taskId, SyncStatus.SUCCESS, successMessage, null);
      } catch (SyncCancelledException e) {
        try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_End")) {
          log.warn("Task cancelled during execution, completedTables={}, syncedRecords={}", tableCount, recordCount, e);
        }
        logService.finish(logId, SyncStatus.CANCELLED, e.getMessage(), tableCount, recordCount);
        taskService.finish(taskId, SyncStatus.CANCELLED, e.getMessage(), null);
      } catch (Exception e) {
        LocalDateTime cooldownUntil = LocalDateTime.now().plusMinutes(taskService.getFailureBackoffMinutes());
        try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Task_End")) {
          log.error(
              "Task failed, completedTables={}, syncedRecords={}, retryCount={}, retryBackoffMinutes={}",
              tableCount,
              recordCount,
              task.getRetryCount(),
              taskService.getFailureBackoffMinutes(),
              e);
        }
        logService.finish(logId, SyncStatus.FAILED, e.getMessage(), tableCount, recordCount);
        taskService.finish(taskId, SyncStatus.FAILED, e.getMessage(), cooldownUntil);
      } finally {
        progressMap.remove(taskId);
        GitlabSyncTask nextTask = taskService.promoteNextQueued(task.getScopeKey());
        if (nextTask != null) {
          self.executeTaskAsync(nextTask.getId());
        }
      }
    }
  }

  private String resolvePhase(SyncType type) {
    return switch (type) {
      case FULL -> "FULL_SYNC";
      case COMPENSATION -> "COMPENSATION_SYNC";
      case INCREMENTAL, WEBHOOK -> "INCREMENTAL_SYNC";
      case PURGE -> "PURGE";
    };
  }

  private String resolveScanMode(SyncType type) {
    return switch (type) {
      case FULL -> "FULL";
      case COMPENSATION -> "WINDOW_COMPENSATION";
      case INCREMENTAL, WEBHOOK -> "INCREMENTAL";
      case PURGE -> "PURGE";
    };
  }

  private LocalDateTime resolveOnDemandSince(
      GitlabSyncConfig config,
      com.data.collection.platform.entity.GitlabMirrorTableRegistry registry,
      TableWhitelistOption table) {
    if (table.updatedAtColumn() == null || table.updatedAtColumn().isBlank()) {
      return null;
    }

    LocalDateTime baseline = registry != null && registry.getLastSyncTime() != null
        ? registry.getLastSyncTime()
        : config.getLastIncrementalSyncAt();
    if (baseline == null) {
      baseline = LocalDateTime.now().minusHours(24);
    }
    return baseline.minusMinutes(resolveIncrementalLookbackMinutes());
  }

  private void checkCancelled(Long taskId) {
    if (taskService.isCancelRequested(taskId)) {
      throw new SyncCancelledException("同步已被用户中止");
    }
  }

  private boolean executeWebhookPreciseSync(
      GitlabSyncTask task,
      GitlabSyncConfig config,
      List<TableWhitelistOption> tables,
      Map<String, Object> taskPayload,
      SyncProgress currentProgress) {
    @SuppressWarnings("unchecked")
    Map<String, Object> webhookPayload = taskPayload.get("webhookPayload") instanceof Map<?, ?> payload
        ? (Map<String, Object>) payload
        : Map.of();
    List<GitlabWebhookPreciseSyncTarget> targets = webhookPreciseSyncPlanner.planTargets(webhookPayload);
    if (targets.isEmpty()) {
      return false;
    }

    Map<String, TableWhitelistOption> tableMap = tables.stream()
        .collect(java.util.stream.Collectors.toMap(TableWhitelistOption::tableName, table -> table, (left, right) -> left));

    int tableCount = 0;
    int recordCount = 0;
    for (GitlabWebhookPreciseSyncTarget target : targets) {
      TableWhitelistOption table = tableMap.get(target.tableName());
      if (table == null) {
        continue;
      }
      try {
        checkCancelled(task.getId());
        GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
            mirrorSchemaService.getPreparedMirrorTableForSync(config, table);
        SourceTableSchema mirrorSchema = preparedMirrorTable.mirrorSchema();
        mirrorSchemaService.markTableSyncing(config.getId(), table.tableName());
        currentProgress.setCurrentTable(table.tableName());
        currentProgress.setCompletedTables(tableCount);
        currentProgress.setSyncedRecords(recordCount);
        taskService.heartbeat(task.getId());
        try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Data_Fetching")) {
          log.info(
              "Fetching precise webhook data, tableName={}, lookupColumn={}, lookupValue={}",
              table.tableName(),
              target.lookupColumn(),
              target.lookupValue());
        }
        List<Map<String, Object>> rows =
            externalDbService.preciseScan(config, table, target.lookupColumn(), target.lookupValue());
        recordCount = writeMirrorRows(task.getId(), null, config, table, mirrorSchema, rows, recordCount, currentProgress);
        tableCount++;
        currentProgress.setCompletedTables(tableCount);
        currentProgress.setSyncedRecords(recordCount);
        mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), LocalDateTime.now());
      } catch (SyncCancelledException e) {
        mirrorSchemaService.markTableIdle(config.getId(), table.tableName(), null);
        throw e;
      } catch (Exception e) {
        mirrorSchemaService.markTableError(config.getId(), table.tableName());
        throw e;
      }
    }
    return true;
  }

  private int writeMirrorRows(
      Long taskId,
      String objectId,
      GitlabSyncConfig config,
      TableWhitelistOption table,
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      int recordCount,
      SyncProgress currentProgress) {
    List<GitlabMirrorRecord> mirrorRows = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      GitlabMirrorRecord record = new GitlabMirrorRecord();
      record.setConfigId(config.getId());
      record.setTableName(mirrorSchema.tableName());
      record.setRecordKey(externalDbService.buildRecordKey(table, row));
      record.setUpdatedAtSource(externalDbService.extractUpdatedAt(table, row));
      record.setRowData(jsonUtils.toJson(row));
      mirrorRows.add(record);
    }

    for (int index = 0; index < mirrorRows.size(); index += UPSERT_BATCH_SIZE) {
      if (taskId != null) {
        checkCancelled(taskId);
      }
      int end = Math.min(index + UPSERT_BATCH_SIZE, mirrorRows.size());
      int batchSize = end - index;
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Mirror_Writing")) {
        log.info(
            "Writing mirror batch, tableName={}, batchSize={}, batchStart={}, batchEnd={}",
            table.tableName(),
            batchSize,
            index,
            end);
      }
      MirrorBatchWriteResult writeResult =
          mirrorTableStorageService.upsertBatch(mirrorSchema, rows.subList(index, end), taskId);
      mirrorRecordMapper.upsertBatch(mirrorRows.subList(index, end));
      recordCount += writeResult.appliedRows();
      if (currentProgress != null) {
        currentProgress.setSyncedRecords(recordCount);
      }
      if (taskId != null) {
        taskService.heartbeat(taskId);
      }
      if (writeResult.skippedConflicts() > 0) {
        try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("UPSERT_CONFLICT_SKIPPED");
             GitlabSyncLogContext.Scope objectScope = GitlabSyncLogContext.object(objectId)) {
          log.info(
              "Skipped stale rows during upsert, tableName={}, skippedConflicts={}, objectId={}",
              table.tableName(),
              writeResult.skippedConflicts(),
              objectId == null ? "" : objectId);
        }
      }
      try (GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("Commit_Success")) {
        log.info(
            "Mirror batch committed, tableName={}, attemptedRows={}, appliedRows={}, cumulativeRecords={}",
            table.tableName(),
            batchSize,
            writeResult.appliedRows(),
            recordCount);
      }
    }
    return recordCount;
  }

  private LocalDateTime resolveSince(GitlabSyncConfig config, SyncType type) {
    if (type == SyncType.FULL) {
      return null;
    }
    if (type == SyncType.COMPENSATION) {
      int intervalMinutes = config.getCompensationIntervalMinutes() == null ? 10 : config.getCompensationIntervalMinutes();
      return LocalDateTime.now().minusMinutes((long) intervalMinutes + COMPENSATION_EXTRA_LOOKBACK_MINUTES);
    }
    LocalDateTime baseline = config.getLastIncrementalSyncAt() != null
        ? config.getLastIncrementalSyncAt()
        : config.getLastFullSyncAt();
    return baseline == null ? null : baseline.minusMinutes(resolveIncrementalLookbackMinutes());
  }

  private int resolveIncrementalLookbackMinutes() {
    return Math.max(0, properties.getIncrementalLookbackMinutes());
  }

  private String buildCompletionMessage(SyncType type, int skippedTableCount) {
    if (type != SyncType.COMPENSATION || skippedTableCount <= 0) {
      return "同步已完成";
    }
    return "同步已完成，补偿窗口扫描时跳过了 %d 张缺少时间列的表"
        .formatted(skippedTableCount);
  }

  private static final class SyncCancelledException extends RuntimeException {
    private SyncCancelledException(String message) {
      super(message);
    }
  }
}
