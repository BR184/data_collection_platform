package com.data.collection.platform.service.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.GitlabConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncRunCompletionCommitServiceTest {
  private SyncRunPublicationFenceService publicationFenceService;
  private SyncRunLeaseService leaseService;
  private GitlabConfigService configService;
  private SyncIncrementalRerunService incrementalRerunService;
  private SyncSourceSubmissionLockService sourceSubmissionLockService;
  private SyncRunCompletionCommitService service;

  @BeforeEach
  void setUp() {
    publicationFenceService = mock(SyncRunPublicationFenceService.class);
    leaseService = mock(SyncRunLeaseService.class);
    configService = mock(GitlabConfigService.class);
    incrementalRerunService = mock(SyncIncrementalRerunService.class);
    sourceSubmissionLockService = mock(SyncSourceSubmissionLockService.class);
    service =
        new SyncRunCompletionCommitService(
            publicationFenceService,
            leaseService,
            configService,
            incrementalRerunService,
            sourceSubmissionLockService);
  }

  @Test
  void test_complete_incremental_success_advances_clock_and_consumes_pending() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.SUCCESS);
    when(leaseService.finishOwnedRun(run)).thenReturn(1);

    service.finishOwnedRun(run);

    verify(configService).updateSyncTime(1L, false);
    verify(incrementalRerunService).enqueuePendingRerun(run);
    verify(sourceSubmissionLockService).lock(1L, "default");
  }

  @Test
  void test_partial_incremental_does_not_advance_clock_but_keeps_tail_rerun() {
    SyncRun run = run(SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.PARTIAL_SUCCESS);
    when(leaseService.finishOwnedRun(run)).thenReturn(1);

    service.finishOwnedRun(run);

    verify(configService, never()).updateSyncTime(1L, false);
    verify(incrementalRerunService).enqueuePendingRerun(run);
    verify(sourceSubmissionLockService).lock(1L, "default");
  }

  @Test
  void test_table_refresh_success_never_advances_global_incremental_clock() {
    SyncRun run = run(SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS);
    when(leaseService.finishOwnedRun(run)).thenReturn(1);

    service.finishOwnedRun(run);

    verify(configService, never()).updateSyncTime(1L, false);
    verify(incrementalRerunService).enqueuePendingRerun(run);
    verify(sourceSubmissionLockService, never()).lock(1L, "default");
  }

  private SyncRun run(SyncRunType type, SyncRunStatus status) {
    SyncRun run = new SyncRun();
    run.setId(11L);
    run.setConfigId(1L);
    run.setSourceInstance("default");
    run.setRunType(type);
    run.setStatus(status);
    return run;
  }
}
