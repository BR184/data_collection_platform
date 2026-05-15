package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabCompensationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabMirrorSyncService syncService;

  public GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabMirrorSyncService syncService) {
    this.properties = properties;
    this.syncService = syncService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.scheduler-delay-ms:60000}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    syncService.recoverTimedOutTasks();
    log.debug("Compensation scheduling is disabled until the unified run orchestrator is wired.");
  }
}
