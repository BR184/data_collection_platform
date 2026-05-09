package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabTableRowStrategy;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.mapper.GitlabSyncJobMapper;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabTableSyncWorkerService {
  private static final LocalDateTime INITIAL_WATERMARK = LocalDateTime.of(1970, 1, 1, 0, 0);

  private final GitlabTableSyncTaskMapper taskMapper;
  private final GitlabTableSyncStateMapper stateMapper;
  private final GitlabSyncJobMapper jobMapper;
  private final GitlabConfigService configService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final GitlabMirrorTableStorageService storageService;
  private final GitlabMirrorProperties properties;

  public GitlabTableSyncWorkerService(
      GitlabTableSyncTaskMapper taskMapper,
      GitlabTableSyncStateMapper stateMapper,
      GitlabSyncJobMapper jobMapper,
      GitlabConfigService configService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService storageService,
      GitlabMirrorProperties properties) {
    this.taskMapper = taskMapper;
    this.stateMapper = stateMapper;
    this.jobMapper = jobMapper;
    this.configService = configService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.storageService = storageService;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.table-worker-delay-ms:5000}")
  public void runOnce() {
    GitlabTableSyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getStatus, SyncStatus.PENDING)
        .orderByAsc(GitlabTableSyncTask::getCreatedAt)
        .last("limit 1"));
    if (task == null) {
      return;
    }
    executeTask(task);
  }

  void executeTask(GitlabTableSyncTask task) {
    LocalDateTime now = LocalDateTime.now();
    markTaskRunning(task, now);
    GitlabTableSyncState state = findState(task);
    try {
      if (state == null || state.getRowStrategy() != GitlabTableRowStrategy.INCREMENTAL || !state.isSyncEnabled()) {
        throw new IllegalStateException("Table task is not executable by incremental worker");
      }
      GitlabSyncConfig config = configService.getConfigById(task.getConfigId());
      TableWhitelistOption option = new TableWhitelistOption(
          state.getSourceTable(),
          state.getSourceTable(),
          state.getPrimaryKeyColumns(),
          state.getUpdatedAtColumn(),
          true);
      GitlabMirrorSchemaService.PreparedMirrorTable preparedMirrorTable =
          mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
      mirrorSchemaService.markTableSyncing(config.getId(), state.getSourceTable());

      LocalDateTime since = resolveScanStart(task);
      List<Map<String, Object>> rows = externalDbService.incrementalScan(config, option, since);
      MirrorBatchWriteResult writeResult =
          storageService.upsertBatch(preparedMirrorTable.mirrorSchema(), rows, task.getId());
      LocalDateTime maxWatermark = maxUpdatedAt(rows, state.getUpdatedAtColumn());
      markSuccess(task, state, rows.size(), writeResult.appliedRows(), maxWatermark);
      mirrorSchemaService.markTableIdle(config.getId(), state.getSourceTable(), LocalDateTime.now());
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

  private void markTaskRunning(GitlabTableSyncTask task, LocalDateTime now) {
    task.setStatus(SyncStatus.RUNNING);
    task.setStartedAt(now);
    task.setHeartbeatAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    GitlabSyncJob job = jobMapper.selectById(task.getJobId());
    if (job != null && job.getStatus() == SyncStatus.PENDING) {
      job.setStatus(SyncStatus.RUNNING);
      job.setStartedAt(now);
      job.setHeartbeatAt(now);
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
      LocalDateTime maxWatermark) {
    LocalDateTime now = LocalDateTime.now();
    task.setStatus(SyncStatus.SUCCESS);
    task.setRowsScanned((long) scannedRows);
    task.setRowsApplied((long) appliedRows);
    task.setFinishedAt(now);
    task.setUpdatedAt(now);
    taskMapper.updateById(task);

    state.setDirtyFlag(false);
    state.setLastSuccessAt(now);
    if (maxWatermark != null) {
      state.setLastWatermarkAt(maxWatermark);
    }
    state.setLastError("");
    state.setRetryCount(0);
    state.setUpdatedAt(now);
    stateMapper.updateById(state);
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
  }

  private LocalDateTime maxUpdatedAt(List<Map<String, Object>> rows, String updatedAtColumn) {
    if (updatedAtColumn == null || updatedAtColumn.isBlank()) {
      return null;
    }
    LocalDateTime max = null;
    for (Map<String, Object> row : rows) {
      LocalDateTime value = toLocalDateTime(row.get(updatedAtColumn));
      if (value != null && (max == null || value.isAfter(max))) {
        max = value;
      }
    }
    return max;
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
}
