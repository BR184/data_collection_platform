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
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabDailyVerificationSchedulerTest {
  @Test
  void shouldSubmitDailyVerificationRunsForSourcesDueAtConfiguredTime() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    GitlabConfigService configService = mock(GitlabConfigService.class);
    SyncRunSubmissionService submissionService = mock(SyncRunSubmissionService.class);
    GitlabSyncConfig enabled = config(1L, true, true);
    enabled.setFullCompensationEnabled(true);
    enabled.setFullCompensationTime("02:00");
    GitlabSyncConfig disabled = config(2L, false, true);
    disabled.setFullCompensationEnabled(true);
    disabled.setFullCompensationTime("02:00");
    GitlabSyncConfig notDue = config(3L, true, true);
    notDue.setFullCompensationEnabled(true);
    notDue.setFullCompensationTime("03:00");
    GitlabSyncConfig scheduleDisabled = config(4L, true, true);
    scheduleDisabled.setFullCompensationEnabled(false);
    scheduleDisabled.setFullCompensationTime("02:00");
    when(configService.listConfigs()).thenReturn(List.of(enabled, disabled, notDue, scheduleDisabled));
    when(configService.isReadyForScheduledSync(enabled)).thenReturn(true);
    when(configService.isReadyForScheduledSync(disabled)).thenReturn(false);
    when(configService.isReadyForScheduledSync(notDue)).thenReturn(true);
    when(configService.isReadyForScheduledSync(scheduleDisabled)).thenReturn(true);
    when(configService.sourceReadinessIssue(disabled)).thenReturn("source is disabled");

    Clock clock = Clock.fixed(Instant.parse("2026-05-22T02:00:00+08:00"), ZoneId.of("Asia/Shanghai"));
    new GitlabDailyVerificationScheduler(properties, configService, submissionService, clock).run();

    verify(submissionService)
        .submitFullCompensationSync(
            eq(enabled),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily full compensation scan"));
    verify(submissionService, never())
        .submitFullCompensationSync(
            eq(disabled),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily full compensation scan"));
    verify(submissionService, never())
        .submitFullCompensationSync(
            eq(notDue),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily full compensation scan"));
    verify(submissionService, never())
        .submitFullCompensationSync(
            eq(scheduleDisabled),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily full compensation scan"));
  }

  private GitlabSyncConfig config(Long id, boolean sourceEnabled, boolean autoSyncEnabled) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(id);
    config.setEnabled(sourceEnabled);
    config.setSourceEnabled(sourceEnabled);
    config.setAutoSyncEnabled(autoSyncEnabled);
    return config;
  }
}
