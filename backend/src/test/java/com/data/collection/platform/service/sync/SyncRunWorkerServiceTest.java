package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

class SyncRunWorkerServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunLeaseService leaseService;
  private SyncRunTablePlanningService tablePlanningService;
  private SyncRunTableWorkerService tableWorkerService;
  private SyncRunAuthoritativeScopeWorkerService authoritativeScopeWorkerService;
  private SyncRunReconciliationCoordinator reconciliationCoordinator;
  private ApplicationEventPublisher eventPublisher;
  private SyncFactRefreshRunExecutor factRefreshRunExecutor;
  private SyncRunDeadlineGuard deadlineGuard;
  private SyncRunCompletionCommitService completionCommitService;
  private SyncIncrementalCoverageService incrementalCoverageService;
  private SyncRunWorkerService workerService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    leaseService = mock(SyncRunLeaseService.class);
    tablePlanningService = mock(SyncRunTablePlanningService.class);
    tableWorkerService = mock(SyncRunTableWorkerService.class);
    authoritativeScopeWorkerService = mock(SyncRunAuthoritativeScopeWorkerService.class);
    reconciliationCoordinator = mock(SyncRunReconciliationCoordinator.class);
    eventPublisher = mock(ApplicationEventPublisher.class);
    factRefreshRunExecutor = mock(SyncFactRefreshRunExecutor.class);
    deadlineGuard = mock(SyncRunDeadlineGuard.class);
    completionCommitService = mock(SyncRunCompletionCommitService.class);
    incrementalCoverageService = mock(SyncIncrementalCoverageService.class);
    workerService =
        new SyncRunWorkerService(
            syncRunMapper,
            leaseService,
            tablePlanningService,
            tableWorkerService,
            authoritativeScopeWorkerService,
            reconciliationCoordinator,
            eventPublisher,
            factRefreshRunExecutor,
            deadlineGuard,
            completionCommitService,
            incrementalCoverageService);
    when(incrementalCoverageService.evaluate(anyLong()))
        .thenReturn(SyncIncrementalCoverageService.CoverageResult.fresh());
    when(authoritativeScopeWorkerService.drainRunScopes(any(SyncRun.class), anyInt()))
        .thenReturn(new SyncRunAuthoritativeScopeWorkerService.DrainResult(0, false));
    when(authoritativeScopeWorkerService.summarize(anyLong()))
        .thenReturn(
            new SyncRunAuthoritativeScopeRepository.ScopeSummary(
                0, 0, 0, 0, 0, 0, null));
  }

  @Test
  void shouldCompleteFullRunAndUpdateSyncTimestamp() {
    SyncRun run = run(11L, SyncRunType.FULL_SYNC);
    when(tablePlanningService.planRunTables(11L)).thenReturn(4);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(4, false));
    when(tableWorkerService.summarizeRun(11L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(4, 4, 20L, 18L));

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(11L);
    verify(tableWorkerService).drainRunTasks(run, 2);
    verify(tableWorkerService).summarizeRun(11L);
    verify(completionCommitService).finishOwnedRun(run);
    verifyMirrorCompletionEvent(11L, SyncRunType.FULL_SYNC, SyncRunStatus.SUCCESS, 18L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getStartedAt()).isNotNull();
    assertThat(run.getFinishedAt()).isNotNull();
    assertThat(run.getPlannedTableCount()).isEqualTo(4);
    assertThat(run.getCompletedTableCount()).isEqualTo(4);
    assertThat(run.getScannedRows()).isEqualTo(20L);
    assertThat(run.getAppliedRows()).isEqualTo(18L);
  }

  @Test
  void shouldPlanAndDrainTableRefreshWithoutUpdatingGlobalIncrementalClock() {
    SyncRun run = run(12L, SyncRunType.TABLE_REFRESH);
    when(tablePlanningService.planRunTables(12L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(2, false));
    when(tableWorkerService.summarizeRun(12L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L));

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(12L);
    verify(tableWorkerService).drainRunTasks(run, 2);
    verify(tableWorkerService).summarizeRun(12L);
    verify(completionCommitService).finishOwnedRun(run);
    verifyMirrorCompletionEvent(12L, SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 5L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getPlannedTableCount()).isEqualTo(3);
    assertThat(run.getCompletedTableCount()).isEqualTo(2);
    assertThat(run.getScannedRows()).isEqualTo(7L);
    assertThat(run.getAppliedRows()).isEqualTo(5L);
  }

  @Test
  void shouldReportDynamicallyDerivedTasksInFinalPlannedCount() {
    SyncRun run = run(18L, SyncRunType.TABLE_REFRESH);
    when(tablePlanningService.planRunTables(18L)).thenReturn(1);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(3, false));
    when(tableWorkerService.summarizeRun(18L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 3, 6L, 5L));

    workerService.executeRun(run);

    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getPlannedTableCount()).isEqualTo(3);
    assertThat(run.getCompletedTableCount()).isEqualTo(3);
  }

  @Test
  void shouldMarkMirrorRunPartialSuccessWhenAnyTableTaskFailed() {
    SyncRun run = run(15L, SyncRunType.INCREMENTAL_SYNC);
    when(tablePlanningService.planRunTables(15L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(2, false));
    when(tableWorkerService.summarizeRun(15L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L, 1, 0, 0, 0, 0, 0));

    workerService.executeRun(run);

    verifyMirrorCompletionEvent(15L, SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.PARTIAL_SUCCESS, 5L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(run.getErrorMessage()).isEqualTo("一个或多个表任务失败");
  }

  @Test
  void test_zero_table_incremental_run_fails_instead_of_reporting_green_success() {
    SyncRun run = run(24L, SyncRunType.INCREMENTAL_SYNC);
    when(tablePlanningService.planRunTables(24L)).thenReturn(0);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(0, false));
    when(tableWorkerService.summarizeRun(24L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(0, 0, 0L, 0L));

    workerService.executeRun(run);

    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.FAILED);
    assertThat(run.getErrorMessage()).contains("未规划任何快速增量表");
  }

  @Test
  void test_retry_waiting_scope_defers_same_run_without_planning_reconciliation() {
    SyncRun run = run(21L, SyncRunType.INCREMENTAL_SYNC);
    LocalDateTime retryAt = LocalDateTime.now().plusSeconds(30);
    when(tablePlanningService.planRunTables(21L)).thenReturn(2);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(2, false));
    when(tableWorkerService.summarizeRun(21L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(2, 2, 4L, 2L));
    when(authoritativeScopeWorkerService.summarize(21L))
        .thenReturn(
            new SyncRunAuthoritativeScopeRepository.ScopeSummary(
                2, 1, 0, 0, 0, 1, retryAt));
    when(leaseService.deferOwnedRun(any(), any(), any(), any())).thenReturn(1);

    workerService.executeRun(run);

    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.RETRYING);
    assertThat(run.getRunAfter()).isEqualTo(retryAt);
    verify(leaseService)
        .deferOwnedRun(run, SyncRunStatus.RETRYING, retryAt, "权威关系范围等待重试");
    verify(reconciliationCoordinator, never()).planIfReady(21L);
    verify(tableWorkerService).summarizeRun(21L);
    verify(completionCommitService, never()).finishOwnedRun(run);
  }

  @Test
  void test_retrying_table_defers_same_run_even_when_another_table_failed() {
    SyncRun run = run(25L, SyncRunType.INCREMENTAL_SYNC);
    LocalDateTime retryAt = LocalDateTime.now().plusSeconds(5);
    when(tablePlanningService.planRunTables(25L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(3, false));
    when(tableWorkerService.summarizeRun(25L))
        .thenReturn(
            new SyncRunTableWorkerService.RunTableTaskSummary(
                3, 1, 10L, 4L, 1, 0, 0, 0, 0, 1, retryAt));
    when(leaseService.deferOwnedRun(any(), any(), any(), any())).thenReturn(1);

    workerService.executeRun(run);

    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.RETRYING);
    assertThat(run.getRunAfter()).isEqualTo(retryAt);
    verify(leaseService)
        .deferOwnedRun(run, SyncRunStatus.RETRYING, retryAt, "表任务等待重试");
    verify(authoritativeScopeWorkerService, never())
        .drainRunScopes(any(SyncRun.class), anyInt());
    verify(completionCommitService, never()).finishOwnedRun(run);
  }

  @Test
  void test_successful_scope_batch_does_not_add_full_table_reconciliation() {
    SyncRun run = run(22L, SyncRunType.INCREMENTAL_SYNC);
    when(tablePlanningService.planRunTables(22L)).thenReturn(2);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(2, false));
    when(authoritativeScopeWorkerService.drainRunScopes(run, 2))
        .thenReturn(new SyncRunAuthoritativeScopeWorkerService.DrainResult(2, false));
    when(authoritativeScopeWorkerService.summarize(22L))
        .thenReturn(
            new SyncRunAuthoritativeScopeRepository.ScopeSummary(
                2, 2, 0, 0, 0, 0, null));
    when(tableWorkerService.summarizeRun(22L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(2, 2, 12L, 3L));

    workerService.executeRun(run);

    verify(tableWorkerService).drainRunTasks(run, 2);
    verify(reconciliationCoordinator).planIfReady(22L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
  }

  @Test
  void test_failed_scope_prevents_reconciliation_and_marks_run_partial() {
    SyncRun run = run(23L, SyncRunType.INCREMENTAL_SYNC);
    when(tablePlanningService.planRunTables(23L)).thenReturn(2);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(2, false));
    when(authoritativeScopeWorkerService.summarize(23L))
        .thenReturn(
            new SyncRunAuthoritativeScopeRepository.ScopeSummary(
                2, 1, 1, 0, 0, 0, null));
    when(tableWorkerService.summarizeRun(23L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(2, 2, 6L, 1L));

    workerService.executeRun(run);

    verify(reconciliationCoordinator, never()).planIfReady(23L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(run.getErrorMessage()).isEqualTo("一个或多个权威关系范围失败");
  }

  @Test
  void shouldUseRunThreadBudgetSnapshotWhenDrainingMirrorTasks() {
    SyncRun run = run(16L, SyncRunType.TABLE_REFRESH);
    run.setResolvedWorkerCount(3);
    when(tablePlanningService.planRunTables(16L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(run, 3))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(3, false));
    when(tableWorkerService.summarizeRun(16L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 3, 8L, 6L));

    workerService.executeRun(run);

    verify(tableWorkerService).drainRunTasks(run, 3);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
  }

  @Test
  void shouldLeaveYieldedBackgroundRunPausedWithoutPublishingCompletion() {
    SyncRun run = run(19L, SyncRunType.FULL_COMPENSATION_SCAN);
    when(tablePlanningService.planRunTables(19L)).thenReturn(4);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenAnswer(
            invocation -> {
              run.setStatus(SyncRunStatus.PAUSED);
              return new SyncRunTableWorkerService.DrainResult(2, true);
            });

    workerService.executeRun(run);

    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PAUSED);
    assertThat(run.getFinishedAt()).isNull();
    verify(tableWorkerService, org.mockito.Mockito.never()).summarizeRun(19L);
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    verify(completionCommitService, never()).finishOwnedRun(run);
  }

  @Test
  void shouldNotPublishCompletionWhenRunLeaseWasLostBeforeFinalCommit() {
    SyncRun run = run(20L, SyncRunType.TABLE_REFRESH);
    when(tablePlanningService.planRunTables(20L)).thenReturn(1);
    when(tableWorkerService.drainRunTasks(run, 2))
        .thenReturn(new SyncRunTableWorkerService.DrainResult(1, false));
    when(tableWorkerService.summarizeRun(20L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(1, 1, 1L, 1L));
    org.mockito.Mockito.doThrow(new SyncRunLeaseLostException(run.getId()))
        .when(completionCommitService)
        .finishOwnedRun(run);

    workerService.executeRun(run);

    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void shouldNotWrapWholeRunExecutionInSingleTransaction() throws Exception {
    Method executeRun = SyncRunWorkerService.class.getMethod("executeRun", SyncRun.class);

    assertThat(executeRun.isAnnotationPresent(Transactional.class)).isFalse();
  }

  @Test
  void shouldExecuteFactRefreshRunThroughQueuedFactTasks() {
    SyncRun run = run(14L, SyncRunType.FACT_REFRESH);
    run.setPayloadJson("{\"fullBuild\":true}");
    when(factRefreshRunExecutor.execute(run))
        .thenReturn(
            new SyncFactRefreshRunExecutor.Result(
                1, 1, 8L, SyncRunStatus.SUCCESS, null, null));

    workerService.executeRun(run);

    verify(factRefreshRunExecutor).execute(run);
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getPlannedTableCount()).isEqualTo(1);
    assertThat(run.getCompletedTableCount()).isEqualTo(1);
    assertThat(run.getAppliedRows()).isEqualTo(8L);
  }

  @Test
  void shouldStopBeforePlanningWhenCancellationWasRequested() {
    SyncRun run = run(13L, SyncRunType.TABLE_REFRESH);
    SyncRun cancelling = run(13L, SyncRunType.TABLE_REFRESH);
    cancelling.setCancelRequested(true);
    cancelling.setStatus(SyncRunStatus.CANCELLING);
    when(syncRunMapper.selectById(13L)).thenReturn(cancelling);

    workerService.executeRun(run);

    verify(tablePlanningService, org.mockito.Mockito.never()).planRunTables(13L);
    verify(tableWorkerService, org.mockito.Mockito.never())
        .drainRunTasks(
            org.mockito.ArgumentMatchers.any(SyncRun.class),
            org.mockito.ArgumentMatchers.anyInt());
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(run.getErrorMessage()).isEqualTo("同步运行在处理前已取消");
  }

  @Test
  void shouldStopBeforeDrainingTablesWhenDeadlineExpiredAfterPlanning() {
    SyncRun run = run(17L, SyncRunType.FULL_COMPENSATION_SCAN);
    when(tablePlanningService.planRunTables(17L)).thenReturn(8);
    when(deadlineGuard.requestCancellationIfExpired(run))
        .thenReturn(false)
        .thenAnswer(invocation -> {
          run.setErrorMessage("Sync run exceeded maximum runtime of 60 minutes");
          return true;
        });

    workerService.executeRun(run);

    verify(tableWorkerService, org.mockito.Mockito.never())
        .drainRunTasks(
            org.mockito.ArgumentMatchers.any(SyncRun.class),
            org.mockito.ArgumentMatchers.anyInt());
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(run.getErrorMessage()).isEqualTo("Sync run exceeded maximum runtime of 60 minutes");
    assertThat(run.getPlannedTableCount()).isEqualTo(8);
    assertThat(run.getCompletedTableCount()).isZero();
  }

  private SyncRun run(Long id, SyncRunType runType) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_" + id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(runType);
    run.setStatus(SyncRunStatus.QUEUED);
    run.setLeaseOwner("run-owner-" + id);
    run.setResolvedWorkerCount(2);
    return run;
  }

  private void verifyMirrorCompletionEvent(
      Long runId,
      SyncRunType runType,
      SyncRunStatus status,
      Long appliedRows) {
    ArgumentCaptor<SyncRunCompletionEvent> captor = ArgumentCaptor.forClass(SyncRunCompletionEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    SyncRunCompletionEvent event = captor.getValue();
    assertThat(event.runId()).isEqualTo(runId);
    assertThat(event.configId()).isEqualTo(1L);
    assertThat(event.sourceInstance()).isEqualTo("alpha");
    assertThat(event.runType()).isEqualTo(runType);
    assertThat(event.status()).isEqualTo(status);
    assertThat(event.appliedRows()).isEqualTo(appliedRows);
  }
}
