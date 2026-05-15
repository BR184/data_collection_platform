package com.data.collection.platform.service;

import com.data.collection.platform.common.logging.SyncRunLogContext;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabSystemHookAsyncDispatchService {
  private final GitlabConfigService configService;
  private final GitlabMirrorSchemaService mirrorSchemaService;

  public GitlabSystemHookAsyncDispatchService(
      GitlabConfigService configService,
      GitlabMirrorSchemaService mirrorSchemaService) {
    this.configService = configService;
    this.mirrorSchemaService = mirrorSchemaService;
  }

  public void accept(String eventType, Map<String, Object> payload) {
    accept(configService.getConfig(), eventType, payload);
  }

  public void accept(GitlabSyncConfig config, String eventType, Map<String, Object> payload) {
    try (SyncRunLogContext.Scope context = SyncRunLogContext.openConfig(config, "SYSTEM_HOOK_WAKEUP", eventType);
        SyncRunLogContext.Scope action = SyncRunLogContext.action("Run_Submit_Disabled")) {
      log.info(
          "System Hook persisted but sync submission is disabled during orchestrator cutover, eventType={}, objectKind={}",
          eventType,
          payload == null ? null : payload.get("object_kind"));
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
