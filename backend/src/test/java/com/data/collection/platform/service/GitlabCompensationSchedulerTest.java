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
import java.time.LocalDateTime;
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
