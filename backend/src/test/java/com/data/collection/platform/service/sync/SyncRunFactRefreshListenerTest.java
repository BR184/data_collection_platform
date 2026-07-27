package com.data.collection.platform.service.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.GitlabConfigService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncRunFactRefreshListenerTest {
  private GitlabConfigService configService;
  private SyncRunSubmissionService submissionService;
  private SyncRunFactRefreshListener listener;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    submissionService = mock(SyncRunSubmissionService.class);
    listener = new SyncRunFactRefreshListener(configService, submissionService);
  }

  @Test
  void shouldSubmitFactRefreshForSuccessfulMirrorRunWithAppliedRows() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);

    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(11L, 1L, "alpha", SyncRunType.FULL_SYNC, SyncRunStatus.SUCCESS, 8L));

    verify(submissionService)
        .submitFactRefresh(config, 11L, true, "镜像同步已完成，刷新事实层");
  }

  @Test
  void shouldSkipFactRefreshWhenCustomWhitelistDoesNotCoverFactSourceTables() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("users", "projects"));
    when(configService.getConfigById(1L)).thenReturn(config);

    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(15L, 1L, "alpha", SyncRunType.FULL_SYNC, SyncRunStatus.SUCCESS, 8L));

    verify(submissionService, never())
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void shouldSubmitIncrementalFactRefreshForPartialSuccess() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);

    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(12L, 1L, "alpha", SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.PARTIAL_SUCCESS, 8L));
    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(13L, 1L, "alpha", SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.SUCCESS, 0L));

    verify(configService, org.mockito.Mockito.times(2)).getConfigById(1L);
    verify(submissionService)
        .submitFactRefresh(config, 12L, false, "镜像同步已完成，刷新事实层");
    verify(submissionService)
        .submitFactRefresh(config, 13L, false, "镜像同步已完成，刷新事实层");
  }

  @Test
  void shouldSkipFailedMirrorRun() {
    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(16L, 1L, "alpha", SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.FAILED, 8L));

    verify(configService, never()).getConfigById(org.mockito.ArgumentMatchers.any());
    verifyNoFactRefreshSubmission();
  }

  @Test
  void shouldSkipFactRefreshRuns() {
    listener.onSyncRunCompleted(
        new SyncRunCompletionEvent(14L, 1L, "alpha", SyncRunType.FACT_REFRESH, SyncRunStatus.SUCCESS, 8L));

    verify(configService, never()).getConfigById(org.mockito.ArgumentMatchers.any());
    verifyNoFactRefreshSubmission();
  }

  private void verifyNoFactRefreshSubmission() {
    verify(submissionService, never())
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any());
  }
}
