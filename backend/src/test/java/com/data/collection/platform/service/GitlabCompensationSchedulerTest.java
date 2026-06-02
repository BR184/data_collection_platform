package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabCompensationSchedulerTest {
  private GitlabMirrorProperties properties;
  private GitlabMirrorSyncService syncService;
  private GitlabConfigService configService;
  private SyncRunSubmissionService submissionService;
  private GitlabCompensationScheduler scheduler;

  @BeforeEach
  void setUp() {
    properties = new GitlabMirrorProperties();
    properties.setSchedulerEnabled(true);
    syncService = mock(GitlabMirrorSyncService.class);
    configService = mock(GitlabConfigService.class);
    submissionService = mock(SyncRunSubmissionService.class);
    scheduler = new GitlabCompensationScheduler(properties, syncService, configService, submissionService);
  }

  @Test
  void shouldSubmitCompensationRunsForDueEnabledSources() {
    GitlabSyncConfig due = config(1L, true, true, LocalDateTime.now().minusMinutes(20));
    due.setCompensationIntervalMinutes(10);
    GitlabSyncConfig notDue = config(2L, true, true, LocalDateTime.now().minusMinutes(2));
    notDue.setCompensationIntervalMinutes(10);
    GitlabSyncConfig disabled = config(3L, false, true, LocalDateTime.now().minusMinutes(20));
    when(configService.listConfigs()).thenReturn(List.of(due, notDue, disabled));
    when(configService.isReadyForScheduledSync(due)).thenReturn(true);
    when(configService.isReadyForScheduledSync(notDue)).thenReturn(true);
    when(configService.isReadyForScheduledSync(disabled)).thenReturn(false);
    when(configService.sourceReadinessIssue(disabled)).thenReturn("source is disabled");

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(submissionService)
        .submitRun(
            eq(due),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
    verify(submissionService, never())
        .submitRun(
            eq(notDue),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
    verify(submissionService, never())
        .submitRun(
            eq(disabled),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
  }

  @Test
  void shouldSkipIncompleteSourceBeforeSubmittingCompensationRun() {
    GitlabSyncConfig incomplete = config(4L, true, true, null);
    when(configService.listConfigs()).thenReturn(List.of(incomplete));
    when(configService.isReadyForScheduledSync(incomplete)).thenReturn(false);
    when(configService.sourceReadinessIssue(incomplete)).thenReturn("source connection settings are incomplete");

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(submissionService, never())
        .submitRun(
            eq(incomplete),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
  }

  @Test
  void shouldSkipScheduledCompensationWhenFullCompensationIsActive() {
    GitlabSyncConfig due = config(9L, true, true, LocalDateTime.now().minusMinutes(20));
    due.setCompensationIntervalMinutes(10);
    when(configService.listConfigs()).thenReturn(List.of(due));
    when(configService.isReadyForScheduledSync(due)).thenReturn(true);
    when(submissionService.hasActiveFullCompensationRun(due)).thenReturn(true);

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(submissionService)
        .hasActiveFullCompensationRun(eq(due));
    verify(submissionService, never())
        .submitRun(
            eq(due),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
  }

  @Test
  void shouldSubmitDailyTimeCompensationOnlyAtConfiguredMinute() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-02T03:30:00Z"), ZoneId.of("UTC"));
    scheduler = new GitlabCompensationScheduler(properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig due = config(5L, true, true, LocalDateTime.of(2026, 6, 1, 3, 30));
    due.setCompensationScheduleMode("DAILY_TIME");
    due.setCompensationTime("03:30");
    GitlabSyncConfig notDue = config(6L, true, true, LocalDateTime.of(2026, 6, 1, 4, 0));
    notDue.setCompensationScheduleMode("DAILY_TIME");
    notDue.setCompensationTime("04:00");
    when(configService.listConfigs()).thenReturn(List.of(due, notDue));
    when(configService.isReadyForScheduledSync(due)).thenReturn(true);
    when(configService.isReadyForScheduledSync(notDue)).thenReturn(true);

    scheduler.run();

    verify(submissionService)
        .submitRun(
            eq(due),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
    verify(submissionService, never())
        .submitRun(
            eq(notDue),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
  }

  @Test
  void shouldApplyIntervalOnlyInsideConfiguredCompensationWindow() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-02T10:00:00Z"), ZoneId.of("UTC"));
    scheduler = new GitlabCompensationScheduler(properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig insideWindow = config(7L, true, true, LocalDateTime.of(2026, 6, 2, 9, 40));
    insideWindow.setCompensationScheduleMode("WINDOWED_INTERVAL");
    insideWindow.setCompensationIntervalMinutes(10);
    insideWindow.setCompensationWindowStart("09:00");
    insideWindow.setCompensationWindowEnd("11:00");
    GitlabSyncConfig outsideWindow = config(8L, true, true, LocalDateTime.of(2026, 6, 2, 9, 40));
    outsideWindow.setCompensationScheduleMode("WINDOWED_INTERVAL");
    outsideWindow.setCompensationIntervalMinutes(10);
    outsideWindow.setCompensationWindowStart("11:00");
    outsideWindow.setCompensationWindowEnd("12:00");
    when(configService.listConfigs()).thenReturn(List.of(insideWindow, outsideWindow));
    when(configService.isReadyForScheduledSync(insideWindow)).thenReturn(true);
    when(configService.isReadyForScheduledSync(outsideWindow)).thenReturn(true);

    scheduler.run();

    verify(submissionService)
        .submitRun(
            eq(insideWindow),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
    verify(submissionService, never())
        .submitRun(
            eq(outsideWindow),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled compensation scan"),
            eq(List.of()),
            eq(null));
  }

  private GitlabSyncConfig config(Long id, boolean sourceEnabled, boolean autoSyncEnabled, LocalDateTime lastSyncAt) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(id);
    config.setSourceInstance("source_" + id);
    config.setEnabled(sourceEnabled);
    config.setSourceEnabled(sourceEnabled);
    config.setAutoSyncEnabled(autoSyncEnabled);
    config.setLastIncrementalSyncAt(lastSyncAt);
    return config;
  }
}
