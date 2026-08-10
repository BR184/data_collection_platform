package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabCompensationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabMirrorSyncService syncService;
  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;
  private final Clock clock;

  @Autowired
  public GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabMirrorSyncService syncService,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this(properties, syncService, configService, submissionService, Clock.systemDefaultZone());
  }

  GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabMirrorSyncService syncService,
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService,
      Clock clock) {
    this.properties = properties;
    this.syncService = syncService;
    this.configService = configService;
    this.submissionService = submissionService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.scheduler-delay-ms:60000}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    syncService.recoverTimedOutTasks();
    LocalDateTime now = LocalDateTime.now(clock);
    for (GitlabSyncConfig config : configService.listConfigs()) {
      if (!isReady(config)) {
        continue;
      }
      if (isDue(config, now)) {
        submissionService.submitIncrementalSync(
            config,
            SyncTriggerType.SCHEDULE,
            "Scheduled incremental sync");
      }
      if (properties.isDeleteReconciliationEnabled()
          && submissionService.hasDueDeleteReconciliation(
              config, now, properties.getDeleteReconciliationIntervalMinutes())) {
        submissionService.submitDeleteReconciliation(
            config, "Scheduled physical delete reconciliation");
      }
    }
  }

  private boolean isReady(GitlabSyncConfig config) {
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
    return true;
  }

  private boolean isDue(GitlabSyncConfig config, LocalDateTime now) {
    SyncRunSubmissionService.IncrementalScheduleState scheduleState =
        submissionService.incrementalScheduleState(config);
    if (scheduleState == null) {
      scheduleState = new SyncRunSubmissionService.IncrementalScheduleState(null, false);
    }
    if (config.getIncrementalRerunRequestedAt() != null) {
      return !scheduleState.activeIncremental();
    }
    LocalDateTime lastTriggerAt =
        latest(config.getLastIncrementalSyncAt(), scheduleState.lastSubmittedAt());
    String mode = config.getCompensationScheduleMode();
    if (GitlabConfigService.COMPENSATION_SCHEDULE_DAILY_TIME.equals(mode)) {
      return isDailyTimeDue(config, now, lastTriggerAt);
    }
    if (GitlabConfigService.COMPENSATION_SCHEDULE_WINDOWED_INTERVAL.equals(mode)
        && !isWithinWindow(config, now.toLocalTime())) {
      return false;
    }
    return isIntervalDue(config, now, lastTriggerAt);
  }

  private LocalDateTime latest(LocalDateTime first, LocalDateTime second) {
    if (first == null) {
      return second;
    }
    if (second == null || first.isAfter(second)) {
      return first;
    }
    return second;
  }

  private boolean isIntervalDue(GitlabSyncConfig config, LocalDateTime now, LocalDateTime lastSyncAt) {
    if (lastSyncAt == null) {
      return true;
    }
    int intervalMinutes =
        config.getCompensationIntervalMinutes() == null
            ? GitlabConfigService.DEFAULT_COMPENSATION_INTERVAL_MINUTES
            : config.getCompensationIntervalMinutes();
    return Duration.between(lastSyncAt, now).toMinutes() >= Math.max(1, intervalMinutes);
  }

  private boolean isDailyTimeDue(GitlabSyncConfig config, LocalDateTime now, LocalDateTime lastSyncAt) {
    LocalTime scheduledTime = parseTime(config.getCompensationTime(), GitlabConfigService.DEFAULT_COMPENSATION_TIME);
    if (!now.toLocalTime().withSecond(0).withNano(0).equals(scheduledTime)) {
      return false;
    }
    LocalDateTime scheduledAt = now.toLocalDate().atTime(scheduledTime);
    return lastSyncAt == null || lastSyncAt.isBefore(scheduledAt);
  }

  private boolean isWithinWindow(GitlabSyncConfig config, LocalTime now) {
    LocalTime start = parseTime(config.getCompensationWindowStart(), null);
    LocalTime end = parseTime(config.getCompensationWindowEnd(), null);
    if (start == null || end == null || start.equals(end)) {
      return true;
    }
    LocalTime current = now.withSecond(0).withNano(0);
    if (start.isBefore(end)) {
      return !current.isBefore(start) && current.isBefore(end);
    }
    return !current.isBefore(start) || current.isBefore(end);
  }

  private LocalTime parseTime(String value, String fallback) {
    String effectiveValue = value == null || value.isBlank() ? fallback : value.trim();
    if (effectiveValue == null || effectiveValue.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(effectiveValue, DateTimeFormatter.ofPattern("HH:mm"));
    } catch (DateTimeParseException error) {
      log.warn("Skipped scheduled compensation scan because configured time is invalid: {}", value);
      return null;
    }
  }
}
