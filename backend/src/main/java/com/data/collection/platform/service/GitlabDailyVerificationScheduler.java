package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabDailyVerificationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;

  public GitlabDailyVerificationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this.properties = properties;
    this.configService = configService;
    this.submissionService = submissionService;
  }

  @Scheduled(cron = "${platform.gitlab-mirror.daily-verify-cron:0 0 2 * * *}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    for (GitlabSyncConfig config : configService.listConfigs()) {
      if (!configService.isReadyForScheduledSync(config)) {
        log.info(
            "Skipped daily verification scan for sourceInstance={}, reason={}",
            config == null ? null : config.getSourceInstance(),
            configService.sourceReadinessIssue(config));
        continue;
      }
      submissionService.submitRun(
          config,
          SyncType.COMPENSATION,
          SyncRunType.FULL_COMPENSATION_SCAN,
          SyncTriggerType.SCHEDULE,
          "Daily full compensation scan",
          List.of(),
          null);
    }
  }

}
