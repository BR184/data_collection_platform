package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

class SyncRunWorkerServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunTablePlanningService tablePlanningService;
  private SyncRunTableWorkerService tableWorkerService;
  private GitlabConfigService configService;
  private SyncRunSubmissionService submissionService;
  private FactBuildTaskService factBuildTaskService;
  private FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private JsonUtils jsonUtils;
  private SyncRunWorkerService workerService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    tablePlanningService = mock(SyncRunTablePlanningService.class);
    tableWorkerService = mock(SyncRunTableWorkerService.class);
    configService = mock(GitlabConfigService.class);
    submissionService = mock(SyncRunSubmissionService.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    factRefreshTaskWorkerService = mock(FactRefreshTaskWorkerService.class);
    jsonUtils = new JsonUtils(new ObjectMapper());
    workerService =
        new SyncRunWorkerService(
            syncRunMapper,
            tablePlanningService,
            tableWorkerService,
            configService,
            submissionService,
            factBuildTaskService,
            factRefreshTaskWorkerService,
            jsonUtils);
  }

  @Test
  void shouldCompleteFullRunAndUpdateSyncTimestamp() {
    SyncRun run = run(11L, SyncRunType.FULL_SYNC);
    GitlabSyncConfig config = config();
    when(tablePlanningService.planRunTables(11L)).thenReturn(4);
    when(tableWorkerService.drainRunTasks(11L)).thenReturn(4);
    when(tableWorkerService.summarizeRun(11L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(4, 4, 20L, 18L));
    when(configService.getConfigById(1L)).thenReturn(config);

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(11L);
    verify(tableWorkerService).drainRunTasks(11L);
    verify(tableWorkerService).summarizeRun(11L);
    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, true);
    verify(submissionService).submitFactRefresh(config, 11L, true, "Mirror run completed; refresh fact layer");
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
    when(tableWorkerService.drainRunTasks(12L)).thenReturn(2);
    when(tableWorkerService.summarizeRun(12L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L));
    when(configService.getConfigById(1L)).thenReturn(config);

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(12L);
    verify(tableWorkerService).drainRunTasks(12L);
    verify(tableWorkerService).summarizeRun(12L);
    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, false);
    verify(submissionService).submitFactRefresh(config, 12L, false, "Mirror run completed; refresh fact layer");
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
    when(tableWorkerService.drainRunTasks(15L)).thenReturn(2);
    when(tableWorkerService.summarizeRun(15L))
        .thenReturn(new SyncRunTableWorkerService.RunTableTaskSummary(3, 2, 7L, 5L, 1, 0, 0, 0, 0, 0));

    workerService.executeRun(run);

    verify(configService).updateSyncTime(1L, false);
    verify(submissionService, org.mockito.Mockito.never())
        .submitFactRefresh(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any());
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(run.getErrorMessage()).isEqualTo("One or more table tasks failed");
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
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task =
        new QueuedFactBuildTask(101L, 1L, "alpha", "ISSUE", "alpha:issue", true, 0, 3, LocalDateTime.now().plusSeconds(30));
    when(configService.getConfigById(1L)).thenReturn(config);
    when(factBuildTaskService.enqueueMirrorRefreshTasks(config, true, 14L)).thenReturn(1);
    when(factBuildTaskService.claimNextQueuedTaskForRun(14L, "fact-run-worker", 30))
        .thenReturn(task)
        .thenReturn(null);
    when(factRefreshTaskWorkerService.execute(task))
        .thenReturn(new FactBuildResponse("alpha:issue", true, 8, "issue facts built"));

    workerService.executeRun(run);

    verify(factBuildTaskService).enqueueMirrorRefreshTasks(config, true, 14L);
    verify(factRefreshTaskWorkerService).execute(task);
    verify(configService, org.mockito.Mockito.never()).updateSyncTime(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
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
    return config;
  }
}
