package com.data.collection.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabTableSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabTableSyncState;
import com.data.collection.platform.entity.GitlabTableSyncStateDiagnostics;
import com.data.collection.platform.entity.GitlabTableSyncTask;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.mapper.GitlabTableSyncStateMapper;
import com.data.collection.platform.mapper.GitlabTableSyncTaskMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GitlabTableSyncDiagnosticsService {
  private final GitlabConfigService configService;
  private final GitlabTableSyncStateMapper stateMapper;
  private final GitlabTableSyncTaskMapper taskMapper;

  public GitlabTableSyncDiagnosticsService(
      GitlabConfigService configService,
      GitlabTableSyncStateMapper stateMapper,
      GitlabTableSyncTaskMapper taskMapper) {
    this.configService = configService;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
  }

  public GitlabTableSyncDiagnosticsResponse diagnose(Long configId) {
    GitlabSyncConfig config = configService.getConfigById(configId);
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<GitlabTableSyncState> states = stateMapper.selectList(new LambdaQueryWrapper<GitlabTableSyncState>()
        .eq(GitlabTableSyncState::getConfigId, config.getId())
        .eq(GitlabTableSyncState::getSourceInstance, sourceInstance)
        .orderByAsc(GitlabTableSyncState::getSourceTable));
    Map<String, GitlabTableSyncTask> latestTasks = latestTasksBySourceTable(config.getId(), sourceInstance);
    List<GitlabTableSyncStateDiagnostics> tables = states.stream()
        .map(state -> toTableDiagnostics(state, latestTasks.get(state.getSourceTable())))
        .toList();
    return new GitlabTableSyncDiagnosticsResponse(
        config.getId(),
        sourceInstance,
        LocalDateTime.now(),
        tables.size(),
        Math.toIntExact(tables.stream().filter(GitlabTableSyncStateDiagnostics::dirty).count()),
        countTasks(config.getId(), sourceInstance, SyncStatus.PENDING),
        countTasks(config.getId(), sourceInstance, SyncStatus.RUNNING),
        countTasks(config.getId(), sourceInstance, SyncStatus.RETRYING),
        countTasks(config.getId(), sourceInstance, SyncStatus.FAILED),
        countTasks(config.getId(), sourceInstance, SyncStatus.TIMEOUT),
        tables);
  }

  private Map<String, GitlabTableSyncTask> latestTasksBySourceTable(Long configId, String sourceInstance) {
    List<GitlabTableSyncTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getConfigId, configId)
        .eq(GitlabTableSyncTask::getSourceInstance, sourceInstance)
        .orderByDesc(GitlabTableSyncTask::getCreatedAt));
    Map<String, GitlabTableSyncTask> latestByTable = new LinkedHashMap<>();
    for (GitlabTableSyncTask task : tasks) {
      latestByTable.putIfAbsent(task.getSourceTable(), task);
    }
    return latestByTable;
  }

  private long countTasks(Long configId, String sourceInstance, SyncStatus status) {
    Long count = taskMapper.selectCount(new LambdaQueryWrapper<GitlabTableSyncTask>()
        .eq(GitlabTableSyncTask::getConfigId, configId)
        .eq(GitlabTableSyncTask::getSourceInstance, sourceInstance)
        .eq(GitlabTableSyncTask::getStatus, status));
    return count == null ? 0L : count;
  }

  private GitlabTableSyncStateDiagnostics toTableDiagnostics(
      GitlabTableSyncState state,
      GitlabTableSyncTask latestTask) {
    return new GitlabTableSyncStateDiagnostics(
        state.getSourceTable(),
        state.getMirrorTable(),
        state.getPrimaryKeyColumns(),
        state.getUpdatedAtColumn(),
        state.getRowStrategy(),
        state.isSyncEnabled(),
        state.isDirtyFlag(),
        state.getLastSuccessAt(),
        state.getLastFullVerifiedAt(),
        state.getLastWatermarkAt(),
        state.getLastCursorPk(),
        state.getSourceRowCount(),
        state.getMirrorRowCount(),
        state.getSchemaFingerprint(),
        state.getLastError(),
        state.getRetryCount(),
        latestTask == null ? null : latestTask.getTaskType(),
        latestTask == null ? null : latestTask.getStatus(),
        latestTask == null ? null : latestTask.getRunAfter(),
        latestTask == null ? null : latestTask.getHeartbeatAt(),
        latestTask == null ? null : latestTask.getLeaseUntil(),
        latestTask == null ? null : latestTask.getRowsScanned(),
        latestTask == null ? null : latestTask.getRowsApplied(),
        latestTask == null ? null : latestTask.getLastError());
  }
}
