package com.data.collection.platform.service.sync;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.GitlabConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunFactPublicationCoordinatorTest {
  private GitlabConfigService configService;
  private SyncRunSubmissionService submissionService;
  private SyncFactPublicationStateService publicationStateService;
  private SyncRunFactPublicationCoordinator coordinator;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    submissionService = mock(SyncRunSubmissionService.class);
    publicationStateService = mock(SyncFactPublicationStateService.class);
    coordinator = new SyncRunFactPublicationCoordinator(
        mock(JdbcTemplate.class), configService, submissionService, publicationStateService);
    when(configService.getConfigById(1L)).thenReturn(config());
  }

  @Test
  void test_blocked_dependency_keeps_targets_pending_without_starting_fact_run() {
    SyncRunCompletionEvent event = event(SyncRunStatus.FAILED);
    when(publicationStateService.recordMirrorCompletion(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(41L),
            org.mockito.ArgumentMatchers.eq(SyncRunStatus.FAILED),
            org.mockito.ArgumentMatchers.eq(false)))
        .thenReturn(false);

    coordinator.onMirrorCompleted(event);

    verify(publicationStateService).releaseFailedFactAssignments("alpha");
    verify(submissionService, never()).submitFactRefresh(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyBoolean(), anyString());
  }

  @Test
  void test_ready_dependency_starts_source_level_fact_consumer_without_parent_run() {
    SyncRunCompletionEvent event = event(SyncRunStatus.SUCCESS);
    when(publicationStateService.recordMirrorCompletion(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(41L),
            org.mockito.ArgumentMatchers.eq(SyncRunStatus.SUCCESS),
            org.mockito.ArgumentMatchers.eq(false)))
        .thenReturn(true);

    coordinator.onMirrorCompleted(event);

    verify(submissionService).submitFactRefresh(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq(false), anyString());
  }

  private SyncRunCompletionEvent event(SyncRunStatus status) {
    return new SyncRunCompletionEvent(
        41L, 1L, "alpha", SyncRunType.INCREMENTAL_SYNC, status, 1L);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    return config;
  }
}
