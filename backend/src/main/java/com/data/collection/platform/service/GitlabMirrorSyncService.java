package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.service.sync.SyncRunLeaseService;
import com.data.collection.platform.service.sync.SyncRunTableWorkerService;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabMirrorSyncService {
  private final GitlabConfigService configService;
  private final SourceConnectionTester sourceConnectionTester;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final SyncRunSubmissionService syncRunSubmissionService;
  private final SyncRunLeaseService syncRunLeaseService;
  private final SyncRunTableWorkerService syncRunTableWorkerService;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final SyncRunTableStateMapper tableStateMapper;

  public GitlabMirrorSyncService(
      GitlabConfigService configService,
      SourceConnectionTester sourceConnectionTester,
      GitlabMirrorSchemaService mirrorSchemaService,
      SyncRunSubmissionService syncRunSubmissionService,
      SyncRunLeaseService syncRunLeaseService,
      SyncRunTableWorkerService syncRunTableWorkerService,
      GitlabMirrorTableRegistryMapper registryMapper,
      SyncRunTableStateMapper tableStateMapper) {
    this.configService = configService;
    this.sourceConnectionTester = sourceConnectionTester;
    this.mirrorSchemaService = mirrorSchemaService;
    this.syncRunSubmissionService = syncRunSubmissionService;
    this.syncRunLeaseService = syncRunLeaseService;
    this.syncRunTableWorkerService = syncRunTableWorkerService;
    this.registryMapper = registryMapper;
    this.tableStateMapper = tableStateMapper;
  }

  public boolean hasActiveTask(Long configId) {
    return false;
  }

  public boolean hasExecutingTask(Long configId) {
    return false;
  }

  public void recoverTimedOutTasks() {
    mirrorSchemaService.recoverStaleSyncingStatuses();
    syncRunLeaseService.recoverTimedOutRuns();
    syncRunTableWorkerService.recoverTimedOutTasks();
  }

  public void testConnection() {
    testConnection(null);
  }

  public void testConnection(Long configId) {
    sourceConnectionTester.testConnection(resolveConfig(configId));
  }

  public SyncRunSubmissionResult startFullSync() {
    return startFullSync(null);
  }

  public SyncRunSubmissionResult startFullSync(Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return syncRunSubmissionService.submitFullSync(config, "手动全量同步");
  }

  public SyncRunSubmissionResult startIncrementalSync(SyncTriggerType triggerType, String message) {
    return startIncrementalSync(null, triggerType, message);
  }

  public SyncRunSubmissionResult startIncrementalSync(Long configId, SyncTriggerType triggerType, String message) {
    GitlabSyncConfig config = resolveConfig(configId);
    return syncRunSubmissionService.submitIncrementalSync(config, triggerType, message);
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
    GitlabSyncConfig config = resolveConfig(configId);
    List<String> requestedTables = normalizeRequestedTables(sourceTableNames);
    validateManualTableRefreshBoundaries(config, requestedTables);
    SyncRunSubmissionResult submission =
        syncRunSubmissionService.submitTableRefresh(config, requestedTables, reason);
    return new OnDemandRefreshResult(
        submission.runId(),
        requestedTables,
        requestedTables.isEmpty() ? 0 : requestedTables.size(),
        List.of(),
        submission.status(),
        submission.message());
  }

  private void validateManualTableRefreshBoundaries(GitlabSyncConfig config, List<String> sourceTables) {
    for (String sourceTable : sourceTables) {
      GitlabMirrorTableRegistry registry =
          registryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GitlabMirrorTableRegistry>()
              .eq(GitlabMirrorTableRegistry::getConfigId, config.getId())
              .eq(GitlabMirrorTableRegistry::getSourceTableName, sourceTable)
              .eq(GitlabMirrorTableRegistry::getInitialized, true)
              .last("limit 1"));
      if (registry == null) {
        throw new com.data.collection.platform.common.exception.BizException(
            "源表未加入镜像白名单：" + sourceTable);
      }
      if (isBlank(registry.getPrimaryKeyColumns())) {
        throw new com.data.collection.platform.common.exception.BizException(
            "手动刷新表需要已识别的主键列：" + sourceTable);
      }
      if (isBlank(registry.getUpdatedAtColumn())) {
        throw new com.data.collection.platform.common.exception.BizException(
            "手动刷新表需要 updated_at 列：" + sourceTable);
      }
      SyncRunTableState state =
          tableStateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
              .eq(SyncRunTableState::getConfigId, config.getId())
              .eq(SyncRunTableState::getSourceInstance, GitlabSourceInstanceSupport.sourceInstanceOf(config))
              .eq(SyncRunTableState::getSourceTable, sourceTable)
              .last("limit 1"));
      if (state == null || state.getLastWatermarkAt() == null) {
        throw new com.data.collection.platform.common.exception.BizException(
            "手动刷新表需要先完成一次全量同步基线：" + sourceTable);
      }
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public boolean requestCancel(Long configId) {
    resolveConfig(configId);
    return false;
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

  private GitlabSyncConfig resolveConfig(Long configId) {
    return configId == null ? configService.getConfig() : configService.getConfigById(configId);
  }

  public record OnDemandRefreshResult(
      Long jobId,
      List<String> sourceTables,
      int plannedTasks,
      List<String> unsupportedTables,
      SyncStatus status,
      String message) {

    public OnDemandRefreshResult {
      sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
      unsupportedTables = unsupportedTables == null ? List.of() : List.copyOf(unsupportedTables);
    }
  }
}
