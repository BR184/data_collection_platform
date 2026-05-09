package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.GitlabTableProbe;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.GitlabTableSyncTaskType;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabTableSyncWorkerService {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);
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

  public GitlabTableSyncWorkerService(
      GitlabTableSyncTaskMapper taskMapper,
      GitlabTableSyncStateMapper stateMapper,
      GitlabSyncJobMapper jobMapper,
      GitlabConfigService configService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService storageService,
      GitlabMirrorProperties properties,
      FactBuildTaskService factBuildTaskService) {
    this.taskMapper = taskMapper;
    this.stateMapper = stateMapper;
    this.jobMapper = jobMapper;
    this.configService = configService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.storageService = storageService;
    this.properties = properties;
    this.factBuildTaskService = factBuildTaskService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.table-worker-delay-ms:5000}")
  public void runOnce() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    recoverTimedOutTasks();
    GitlabTableSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.PENDING)
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
      } else {
        executeIncrementalLikeTask(task, state);
      }
      finishJobIfComplete(task.getJobId());
    } catch (Exception e) {
      markFailure(task, state, e);
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
    boolean drifted = isDrifted(sourceProbe, mirrorProbe, state);
    markVerificationSuccess(task, state, sourceProbe, mirrorProbe, drifted);
    if (drifted && state.getRowStrategy() == GitlabTableRowStrategy.INCREMENTAL && state.isSyncEnabled()) {
      taskMapper.insert(createRepairTask(task, state));
    }
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
    task.setRetryCount(0);
    task.setMaxRetryCount(verificationTask.getMaxRetryCount());
    task.setRowsScanned(0L);
    task.setRowsApplied(0L);
    task.setCreatedAt(now);
    task.setUpdatedAt(now);
    return task;
  }

  private void markFailure(GitlabTableSyncTask task, GitlabTableSyncState state, Exception e) {
    LocalDateTime now = LocalDateTime.now();
    mirrorSchemaService.markTableError(task.getConfigId(), task.getSourceTable());
    task.setStatus(SyncStatus.FAILED);
    task.setLastError(e.getMessage());
    task.setRetryCount(task.getRetryCount() == null ? 1 : task.getRetryCount() + 1);
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

    GitlabSyncJob job = jobMapper.selectById(task.getJobId());
    if (job != null) {
      job.setStatus(SyncStatus.FAILED);
      job.setErrorMessage(e.getMessage());
      job.setFinishedAt(now);
      job.setUpdatedAt(now);
      jobMapper.updateById(job);
    }
  }

  private void markTimeoutAndRetry(GitlabTableSyncTask task, LocalDateTime now) {
    task.setStatus(SyncStatus.TIMEOUT);
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

    int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
    int maxRetryCount = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();
    if (retryCount < maxRetryCount) {
      GitlabTableSyncTask retryTask = copyForRetry(task, retryCount + 1, now);
      taskMapper.insert(retryTask);
      return;
    }

    GitlabSyncJob job = jobMapper.selectById(task.getJobId());
    if (job != null) {
      job.setStatus(SyncStatus.TIMEOUT);
      job.setErrorMessage(task.getLastError());
      job.setFinishedAt(now);
      job.setUpdatedAt(now);
      jobMapper.updateById(job);
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
    if (job == null || job.getStatus() == SyncStatus.FAILED) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    job.setStatus(SyncStatus.SUCCESS);
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
        .eq(GitlabTableSyncTask::getTaskType, GitlabTableSyncTaskType.FULL_REPAIR));
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

  private static String resolveWorkerId() {
    try {
      return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
    } catch (Exception ignored) {
      return "gitlab-table-worker-" + UUID.randomUUID();
    }
  }
}
