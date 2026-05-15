package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import java.time.LocalDateTime;
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

  public GitlabMirrorSyncService(
      GitlabConfigService configService,
      GitlabExternalDbService externalDbService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabMirrorTableStorageService mirrorTableStorageService,
      GitlabMirrorRecordMapper mirrorRecordMapper,
      GitlabSystemHookPreciseSyncPlanner systemHookPreciseSyncPlanner,
      FactBuildTaskService factBuildTaskService) {
    this.configService = configService;
    this.externalDbService = externalDbService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.mirrorTableStorageService = mirrorTableStorageService;
    this.mirrorRecordMapper = mirrorRecordMapper;
    this.systemHookPreciseSyncPlanner = systemHookPreciseSyncPlanner;
    this.factBuildTaskService = factBuildTaskService;
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

  public SubmissionResult startFullSync() {
    return startFullSync(null);
  }

  public SubmissionResult startFullSync(Long configId) {
    resolveConfig(configId);
    return SubmissionResult.disabled(SyncType.FULL);
  }

  public SubmissionResult startIncrementalSync(SyncTriggerType triggerType, String message) {
    return startIncrementalSync(null, triggerType, message);
  }

  public SubmissionResult startIncrementalSync(Long configId, SyncTriggerType triggerType, String message) {
    resolveConfig(configId);
    return SubmissionResult.disabled(SyncType.INCREMENTAL);
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
    resolveConfig(configId);
    List<String> requestedTables = normalizeRequestedTables(sourceTableNames);
    log.info(
        "On-demand table refresh rejected during sync orchestrator cutover, reason={}, targetTables={}",
        reason,
        requestedTables);
    return new OnDemandRefreshResult(
        null,
        requestedTables,
        0,
        List.of(),
        SyncStatus.IDLE,
        "Sync orchestrator cutover is in progress. Table refresh will be re-enabled by the unified run model.");
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

  public record SubmissionResult(
      Long runId,
      SyncType type,
      SyncStatus status,
      SyncSubmissionAction action,
      LocalDateTime submittedAt,
      String message) {

    static SubmissionResult disabled(SyncType type) {
      return new SubmissionResult(
          null,
          type,
          SyncStatus.IDLE,
          SyncSubmissionAction.DEDUPED,
          LocalDateTime.now(),
          "Sync orchestrator cutover is in progress. Submission will be re-enabled by the unified run model.");
    }
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
