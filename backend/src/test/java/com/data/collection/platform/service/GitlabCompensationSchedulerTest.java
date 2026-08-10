package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncTriggerType;
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
  void shouldSubmitIncrementalSyncForDueEnabledSources() {
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
        .submitIncrementalSync(
            eq(due),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(notDue),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(disabled),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_recent_incremental_submission_consumes_current_interval_boundary() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-05T12:01:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig config = config(13L, true, true, LocalDateTime.of(2026, 8, 5, 11, 40));
    config.setCompensationIntervalMinutes(10);
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.incrementalScheduleState(config))
        .thenReturn(
            new SyncRunSubmissionService.IncrementalScheduleState(
                LocalDateTime.of(2026, 8, 5, 12, 0), true));

    scheduler.run();

    verify(submissionService, never())
        .submitIncrementalSync(
            eq(config),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_running_incremental_crossing_real_interval_requests_one_tail_rerun() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig config = config(14L, true, true, LocalDateTime.of(2026, 8, 5, 11, 30));
    config.setCompensationIntervalMinutes(10);
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.incrementalScheduleState(config))
        .thenReturn(
            new SyncRunSubmissionService.IncrementalScheduleState(
                LocalDateTime.of(2026, 8, 5, 11, 50), true));

    scheduler.run();

    verify(submissionService)
        .submitIncrementalSync(
            config, SyncTriggerType.SCHEDULE, "Scheduled incremental sync");
  }

  @Test
  void shouldSkipIncompleteSourceBeforeSubmittingIncrementalSync() {
    GitlabSyncConfig incomplete = config(4L, true, true, null);
    when(configService.listConfigs()).thenReturn(List.of(incomplete));
    when(configService.isReadyForScheduledSync(incomplete)).thenReturn(false);
    when(configService.sourceReadinessIssue(incomplete)).thenReturn("source connection settings are incomplete");

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(incomplete),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void shouldQueueDueIncrementalBehindActiveFullCompensation() {
    GitlabSyncConfig due = config(9L, true, true, LocalDateTime.now().minusMinutes(20));
    due.setCompensationIntervalMinutes(10);
    when(configService.listConfigs()).thenReturn(List.of(due));
    when(configService.isReadyForScheduledSync(due)).thenReturn(true);
    when(submissionService.hasActiveFullCompensationRun(due)).thenReturn(true);

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(submissionService)
        .submitIncrementalSync(
            eq(due),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void shouldSubmitDailyIncrementalSyncOnlyAtConfiguredMinute() {
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
        .submitIncrementalSync(
            eq(due),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(notDue),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_pending_tail_rerun_bypasses_daily_schedule_after_abnormal_termination() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-02T03:31:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig config =
        config(12L, true, true, LocalDateTime.of(2026, 6, 2, 3, 30));
    config.setCompensationScheduleMode("DAILY_TIME");
    config.setCompensationTime("03:30");
    config.setIncrementalRerunRequestedAt(LocalDateTime.of(2026, 6, 2, 3, 30, 30));
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.incrementalScheduleState(config))
        .thenReturn(new SyncRunSubmissionService.IncrementalScheduleState(null, false));

    scheduler.run();

    verify(submissionService)
        .submitIncrementalSync(
            config, SyncTriggerType.SCHEDULE, "Scheduled incremental sync");
  }

  @Test
  void test_pending_tail_rerun_is_not_resubmitted_while_incremental_is_active() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-05T12:01:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig config = config(15L, true, true, LocalDateTime.of(2026, 8, 5, 11, 40));
    config.setCompensationIntervalMinutes(10);
    config.setIncrementalRerunRequestedAt(LocalDateTime.of(2026, 8, 5, 12, 0, 30));
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.incrementalScheduleState(config))
        .thenReturn(
            new SyncRunSubmissionService.IncrementalScheduleState(
                LocalDateTime.of(2026, 8, 5, 12, 0), true));

    scheduler.run();

    verify(submissionService, never())
        .submitIncrementalSync(
            eq(config),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_daily_schedule_does_not_submit_twice_in_same_minute() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-02T03:30:30Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    GitlabSyncConfig config = config(16L, true, true, LocalDateTime.of(2026, 6, 1, 3, 30));
    config.setCompensationScheduleMode("DAILY_TIME");
    config.setCompensationTime("03:30");
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.incrementalScheduleState(config))
        .thenReturn(
            new SyncRunSubmissionService.IncrementalScheduleState(
                LocalDateTime.of(2026, 6, 2, 3, 30), true));

    scheduler.run();

    verify(submissionService, never())
        .submitIncrementalSync(
            eq(config),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
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
        .submitIncrementalSync(
            eq(insideWindow),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(outsideWindow),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_delete_reconciliation_is_submitted_only_when_enabled_and_due() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    properties.setDeleteReconciliationEnabled(true);
    properties.setDeleteReconciliationIntervalMinutes(45);
    GitlabSyncConfig config = config(10L, true, true, LocalDateTime.now(clock));
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.hasDueDeleteReconciliation(
            config, LocalDateTime.now(clock), 45))
        .thenReturn(true);

    scheduler.run();

    verify(submissionService)
        .submitDeleteReconciliation(config, "Scheduled physical delete reconciliation");
    verify(submissionService, never())
        .submitIncrementalSync(
            eq(config),
            eq(SyncTriggerType.SCHEDULE),
            eq("Scheduled incremental sync"));
  }

  @Test
  void test_delete_reconciliation_is_not_submitted_when_no_table_is_due() {
    Clock clock = Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneId.of("UTC"));
    scheduler =
        new GitlabCompensationScheduler(
            properties, syncService, configService, submissionService, clock);
    properties.setDeleteReconciliationEnabled(true);
    GitlabSyncConfig config = config(11L, true, true, LocalDateTime.now(clock));
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isReadyForScheduledSync(config)).thenReturn(true);
    when(submissionService.hasDueDeleteReconciliation(
            config, LocalDateTime.now(clock), 60))
        .thenReturn(false);

    scheduler.run();

    verify(submissionService, never())
        .submitDeleteReconciliation(
            eq(config), eq("Scheduled physical delete reconciliation"));
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
