package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableShardProbe;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.sql.Timestamp;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabTableSyncWorkerService {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);
  private static final int DAILY_VERIFY_SHARD_KEY_LENGTH = 2;
  private static final String WORKER_ID = resolveWorkerId();

  private final GitlabTableSyncTaskMapper taskMapper;
  private final GitlabTableSyncStateMapper stateMapper;
  private final GitlabSyncJobMapper jobMapper;
  private final GitlabConfigService configService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final GitlabMirrorTableStorageService storageService;
  private final GitlabMirrorProperties properties;
  private final FactBuildTaskService factBuildTaskService;
  private final JsonUtils jsonUtils;

  public GitlabTableSyncWorkerService(
      GitlabTableSyncTaskMapper taskMapper,
      GitlabTableSyncStateMapper stateMapper,
      GitlabSyncJobMapper jobMapper,
      GitlabConfigService configService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService storageService,
      GitlabMirrorProperties properties,
      FactBuildTaskService factBuildTaskService,
      JsonUtils jsonUtils) {
    this.taskMapper = taskMapper;
    this.stateMapper = stateMapper;
    this.jobMapper = jobMapper;
    this.configService = configService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.storageService = storageService;
    this.properties = properties;
    this.factBuildTaskService = factBuildTaskService;
    this.jsonUtils = jsonUtils;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.table-worker-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    recoverTimedOutTasks();
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.PENDING)
        .and(wrapper -> wrapper.isNull(GitlabTableSyncTask::getRunAfter)
            .or()
            .le(GitlabTableSyncTask::getRunAfter, now))
        .orderByAsc(GitlabTableSyncTask::getCreatedAt)
        .last("limit 1"));
    if (task == null) {
      return;
    }
    executeTask(task);
  }

  int recoverTimedOutTasks() {
    LocalDateTime now = LocalDateTime.now();
    List<GitlabTableSyncTask> staleTasks = taskMapper.selectList(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.RUNNING)
        .lt(GitlabTableSyncTask::getLeaseUntil, now));
    for (GitlabTableSyncTask task : staleTasks) {
      markTimeoutAndRetry(task, now);
      finishJobIfComplete(task.getJobId());
    }
    return staleTasks.size();
  }

  void executeTask(GitlabTableSyncTask task) {
    LocalDateTime now = LocalDateTime.now();
    markTaskRunning(task, now);
    GitlabTableSyncState state = findState(task);
    try {
      if (state == null) {
        throw new IllegalStateException("Table sync state is missing");
      }
      if (task.getTaskType() == GitlabTableSyncTaskType.DAILY_VERIFY) {
        executeDailyVerify(task, state);
      } else if (task.getTaskType() == GitlabTableSyncTaskType.DELETE_RECONCILE) {
        executeDeleteReconcileTask(task, state);
      } else if (task.getTaskType() == GitlabTableSyncTaskType.SHARD_REPAIR) {
        executeShardRepairTask(task, state);
      } else {
        executeIncrementalLikeTask(task, state);
      }
      finishJobIfComplete(task.getJobId());
    } catch (Exception e) {
      markFailure(task, state, e);
      finishJobIfComplete(task.getJobId());
      log.warn(
          "Table sync task failed, taskId={}, configId={}, sourceTable={}",
          task.getId(),
          task.getConfigId(),
          task.getSourceTable(),
          e);
    }
  }

  private void executeDailyVerify(GitlabTableSyncTask task, GitlabTableSyncState state) {
    GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
    TableWhitelistOption option = tableOption(state);
    GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
        mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
    GitlabTableProbe sourceProbe = externalDbService.probeTable(config, option);
    GitlabTableProbe mirrorProbe = storageService.probeMirrorTable(preparedMirrorTable.mirrorSchema());
    List<GitlabTableShardProbe> driftedShards = findDriftedSourceShards(
        externalDbService.probeTableShards(config, option, preparedMirrorTable.mirrorSchema(), DAILY_VERIFY_SHARD_KEY_LENGTH),
        storageService.probeMirrorTableShards(preparedMirrorTable.mirrorSchema(), DAILY_VERIFY_SHARD_KEY_LENGTH));
    boolean drifted = isDrifted(sourceProbe, mirrorProbe, state) || !driftedShards.isEmpty();
    markVerificationSuccess(task, state, sourceProbe, mirrorProbe, drifted);
    if (!drifted) {
      return;
    }
    if (shouldCreateDeleteReconcileTask(sourceProbe, mirrorProbe)) {
      taskMapper.insert(createDeleteReconcileTask(task, state));
    }
    if (!driftedShards.isEmpty()) {
      for (GitlabTableShardProbe shard : driftedShards) {
        taskMapper.insert(createShardRepairTask(task, state, shard.shardKey()));
      }
    } else if (shouldCreateFullRepairTask(sourceProbe, mirrorProbe, state)) {
      taskMapper.insert(createRepairTask(task, state));
    }
  }

  private void executeShardRepairTask(GitlabTableSyncTask task, GitlabTableSyncState state) {
    GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
    TableWhitelistOption option = tableOption(state);
    GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
        mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
    mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());

    ShardRepairCursor cursor = decodeShardRepairCursor(task.getCursorPk());
    List<Map<String, Object>> sourceRows = externalDbService.shardCursorScan(
        config,
        option,
        preparedMirrorTable.mirrorSchema(),
        cursor.shardKey(),
        cursor.rowCursor(),
        resolveBatchSize(task));
    List<Map<String, Object>> rows = sourceRows.stream()
        .map(this::withoutInternalColumns)
        .toList();
    MirrorBatchWriteResult writeResult =
        storageService.upsertBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId());
    String lastCursor = sourceRows.isEmpty() ? null : Objects.toString(sourceRows.get(sourceRows.size() - 1).get("pk_signature"), "");
    boolean hasMore = rows.size() >= resolveBatchSize(task) && lastCursor != null && !lastCursor.isBlank();
    markShardRepairSuccess(task, state, rows.size(), writeResult.appliedRows(), hasMore);
    if (hasMore) {
      taskMapper.insert(createShardRepairContinuationTask(task, cursor.shardKey(), lastCursor));
    }
    mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
  }

  private void executeDeleteReconcileTask(GitlabTableSyncTask task, GitlabTableSyncState state) {
    GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
    TableWhitelistOption option = tableOption(state);
    GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
        mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
    mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());

    MirrorPrimaryKeyBatch batch = storageService.listActivePrimaryKeys(
        preparedMirrorTable.mirrorSchema(),
        task.getCursorPk(),
        resolveBatchSize(task));
    Set<String> sourceKeys = externalDbService.findExistingPrimaryKeySignatures(config, option, batch.keys());
    List<String> primaryKeys = PrimaryKeySignatureSupport.primaryKeyColumns(preparedMirrorTable.mirrorSchema());
    List<Map<String, Object>> missingKeys = batch.keys().stream()
        .filter(row -> !sourceKeys.contains(PrimaryKeySignatureSupport.signature(primaryKeys, row)))
        .toList();
    int deletedRows = storageService.markRowsDeletedByPrimaryKeys(
        preparedMirrorTable.mirrorSchema(),
        missingKeys,
        task.getId());
    boolean hasMore = batch.keys().size() >= resolveBatchSize(task) && batch.nextCursor() != null;
    markDeleteReconcileSuccess(task, state, batch.keys().size(), deletedRows, hasMore);
    if (hasMore) {
      taskMapper.insert(createDeleteContinuationTask(task, batch.nextCursor()));
    }
    mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
  }

  private void executeIncrementalLikeTask(GitlabTableSyncTask task, GitlabTableSyncState state) {
    if (state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL || !state.isSyncEnabled()) {
      throw new IllegalStateException("Table task is not executable by incremental worker");
    }
    GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
    TableWhitelistOption option = tableOption(state);
    GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
        mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
    mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());

    LocalDateTime since = resolveScanStart(task);
    List<Map<String, Object>> rows = externalDbService.incrementalCursorScan(
        config,
        option,
        since,
        task.getCursorUpdatedAt(),
        task.getCursorPk(),
        resolveBatchSize(task));
    MirrorBatchWriteResult writeResult =
        storageService.upsertBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId());
    RowCursor lastCursor = lastCursor(rows, state);
    boolean hasMore = rows.size() >= resolveBatchSize(task) && lastCursor.updatedAt() != null;
    markSuccess(task, state, rows.size(), writeResult.appliedRows(), lastCursor, hasMore);
    if (hasMore) {
      taskMapper.insert(createContinuationTask(task, lastCursor));
    }
    mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
  }

  private void markTaskRunning(GitlabTableSyncTask task, LocalDateTime now) {
    task.setStatus(SyncStatus.RUNNING);
    task.setStartedAt(now);
    task.setHeartbeatAt(now);
    task.setLeaseOwner(WORKER_ID);
    task.setLeaseUntil(now.plusSeconds(Math.max(1, properties.getHeartbeatTimeoutSeconds())));
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    GitlabSyncJob job = jobMapper.selectById(task.getJobId());
    if (job != null && job.getStatus() == SyncStatus.PENDING) {
      job.setStatus(SyncStatus.RUNNING);
      job.setStartedAt(now);
      job.setHeartbeatAt(now);
      job.setLeaseOwner(WORKER_ID);
      job.setLeaseUntil(now.plusSeconds(Math.max(1, properties.getHeartbeatTimeoutSeconds())));
      job.setUpdatedAt(now);
      jobMapper.updateById(job);
    }
  }

  private GitlabTableSyncState findState(GitlabTableSyncTask task) {
    return stateMapper.selectOne(new LambdaQueryWrapper<GitlabTableSyncState>()
        .eq(GitlabTableSyncState::getConfigId, task.getConfigId())
        .eq(GitlabTableSyncState::getSourceInstance, task.getSourceInstance())
        .eq(GitlabTableSyncState::getSourceTable, task.getSourceTable())
        .last("limit 1"));
  }

  private LocalDateTime resolveScanStart(GitlabTableSyncTask task) {
    LocalDateTime watermark = task.getWatermarkAt() == null ? INITIAL_WATERMARK : task.getWatermarkAt();
    return watermark.minusMinutes(Math.max(0, properties.getIncrementalLookbackMinutes()));
  }

  private void markSuccess(
      GitlabTableSyncTask task,
      GitlabTableSyncState state,
      int scannedRows,
      int appliedRows,
      RowCursor lastCursor,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(SyncStatus.SUCCESS);
    task.setRowsScanned((long) scannedRows);
    task.setRowsApplied((long) appliedRows);
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    if (lastCursor.updatedAt() != null) {
      state.setLastWatermarkAt(lastCursor.updatedAt());
      state.setLastCursorPk(lastCursor.primaryKey());
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  private void markVerificationSuccess(
      GitlabTableSyncTask task,
      GitlabTableSyncState state,
      GitlabTableProbe sourceProbe,
      GitlabTableProbe mirrorProbe,
      boolean drifted) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(SyncStatus.SUCCESS);
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    state.setSourceRowCount(sourceProbe.rowCount());
    state.setMirrorRowCount(mirrorProbe.rowCount());
    state.setSourceMaxUpdatedAt(sourceProbe.maxUpdatedAt());
    state.setLastFullVerifiedAt(now);
    state.setDirtyFlag(drifted);
    state.setLastError(drifted ? buildDriftMessage(sourceProbe, mirrorProbe, state) : "");
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  private void markDeleteReconcileSuccess(
      GitlabTableSyncTask task,
      GitlabTableSyncState state,
      int scannedRows,
      int deletedRows,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(SyncStatus.SUCCESS);
    task.setRowsScanned((long) scannedRows);
    task.setRowsApplied((long) deletedRows);
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  private void markShardRepairSuccess(
      GitlabTableSyncTask task,
      GitlabTableSyncState state,
      int scannedRows,
      int appliedRows,
      boolean hasMore) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(SyncStatus.SUCCESS);
    task.setRowsScanned((long) scannedRows);
    task.setRowsApplied((long) appliedRows);
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    state.setDirtyFlag(hasMore);
    state.setLastSuccessAt(now);
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
  }

  private GitlabTableSyncTask createContinuationTask(GitlabTableSyncTask previousTask, RowCursor cursor) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(previousTask.getJobId());
    task.setConfigId(previousTask.getConfigId());
    task.setSourceInstance(previousTask.getSourceInstance());
    task.setSourceTable(previousTask.getSourceTable());
    task.setMirrorTable(previousTask.getMirrorTable());
    task.setTaskType(previousTask.getTaskType());
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(previousTask.getRowStrategy());
    task.setWatermarkAt(previousTask.getWatermarkAt());
    task.setCursorUpdatedAt(cursor.updatedAt());
    task.setCursorPk(cursor.primaryKey());
    task.setBatchSize(resolveBatchSize(previousTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(previousTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabTableSyncTask createRepairTask(GitlabTableSyncTask verificationTask, GitlabTableSyncState state) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(verificationTask.getJobId());
    task.setConfigId(verificationTask.getConfigId());
    task.setSourceInstance(verificationTask.getSourceInstance());
    task.setSourceTable(verificationTask.getSourceTable());
    task.setMirrorTable(verificationTask.getMirrorTable());
    task.setTaskType(GitlabTableSyncTaskType.FULL_REPAIR);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(GitlabTableRowStrategy.INCREMENTAL);
    task.setWatermarkAt(INITIAL_WATERMARK);
    task.setBatchSize(resolveBatchSize(verificationTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(verificationTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabTableSyncTask createDeleteReconcileTask(GitlabTableSyncTask verificationTask, GitlabTableSyncState state) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(verificationTask.getJobId());
    task.setConfigId(verificationTask.getConfigId());
    task.setSourceInstance(verificationTask.getSourceInstance());
    task.setSourceTable(verificationTask.getSourceTable());
    task.setMirrorTable(verificationTask.getMirrorTable());
    task.setTaskType(GitlabTableSyncTaskType.DELETE_RECONCILE);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(state.getRowStrategy());
    task.setBatchSize(resolveBatchSize(verificationTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(verificationTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabTableSyncTask createShardRepairTask(
      GitlabTableSyncTask verificationTask,
      GitlabTableSyncState state,
      String shardKey) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(verificationTask.getJobId());
    task.setConfigId(verificationTask.getConfigId());
    task.setSourceInstance(verificationTask.getSourceInstance());
    task.setSourceTable(verificationTask.getSourceTable());
    task.setMirrorTable(verificationTask.getMirrorTable());
    task.setTaskType(GitlabTableSyncTaskType.SHARD_REPAIR);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(state.getRowStrategy());
    task.setCursorPk(encodeShardRepairCursor(shardKey, null));
    task.setBatchSize(resolveBatchSize(verificationTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(verificationTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabTableSyncTask createShardRepairContinuationTask(
      GitlabTableSyncTask previousTask,
      String shardKey,
      String rowCursor) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(previousTask.getJobId());
    task.setConfigId(previousTask.getConfigId());
    task.setSourceInstance(previousTask.getSourceInstance());
    task.setSourceTable(previousTask.getSourceTable());
    task.setMirrorTable(previousTask.getMirrorTable());
    task.setTaskType(GitlabTableSyncTaskType.SHARD_REPAIR);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(previousTask.getRowStrategy());
    task.setCursorPk(encodeShardRepairCursor(shardKey, rowCursor));
    task.setBatchSize(resolveBatchSize(previousTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(previousTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private GitlabTableSyncTask createDeleteContinuationTask(GitlabTableSyncTask previousTask, String cursor) {
    LocalDateTime now = LocalDateTime.now();
    GitlabTableSyncTask task = new GitlabTableSyncTask();
    task.setJobId(previousTask.getJobId());
    task.setConfigId(previousTask.getConfigId());
    task.setSourceInstance(previousTask.getSourceInstance());
    task.setSourceTable(previousTask.getSourceTable());
    task.setMirrorTable(previousTask.getMirrorTable());
    task.setTaskType(GitlabTableSyncTaskType.DELETE_RECONCILE);
    task.setStatus(SyncStatus.PENDING);
    task.setRowStrategy(previousTask.getRowStrategy());
    task.setCursorPk(cursor);
    task.setBatchSize(resolveBatchSize(previousTask));
    task.setRunAfter(now);
    task.setRetryCount(0);
    task.setMaxRetryCount(previousTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private void markFailure(GitlabTableSyncTask task, GitlabTableSyncState state, Exception e) {
    LocalDateTime now = LocalDateTime.now();
    mirrorSchemaService.markTableError(task.getConfigId(), task.getSourceTable());
    int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
    int maxRetryCount = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();
    boolean willRetry = retryCount < maxRetryCount;
    task.setStatus(willRetry ? SyncStatus.RETRYING : SyncStatus.FAILED);
    task.setLastError(e.getMessage());
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    if (state != null) {
      state.setDirtyFlag(true);
      state.setLastError(e.getMessage());
      state.setRetryCount(state.getRetryCount() == null ? 1 : state.getRetryCount() + 1);
      state.setUpdatedAt(now);
      stateMapper.updateById(state);
    }

    if (willRetry) {
      GitlabTableSyncTask retryTask = copyForRetry(task, retryCount + 1, now);
      retryTask.setRunAfter(now.plusMinutes(resolveFailureBackoffMinutes(retryCount + 1)));
      taskMapper.insert(retryTask);
    }
  }

  private void markTimeoutAndRetry(GitlabTableSyncTask task, LocalDateTime now) {
    int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
    int maxRetryCount = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();
    boolean willRetry = retryCount < maxRetryCount;
    task.setStatus(willRetry ? SyncStatus.RETRYING : SyncStatus.TIMEOUT);
    task.setLastError("Table sync task lease timed out");
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    GitlabTableSyncState state = findState(task);
    if (state != null) {
      state.setDirtyFlag(true);
      state.setLastError(task.getLastError());
      state.setRetryCount(state.getRetryCount() == null ? 1 : state.getRetryCount() + 1);
      state.setUpdatedAt(now);
      stateMapper.updateById(state);
    }

    if (willRetry) {
      GitlabTableSyncTask retryTask = copyForRetry(task, retryCount + 1, now);
      taskMapper.insert(retryTask);
      return;
    }

  }

  private GitlabTableSyncTask copyForRetry(GitlabTableSyncTask timedOutTask, int retryCount, LocalDateTime now) {
    GitlabTableSyncTask retryTask = new GitlabTableSyncTask();
    retryTask.setJobId(timedOutTask.getJobId());
    retryTask.setConfigId(timedOutTask.getConfigId());
    retryTask.setSourceInstance(timedOutTask.getSourceInstance());
    retryTask.setSourceTable(timedOutTask.getSourceTable());
    retryTask.setMirrorTable(timedOutTask.getMirrorTable());
    retryTask.setTaskType(timedOutTask.getTaskType());
    retryTask.setStatus(SyncStatus.PENDING);
    retryTask.setRowStrategy(timedOutTask.getRowStrategy());
    retryTask.setWatermarkAt(timedOutTask.getWatermarkAt());
    retryTask.setCursorUpdatedAt(timedOutTask.getCursorUpdatedAt());
    retryTask.setCursorPk(timedOutTask.getCursorPk());
    retryTask.setBatchSize(resolveBatchSize(timedOutTask));
    retryTask.setRunAfter(now.plusMinutes(resolveFailureBackoffMinutes(retryCount)));
    retryTask.setRetryCount(retryCount);
    retryTask.setMaxRetryCount(timedOutTask.getMaxRetryCount());
    retryTask.setRowsScanned(0L);
    retryTask.setRowsApplied(0L);
    retryTask.setCreatedAt(now);
    retryTask.setUpdatedAt(now);
    return retryTask;
  }

  private void finishJobIfComplete(Long jobId) {
    Long remaining = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getJobId, jobId)
        .in(GitlabTableSyncTask::getStatus, List.of(SyncStatus.PENDING, SyncStatus.RUNNING)));
    if (remaining != null && remaining > 0) {
      return;
    }
    GitlabSyncJob job = jobMapper.selectById(jobId);
    if (job == null) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    Long failed = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getJobId, jobId)
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.FAILED));
    Long timedOut = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getJobId, jobId)
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.TIMEOUT));
    Long succeeded = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getJobId, jobId)
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.SUCCESS));
    long failedCount = failed == null ? 0 : failed;
    long timedOutCount = timedOut == null ? 0 : timedOut;
    long successCount = succeeded == null ? 0 : succeeded;
    long badCount = failedCount + timedOutCount;
    if (badCount == 0) {
      job.setStatus(SyncStatus.SUCCESS);
      job.setErrorMessage(null);
    } else if (successCount > 0) {
      job.setStatus(SyncStatus.PARTIAL_SUCCESS);
      job.setErrorMessage("Some table sync tasks failed or timed out");
    } else if (timedOutCount > 0 && failedCount == 0) {
      job.setStatus(SyncStatus.TIMEOUT);
      job.setErrorMessage("All table sync tasks timed out");
    } else {
      job.setStatus(SyncStatus.FAILED);
      job.setErrorMessage("All table sync tasks failed");
    }
    job.setFinishedAt(now);
    job.setUpdatedAt(now);
    jobMapper.updateById(job);
    if (shouldEnqueueFactRefresh(job)) {
      GitlabSyncConfig config = configService.getConfigById(job.getConfigId());
      factBuildTaskService.enqueueMirrorRefreshTasks(config, job.getJobType() == GitlabSyncJobType.DAILY_VERIFY);
    }
  }

  private boolean shouldEnqueueFactRefresh(GitlabSyncJob job) {
    if (job.getJobType() == GitlabSyncJobType.COMPENSATION_SCAN
        || job.getJobType() == GitlabSyncJobType.MANUAL_REFRESH) {
      return true;
    }
    if (job.getJobType() != GitlabSyncJobType.DAILY_VERIFY) {
      return false;
    }
    Long repairTasks = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getJobId, job.getId())
        .in(GitlabTableSyncTask::getTaskType, List.of(
            GitlabTableSyncTaskType.FULL_REPAIR,
            GitlabTableSyncTaskType.SHARD_REPAIR,
            GitlabTableSyncTaskType.DELETE_RECONCILE)));
    return repairTasks != null && repairTasks > 0;
  }

  private RowCursor lastCursor(List<Map<String, Object>> rows, GitlabTableSyncState state) {
    if (rows == null || rows.isEmpty() || state.getUpdatedAtColumn() == null || state.getUpdatedAtColumn().isBlank()) {
      return RowCursor.empty();
    }
    Map<String, Object> row = rows.get(rows.size() - 1);
    LocalDateTime updatedAt = toLocalDateTime(row.get(state.getUpdatedAtColumn()));
    Object primaryKey = row.get(firstPrimaryKey(state.getPrimaryKeyColumns()));
    return new RowCursor(updatedAt, primaryKey == null ? "" : String.valueOf(primaryKey));
  }

  private TableWhitelistOption tableOption(GitlabTableSyncState state) {
    return new TableWhitelistOption(
        state.getSourceTable(),
        state.getSourceTable(),
        state.getPrimaryKeyColumns(),
        state.getUpdatedAtColumn(),
        true);
  }

  private boolean isDrifted(GitlabTableProbe sourceProbe, GitlabTableProbe mirrorProbe, GitlabTableSyncState state) {
    if (sourceProbe.rowCount() != mirrorProbe.rowCount()) {
      return true;
    }
    if (!Objects.equals(sourceProbe.minPrimaryKey(), mirrorProbe.minPrimaryKey())
        || !Objects.equals(sourceProbe.maxPrimaryKey(), mirrorProbe.maxPrimaryKey())) {
      return true;
    }
    return state.getUpdatedAtColumn() != null
        && !state.getUpdatedAtColumn().isBlank()
        && !Objects.equals(sourceProbe.maxUpdatedAt(), mirrorProbe.maxUpdatedAt());
  }

  private boolean shouldCreateDeleteReconcileTask(GitlabTableProbe sourceProbe, GitlabTableProbe mirrorProbe) {
    if (mirrorProbe.rowCount() > sourceProbe.rowCount()) {
      return true;
    }
    return mirrorProbe.rowCount() == sourceProbe.rowCount()
        && (!Objects.equals(sourceProbe.minPrimaryKey(), mirrorProbe.minPrimaryKey())
            || !Objects.equals(sourceProbe.maxPrimaryKey(), mirrorProbe.maxPrimaryKey()));
  }

  private List<GitlabTableShardProbe> findDriftedSourceShards(
      List<GitlabTableShardProbe> sourceShards,
      List<GitlabTableShardProbe> mirrorShards) {
    if (sourceShards == null || sourceShards.isEmpty()) {
      return List.of();
    }
    List<GitlabTableShardProbe> safeMirrorShards = mirrorShards == null ? List.of() : mirrorShards;
    Map<String, GitlabTableShardProbe> mirrorByKey = new LinkedHashMap<>();
    for (GitlabTableShardProbe mirrorShard : safeMirrorShards) {
      mirrorByKey.put(mirrorShard.shardKey(), mirrorShard);
    }
    List<GitlabTableShardProbe> drifted = new ArrayList<>();
    for (GitlabTableShardProbe sourceShard : sourceShards) {
      GitlabTableShardProbe mirrorShard = mirrorByKey.get(sourceShard.shardKey());
      if (mirrorShard == null || isShardDrifted(sourceShard, mirrorShard)) {
        drifted.add(sourceShard);
      }
    }
    return drifted;
  }

  private boolean isShardDrifted(GitlabTableShardProbe sourceShard, GitlabTableShardProbe mirrorShard) {
    return sourceShard.rowCount() != mirrorShard.rowCount()
        || !Objects.equals(sourceShard.maxUpdatedAt(), mirrorShard.maxUpdatedAt())
        || !Objects.equals(sourceShard.minPrimaryKey(), mirrorShard.minPrimaryKey())
        || !Objects.equals(sourceShard.maxPrimaryKey(), mirrorShard.maxPrimaryKey())
        || !Objects.equals(sourceShard.checksum(), mirrorShard.checksum());
  }

  private boolean shouldCreateFullRepairTask(
      GitlabTableProbe sourceProbe,
      GitlabTableProbe mirrorProbe,
      GitlabTableSyncState state) {
    if (state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL || !state.isSyncEnabled()) {
      return false;
    }
    if (sourceProbe.rowCount() > mirrorProbe.rowCount()) {
      return true;
    }
    if (sourceProbe.rowCount() == mirrorProbe.rowCount()
        && (!Objects.equals(sourceProbe.minPrimaryKey(), mirrorProbe.minPrimaryKey())
            || !Objects.equals(sourceProbe.maxPrimaryKey(), mirrorProbe.maxPrimaryKey()))) {
      return true;
    }
    return state.getUpdatedAtColumn() != null
        && !state.getUpdatedAtColumn().isBlank()
        && sourceProbe.maxUpdatedAt() != null
        && (mirrorProbe.maxUpdatedAt() == null || sourceProbe.maxUpdatedAt().isAfter(mirrorProbe.maxUpdatedAt()));
  }

  private String buildDriftMessage(
      GitlabTableProbe sourceProbe,
      GitlabTableProbe mirrorProbe,
      GitlabTableSyncState state) {
    if (state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL) {
      return "Daily verification found drift, but table has no updated_at column for incremental repair";
    }
    return "Daily verification found drift: sourceRows=%d, mirrorRows=%d, sourceMaxUpdatedAt=%s, mirrorMaxUpdatedAt=%s"
        .formatted(
            sourceProbe.rowCount(),
            mirrorProbe.rowCount(),
            sourceProbe.maxUpdatedAt(),
            mirrorProbe.maxUpdatedAt());
  }

  private int resolveBatchSize(GitlabTableSyncTask task) {
    return task.getBatchSize() == null || task.getBatchSize() < 1 ? 500 : task.getBatchSize();
  }

  private String encodeShardRepairCursor(String shardKey, String rowCursor) {
    return jsonUtils.toJson(Map.of(
        "shardKey", shardKey,
        "rowCursor", rowCursor == null ? "" : rowCursor));
  }

  private ShardRepairCursor decodeShardRepairCursor(String cursorJson) {
    Map<String, Object> cursor = jsonUtils.toMap(cursorJson);
    String shardKey = Objects.toString(cursor.get("shardKey"), "");
    if (shardKey.isBlank()) {
      throw new IllegalStateException("Shard repair task cursor is missing shardKey");
    }
    String rowCursor = Objects.toString(cursor.get("rowCursor"), "");
    return new ShardRepairCursor(shardKey, rowCursor.isBlank() ? null : rowCursor);
  }

  private Map<String, Object> withoutInternalColumns(Map<String, Object> row) {
    Map<String, Object> copy = new LinkedHashMap<>(row);
    copy.remove("pk_signature");
    return copy;
  }

  private long resolveFailureBackoffMinutes(int retryCount) {
    int baseMinutes = Math.max(1, properties.getFailureBackoffMinutes());
    int exponent = Math.max(0, Math.min(retryCount - 1, 5));
    return (long) baseMinutes * (1L << exponent);
  }

  private String firstPrimaryKey(String primaryKeys) {
    if (primaryKeys == null || primaryKeys.isBlank()) {
      return "id";
    }
    return List.of(primaryKeys.split(",")).stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse("id");
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (value instanceof OffsetDateTime offsetDateTime) {
      return offsetDateTime.toLocalDateTime();
    }
    if (value instanceof Instant instant) {
      return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
    return null;
  }

  private record RowCursor(LocalDateTime updatedAt, String primaryKey) {
    static RowCursor empty() {
      return new RowCursor(null, "");
    }
  }

  private record ShardRepairCursor(String shardKey, String rowCursor) {
  }

  private static String resolveWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
    } catch (Exception ignored) {
      return "gitlab-table-worker-" + UUID.randomUUID();
    }
  }
}
