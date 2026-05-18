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
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabDailyVerificationSchedulerTest {
  @Test
  void shouldSubmitDailyVerificationRunsForEnabledSources() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    GitlabConfigService configService = mock(GitlabConfigService.class);
    SyncRunSubmissionService submissionService = mock(SyncRunSubmissionService.class);
    GitlabSyncConfig enabled = config(1L, true, true);
    GitlabSyncConfig disabled = config(2L, false, true);
    when(configService.listConfigs()).thenReturn(List.of(enabled, disabled));
    when(configService.isReadyForScheduledSync(enabled)).thenReturn(true);
    when(configService.isReadyForScheduledSync(disabled)).thenReturn(false);
    when(configService.sourceReadinessIssue(disabled)).thenReturn("source is disabled");

    new GitlabDailyVerificationScheduler(properties, configService, submissionService).run();

    verify(submissionService)
        .submitRun(
            eq(enabled),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily verification scan"),
            eq(List.of()),
            eq(null));
    verify(submissionService, never())
        .submitRun(
            eq(disabled),
            eq(SyncType.COMPENSATION),
            eq(SyncRunType.COMPENSATION_SCAN),
            eq(SyncTriggerType.SCHEDULE),
            eq("Daily verification scan"),
            eq(List.of()),
            eq(null));
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
