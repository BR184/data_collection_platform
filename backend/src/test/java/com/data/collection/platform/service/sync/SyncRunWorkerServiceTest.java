package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

class SyncRunWorkerServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunTablePlanningService tablePlanningService;
  private SyncRunTableWorkerService tableWorkerService;
  private GitlabConfigService configService;
  private SyncThreadBudgetResolver threadBudgetResolver;
  private ApplicationEventPublisher eventPublisher;
  private SyncFactRefreshRunExecutor factRefreshRunExecutor;
  private SyncRunWorkerService workerService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    tablePlanningService = mock(SyncRunTablePlanningService.class);
    tableWorkerService = mock(SyncRunTableWorkerService.class);
    configService = mock(GitlabConfigService.class);
    threadBudgetResolver = new SyncThreadBudgetResolver(new GitlabMirrorProperties());
    eventPublisher = mock(ApplicationEventPublisher.class);
    factRefreshRunExecutor = mock(SyncFactRefreshRunExecutor.class);
    workerService =
        new SyncRunWorkerService(
            syncRunMapper,
            tablePlanningService,
            tableWorkerService,
            configService,
            threadBudgetResolver,
            eventPublisher,
            factRefreshRunExecutor);
  }

  @Test
  void shouldCompleteFullRunAndUpdateSyncTimestamp() {
    SyncRun run = run(11L, SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(tablePlanningService.planRunTables(11L)).thenReturn(4);
    when(tableWorkerService.drainRunTasks(11L, 2)).thenReturn(4);
    when(tableWorkerService.summarizeRun(11L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(4, 4, 20L, 18L));
    when(configService.getConfigById(1L)).thenReturn(config);

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(11L);
    verify(tableWorkerService).drainRunTasks(11L, 2);
    verify(tableWorkerService).summarizeRun(11L);
    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, true);
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
  void shouldPlanAndDrainTableRefreshRun() {
    SyncRun run = run(12L, SyncRunType.TABLE_REFRESH);
    GitlabSyncConfig config = config();
    when(tablePlanningService.planRunTables(12L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(12L, 2)).thenReturn(2);
    when(tableWorkerService.summarizeRun(12L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L));
    when(configService.getConfigById(1L)).thenReturn(config);

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(12L);
    verify(tableWorkerService).drainRunTasks(12L, 2);
    verify(tableWorkerService).summarizeRun(12L);
    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, false);
    verifyMirrorCompletionEvent(12L, SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 5L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getPlannedTableCount()).isEqualTo(3);
    assertThat(run.getCompletedTableCount()).isEqualTo(2);
    assertThat(run.getScannedRows()).isEqualTo(7L);
    assertThat(run.getAppliedRows()).isEqualTo(5L);
  }

  @Test
  void shouldMarkMirrorRunPartialSuccessWhenAnyTableTaskFailed() {
    SyncRun run = run(15L, SyncRunType.INCREMENTAL_SYNC);
    when(tablePlanningService.planRunTables(15L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(15L, 2)).thenReturn(2);
    when(tableWorkerService.summarizeRun(15L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L, 1, 0, 0, 0, 0, 0));

    workerService.executeRun(run);

    verify(configService).updateSyncTime(1L, false);
    verifyMirrorCompletionEvent(15L, SyncRunType.INCREMENTAL_SYNC, SyncRunStatus.PARTIAL_SUCCESS, 5L);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(run.getErrorMessage()).isEqualTo("One or more table tasks failed");
  }

  @Test
  void shouldUseRunThreadBudgetSnapshotWhenDrainingMirrorTasks() {
    SyncRun run = run(16L, SyncRunType.TABLE_REFRESH);
    run.setThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    run.setThreadValue(BigDecimal.valueOf(3));
    GitlabSyncConfig config = config();
    config.setMaxSyncThreads(5);
    when(tablePlanningService.planRunTables(16L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(16L, 3)).thenReturn(3);
    when(tableWorkerService.summarizeRun(16L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 3, 8L, 6L));
    when(configService.getConfigById(1L)).thenReturn(config);

    workerService.executeRun(run);

    verify(tableWorkerService).drainRunTasks(16L, 3);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
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
        .thenReturn(new SyncFactRefreshRunExecutor.Result(1, 1, 8L, SyncRunStatus.SUCCESS, null));

    workerService.executeRun(run);

    verify(factRefreshRunExecutor).execute(run);
    verify(configService, org.mockito.Mockito.never()).updateSyncTime(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
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
    verify(tableWorkerService, org.mockito.Mockito.never()).drainRunTasks(13L);
    verify(configService, org.mockito.Mockito.never()).updateSyncTime(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(org.mockito.ArgumentMatchers.any());
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.CANCELLED);
    assertThat(run.getErrorMessage()).isEqualTo("Sync run cancelled before processing");
  }

  private SyncRun run(Long id, SyncRunType runType) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_" + id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(runType);
    run.setStatus(SyncRunStatus.QUEUED);
    return run;
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setSyncThreadMode(SyncThreadBudgetResolver.MODE_FIXED);
    config.setSyncThreadValue(BigDecimal.valueOf(2));
    config.setMaxSyncThreads(4);
    return config;
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
