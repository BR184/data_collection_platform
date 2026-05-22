package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabDailyVerificationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;
  private final Clock clock;

  public GitlabDailyVerificationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this(properties, configService, submissionService, Clock.systemDefaultZone());
  }

  GitlabDailyVerificationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService,
      Clock clock) {
    this.properties = properties;
    this.configService = configService;
    this.submissionService = submissionService;
    this.clock = clock;
  }

  @Scheduled(cron = "${platform.gitlab-mirror.full-compensation-check-cron:0 * * * * *}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    for (GitlabSyncConfig config : configService.listConfigs()) {
      if (!isFullCompensationDue(config)) {
        continue;
      }
      if (!configService.isReadyForScheduledSync(config)) {
        log.info(
            "Skipped daily verification scan for sourceInstance={}, reason={}",
            config == null ? null : config.getSourceInstance(),
            configService.sourceReadinessIssue(config));
        continue;
      }
      submissionService.submitFullCompensationSync(
          config,
          SyncTriggerType.SCHEDULE,
          "Daily full compensation scan");
    }
  }

  private boolean isFullCompensationDue(GitlabSyncConfig config) {
    if (config == null || !Boolean.TRUE.equals(config.getFullCompensationEnabled())) {
      return false;
    }
    try {
      LocalTime scheduledTime = LocalTime.parse(
          config.getFullCompensationTime() == null || config.getFullCompensationTime().isBlank()
              ? GitlabConfigService.DEFAULT_FULL_COMPENSATION_TIME
              : config.getFullCompensationTime(),
          DateTimeFormatter.ofPattern("HH:mm"));
      return LocalTime.now(clock).withSecond(0).withNano(0).equals(scheduledTime);
    } catch (DateTimeParseException ex) {
      log.warn(
          "Skipped daily full compensation scan for sourceInstance={}, reason=invalid full compensation time {}",
          config.getSourceInstance(),
          config.getFullCompensationTime());
      return false;
    }
  }
}
