package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabMirrorTableRegistry;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.WorkspaceRefreshRequest;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncRunAuthoritativeScopeWorkerService;
import com.data.collection.platform.service.sync.SyncRunDeadlineGuard;
import com.data.collection.platform.service.sync.SyncRunLeaseService;
import com.data.collection.platform.service.sync.SyncRunPayload;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.service.sync.SyncRunTableWorkerService;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private final SyncRunDeadlineGuard syncRunDeadlineGuard;
  private final SyncRunTableWorkerService syncRunTableWorkerService;
  private final SyncRunAuthoritativeScopeWorkerService authoritativeScopeWorkerService;
  private final GitlabMirrorTableRegistryMapper registryMapper;
  private final SyncRunTableStateMapper tableStateMapper;

  public GitlabMirrorSyncService(
      GitlabConfigService configService,
      SourceConnectionTester sourceConnectionTester,
      GitlabMirrorSchemaService mirrorSchemaService,
      SyncRunSubmissionService syncRunSubmissionService,
      SyncRunLeaseService syncRunLeaseService,
      SyncRunDeadlineGuard syncRunDeadlineGuard,
      SyncRunTableWorkerService syncRunTableWorkerService,
      SyncRunAuthoritativeScopeWorkerService authoritativeScopeWorkerService,
      GitlabMirrorTableRegistryMapper registryMapper,
      SyncRunTableStateMapper tableStateMapper) {
    this.configService = configService;
    this.sourceConnectionTester = sourceConnectionTester;
    this.mirrorSchemaService = mirrorSchemaService;
    this.syncRunSubmissionService = syncRunSubmissionService;
    this.syncRunLeaseService = syncRunLeaseService;
    this.syncRunDeadlineGuard = syncRunDeadlineGuard;
    this.syncRunTableWorkerService = syncRunTableWorkerService;
    this.authoritativeScopeWorkerService = authoritativeScopeWorkerService;
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
    syncRunDeadlineGuard.requestCancellationForExpiredRuns();
    syncRunTableWorkerService.recoverTimedOutTasks();
    authoritativeScopeWorkerService.recoverExpiredLeases();
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
    return refreshTablesOnDemandDetailed(configId, sourceTableNames, reason, reason, "ON_DEMAND_REFRESH");
  }

  public OnDemandRefreshResult refreshTablesOnDemandDetailed(
      List<String> sourceTableNames,
      String reason,
      String sourcePageKey,
      String triggerSurface) {
    return refreshTablesOnDemandDetailed(null, sourceTableNames, reason, sourcePageKey, triggerSurface);
  }

  public OnDemandRefreshResult refreshTablesOnDemandDetailed(
      Long configId,
      List<String> sourceTableNames,
      String reason,
      String sourcePageKey,
      String triggerSurface) {
    GitlabSyncConfig config = resolveConfig(configId);
    List<String> requestedTables = normalizeRequestedTables(sourceTableNames);
    validateManualTableRefreshBoundaries(config, requestedTables);
    SyncRunSubmissionResult submission =
        syncRunSubmissionService.submitTableRefresh(
            config,
            requestedTables,
            reason,
            structuredRefreshContext(sourcePageKey, triggerSurface));
    return new OnDemandRefreshResult(
        submission.runId(),
        requestedTables,
        requestedTables.isEmpty() ? 0 : requestedTables.size(),
        List.of(),
        submission.status(),
        submission.message());
  }

  /**
   * 提交当前数据源可执行的页面相关源表刷新，并返回无法增量刷新的可选依赖表。
   *
   * <p>页面刷新可以包含补充字段依赖；该类依赖未初始化时不应阻断已经具备基线的核心表刷新。
   * 显式单表刷新继续使用 {@link #refreshTablesOnDemandDetailed(List, String)}，保留严格失败语义。</p>
   */
  public OnDemandRefreshResult refreshAvailableTablesOnDemandDetailed(
      List<String> sourceTableNames,
      String reason,
      WorkspaceRefreshRequest workspaceRefreshRequest,
      String triggerSurface) {
    GitlabSyncConfig config = resolveConfig(null);
    List<String> requestedTables = normalizeRequestedTables(sourceTableNames);
    List<String> availableTables = new java.util.ArrayList<>();
    List<String> unsupportedTables = new java.util.ArrayList<>();
    for (String sourceTable : requestedTables) {
      if (manualTableRefreshFailure(config, sourceTable) == null) {
        availableTables.add(sourceTable);
      } else {
        unsupportedTables.add(sourceTable);
      }
    }
    if (availableTables.isEmpty()) {
      return new OnDemandRefreshResult(
          null,
          List.of(),
          0,
          unsupportedTables,
          SyncStatus.SUCCESS,
          "没有可提交的页面相关源表。");
    }
    SyncRunSubmissionResult submission =
        syncRunSubmissionService.submitTableRefresh(
            config,
            availableTables,
            reason,
            structuredRefreshContext(config, workspaceRefreshRequest, triggerSurface));
    return new OnDemandRefreshResult(
        submission.runId(),
        availableTables,
        availableTables.size(),
        unsupportedTables,
        submission.status(),
        submission.message());
  }

  private Map<String, Object> structuredRefreshContext(String sourcePageKey, String triggerSurface) {
    java.util.LinkedHashMap<String, Object> context = new java.util.LinkedHashMap<>();
    if (!isBlank(sourcePageKey)) {
      context.put("sourcePageKey", sourcePageKey.trim());
    }
    if (!isBlank(triggerSurface)) {
      context.put("triggerSurface", triggerSurface.trim());
    }
    return Map.copyOf(context);
  }

  private Map<String, Object> structuredRefreshContext(
      GitlabSyncConfig config,
      WorkspaceRefreshRequest workspaceRefreshRequest,
      String triggerSurface) {
    java.util.LinkedHashMap<String, Object> context = new java.util.LinkedHashMap<>();
    if (workspaceRefreshRequest != null) {
      List<FactType> factTypes =
          RealtimeWorkspaceDependencyCatalog.resolve(
                  workspaceRefreshRequest.workspaceKey(), config)
              .factTypes();
      context.put(
          "workspaceRefresh",
          SyncRunPayload.WorkspaceRefreshSpec.from(workspaceRefreshRequest, factTypes));
      context.put("sourcePageKey", workspaceRefreshRequest.workspaceKey());
    }
    if (!isBlank(triggerSurface)) {
      context.put("triggerSurface", triggerSurface.trim());
    }
    return Map.copyOf(context);
  }

  private void validateManualTableRefreshBoundaries(GitlabSyncConfig config, List<String> sourceTables) {
    for (String sourceTable : sourceTables) {
      String failure = manualTableRefreshFailure(config, sourceTable);
      if (failure != null) {
        throw new com.data.collection.platform.common.exception.BizException(failure);
      }
    }
  }

  private String manualTableRefreshFailure(GitlabSyncConfig config, String sourceTable) {
    GitlabMirrorTableRegistry registry =
        registryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GitlabMirrorTableRegistry>()
            .eq(GitlabMirrorTableRegistry::getConfigId, config.getId())
            .eq(GitlabMirrorTableRegistry::getSourceTableName, sourceTable)
            .eq(GitlabMirrorTableRegistry::getInitialized, true)
            .last("limit 1"));
    if (registry == null) {
      return "源表未加入镜像白名单：" + sourceTable;
    }
    if (isBlank(registry.getPrimaryKeyColumns())) {
      return "手动刷新表需要已识别的主键列：" + sourceTable;
    }
    if (isBlank(registry.getUpdatedAtColumn())) {
      return "手动刷新表需要 updated_at 列：" + sourceTable;
    }
    SyncRunTableState state =
        tableStateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
            .eq(SyncRunTableState::getConfigId, config.getId())
            .eq(SyncRunTableState::getSourceInstance, GitlabSourceInstanceSupport.sourceInstanceOf(config))
            .eq(SyncRunTableState::getSourceTable, sourceTable)
            .last("limit 1"));
    if (state == null || state.getLastWatermarkAt() == null) {
      return "手动刷新表需要先完成一次全量同步基线：" + sourceTable;
    }
    return null;
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
