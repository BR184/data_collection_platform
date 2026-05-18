package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabCompensationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabMirrorSyncService syncService;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;

  public GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabMirrorSyncService syncService,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this.properties = properties;
    this.syncService = syncService;
    this.configService = configService;
    this.submissionService = submissionService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.scheduler-delay-ms:60000}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    syncService.recoverTimedOutTasks();
    for (GitlabSyncConfig config : configService.listConfigs()) {
      if (!isDue(config, LocalDateTime.now())) {
        continue;
      }
      submissionService.submitRun(
          config,
          SyncType.COMPENSATION,
          SyncRunType.COMPENSATION_SCAN,
          SyncTriggerType.SCHEDULE,
          "Scheduled compensation scan",
          List.of(),
          null);
    }
  }

  private boolean isDue(GitlabSyncConfig config, LocalDateTime now) {
    if (config == null || config.getId() == null) {
      return false;
    }
    if (!configService.isReadyForScheduledSync(config)) {
      log.info(
          "Skipped scheduled compensation scan for sourceInstance={}, reason={}",
          config.getSourceInstance(),
          configService.sourceReadinessIssue(config));
      return false;
    }
    LocalDateTime lastSyncAt = config.getLastIncrementalSyncAt();
    if (lastSyncAt == null) {
      return true;
    }
    int intervalMinutes = config.getCompensationIntervalMinutes() == null ? 10 : config.getCompensationIntervalMinutes();
    return Duration.between(lastSyncAt, now).toMinutes() >= Math.max(1, intervalMinutes);
  }
}
