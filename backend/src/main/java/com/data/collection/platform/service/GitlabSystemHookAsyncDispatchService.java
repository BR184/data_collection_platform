package com.data.collection.platform.service;

import com.data.collection.platform.common.logging.GitlabSyncLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabSystemHookAsyncDispatchService {
  private final GitlabConfigService configService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;

  public GitlabSystemHookAsyncDispatchService(
      GitlabConfigService configService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabWhitelistService whitelistService,
      GitlabTableSyncPlanningService tableSyncPlanningService) {
    this.configService = configService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.whitelistService = whitelistService;
    this.tableSyncPlanningService = tableSyncPlanningService;
  }

  public void accept(String eventType, Map<String, Object> payload) {
    accept(configService.getConfig(), eventType, payload);
  }

  public void accept(GitlabSyncConfig config, String eventType, Map<String, Object> payload) {
    List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
    int dirtyTables = tableSyncPlanningService.markIncrementalTablesDirty(
        config,
        tables,
        "System Hook dirty signal: " + eventType);
    if (tableSyncPlanningService.hasActiveJob(config.getId(), GitlabSyncJobType.COMPENSATION_SCAN)) {
      try (GitlabSyncLogContext.Scope context =
               GitlabSyncLogContext.openConfig(config, "SYSTEM_HOOK_WAKEUP", eventType);
           GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("COMPENSATION_REUSED")) {
        log.info(
            "System Hook wakeup marked source dirty and reused active compensation scan, eventType={}, dirtyTables={}",
            eventType,
            dirtyTables);
      }
      return;
    }
    GitlabTableSyncPlanningService.CompensationPlanResult result =
        tableSyncPlanningService.createCompensationScanPlan(config, tables);
    try (GitlabSyncLogContext.Scope context =
             GitlabSyncLogContext.openConfig(config, "SYSTEM_HOOK_WAKEUP", eventType);
         GitlabSyncLogContext.Scope action = GitlabSyncLogContext.action("COMPENSATION_QUEUED")) {
      log.info(
          "System Hook wakeup marked source dirty and queued compensation scan, eventType={}, dirtyTables={}, jobId={}, discoveredTables={}, plannedTasks={}, verifyOnlyTables={}",
          eventType,
          dirtyTables,
          result.jobId(),
          result.discoveredTables(),
          result.plannedTasks(),
          result.verifyOnlyTables());
    }
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.system-hook-batch-window-seconds:3}000")
  public void scheduledFlush() {
    flushPending();
  }

  public void flushPending() {
    mirrorSchemaService.recoverStaleSyncingStatuses();
  }

  int objectLockCount() {
    return 0;
  }

  public void shutdown() {
    // No background worker remains. Hooks only persist events and wake compensation planning.
  }
}
