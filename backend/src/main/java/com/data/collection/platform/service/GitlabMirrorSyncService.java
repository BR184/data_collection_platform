package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabMirrorSyncService {
  private final GitlabConfigService configService;
  private final GitlabExternalDbService externalDbService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  @SuppressWarnings("unused")
  private final GitlabMirrorTableStorageService mirrorTableStorageService;
  @SuppressWarnings("unused")
  private final GitlabMirrorRecordMapper mirrorRecordMapper;
  @SuppressWarnings("unused")
  private final GitlabSystemHookPreciseSyncPlanner systemHookPreciseSyncPlanner;
  @SuppressWarnings("unused")
  private final FactBuildTaskService factBuildTaskService;
  private final SyncRunSubmissionService syncRunSubmissionService;

  public GitlabMirrorSyncService(
      GitlabConfigService configService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService mirrorTableStorageService,
      GitlabMirrorRecordMapper mirrorRecordMapper,
      GitlabSystemHookPreciseSyncPlanner systemHookPreciseSyncPlanner,
      FactBuildTaskService factBuildTaskService,
      SyncRunSubmissionService syncRunSubmissionService) {
    this.configService = configService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.mirrorTableStorageService = mirrorTableStorageService;
    this.mirrorRecordMapper = mirrorRecordMapper;
    this.systemHookPreciseSyncPlanner = systemHookPreciseSyncPlanner;
    this.factBuildTaskService = factBuildTaskService;
    this.syncRunSubmissionService = syncRunSubmissionService;
  }

  public boolean hasActiveTask(Long configId) {
    return false;
  }

  public boolean hasExecutingTask(Long configId) {
    return false;
  }

  public void recoverTimedOutTasks() {
    mirrorSchemaService.recoverStaleSyncingStatuses();
  }

  public void testConnection() {
    testConnection(null);
  }

  public void testConnection(Long configId) {
    externalDbService.testConnection(resolveConfig(configId));
  }

  public SyncRunSubmissionResult startFullSync() {
    return startFullSync(null);
  }

  public SyncRunSubmissionResult startFullSync(Long configId) {
    GitlabSyncConfig config = resolveConfig(configId);
    return syncRunSubmissionService.submitFullSync(config, "Manual full sync");
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
