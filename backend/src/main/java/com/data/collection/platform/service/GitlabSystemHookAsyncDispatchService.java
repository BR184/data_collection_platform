package com.data.collection.platform.service;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
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
  private final GitlabSystemHookPreciseSyncPlanner preciseSyncPlanner;
  private final SyncRunSubmissionService submissionService;

  public GitlabSystemHookAsyncDispatchService(
      GitlabConfigService configService,
      GitlabMirrorSchemaService mirrorSchemaService,
      GitlabSystemHookPreciseSyncPlanner preciseSyncPlanner,
      SyncRunSubmissionService submissionService) {
    this.configService = configService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.preciseSyncPlanner = preciseSyncPlanner;
    this.submissionService = submissionService;
  }

  public void accept(String eventType, Map<String, Object> payload) {
    accept(configService.getConfig(), eventType, payload);
  }

  public void accept(GitlabSyncConfig config, String eventType, Map<String, Object> payload) {
    GitlabSystemHookPreciseSyncPlan plan = preciseSyncPlanner.plan(payload);
    List<String> sourceTables =
        plan.targets().stream()
            .map(GitlabSystemHookPreciseSyncTarget::tableName)
            .distinct()
            .toList();
    if (sourceTables.isEmpty()) {
      try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "SYSTEM_HOOK_WAKEUP", eventType);
          SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit_Skipped")) {
        log.info(
            "System Hook persisted but no precise sync target was planned, eventType={}, objectKind={}",
            eventType,
            payload == null ? null : payload.get("object_kind"));
      }
      return;
    }
    submissionService.submitRun(
        config,
        SyncType.SYSTEM_HOOK,
        SyncRunType.SYSTEM_HOOK,
        SyncTriggerType.SYSTEM_HOOK,
        "System Hook 已唤醒同步：" + eventType + " " + plan.objectKey(),
        sourceTables,
        sourceTables.getFirst(),
        Map.of(
            "objectKey",
            plan.objectKey(),
            "preciseTargets",
            plan.targets().stream()
                .map(target -> Map.of(
                    "tableName", target.tableName(),
                    "lookupColumn", target.lookupColumn(),
                    "lookupValue", String.valueOf(target.lookupValue())))
                .toList()));
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "SYSTEM_HOOK_WAKEUP", eventType);
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit")) {
      log.info(
          "System Hook submitted unified sync run, eventType={}, objectKind={}, sourceTables={}",
          eventType,
          payload == null ? null : payload.get("object_kind"),
          sourceTables);
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
    // No background worker remains during hard cutover.
  }
}
