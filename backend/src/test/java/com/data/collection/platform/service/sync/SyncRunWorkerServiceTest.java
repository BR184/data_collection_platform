package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncRunWorkerServiceTest {
  private SyncRunMapper syncRunMapper;
  private SyncRunTablePlanningService tablePlanningService;
  private SyncRunTableWorkerService tableWorkerService;
  private GitlabConfigService configService;
  private SyncRunWorkerService workerService;

  @BeforeEach
  void setUp() {
    syncRunMapper = mock(SyncRunMapper.class);
    tablePlanningService = mock(SyncRunTablePlanningService.class);
    tableWorkerService = mock(SyncRunTableWorkerService.class);
    configService = mock(GitlabConfigService.class);
    workerService =
        new SyncRunWorkerService(syncRunMapper, tablePlanningService, tableWorkerService, configService);
  }

  @Test
  void shouldCompleteFullRunAndUpdateSyncTimestamp() {
    SyncRun run = run(11L, SyncRunType.FULL_SYNC);

    workerService.executeRun(run);

    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, true);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getStartedAt()).isNotNull();
    assertThat(run.getFinishedAt()).isNotNull();
  }

  @Test
  void shouldPlanAndDrainTableRefreshRun() {
    SyncRun run = run(12L, SyncRunType.TABLE_REFRESH);
    when(tablePlanningService.planRunTables(12L)).thenReturn(3);
    when(tableWorkerService.drainRunTasks(12L)).thenReturn(2);

    workerService.executeRun(run);

    verify(tablePlanningService).planRunTables(12L);
    verify(tableWorkerService).drainRunTasks(12L);
    verify(syncRunMapper, times(2)).updateById(run);
    verify(configService).updateSyncTime(1L, false);
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getPlannedTableCount()).isEqualTo(3);
    assertThat(run.getCompletedTableCount()).isEqualTo(2);
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
}
