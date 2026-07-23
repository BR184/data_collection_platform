package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.PageRecordSnapshotRefreshService;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncFactRefreshRunExecutorTest {
  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private FactBuildTaskService factBuildTaskService;
  private FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private StatisticBoardSnapshotRefreshService snapshotRefreshService;
  private PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService;
  private SyncFactRefreshRunExecutor executor;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    factRefreshTaskWorkerService = mock(FactRefreshTaskWorkerService.class);
    snapshotRefreshService = mock(StatisticBoardSnapshotRefreshService.class);
    pageRecordSnapshotRefreshService = mock(PageRecordSnapshotRefreshService.class);
    executor =
        new SyncFactRefreshRunExecutor(
            configService,
            factBuildService,
            factBuildTaskService,
            factRefreshTaskWorkerService,
            snapshotRefreshService,
            pageRecordSnapshotRefreshService,
            new JsonUtils(new ObjectMapper()));
  }

  @Test
  void shouldExecuteQueuedFactTasksForFactRefreshRun() {
    SyncRun run = run(14L);
    run.setPayloadJson("{\"fullBuild\":true}");
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    QueuedFactBuildTask task =
        new QueuedFactBuildTask(
            101L,
            14L,
            1L,
            "alpha",
            "ISSUE",
            "alpha:issue",
            true,
            0,
            3,
            LocalDateTime.now().plusSeconds(30));
    when(configService.getConfigById(1L)).thenReturn(config);
    when(factBuildTaskService.enqueueMirrorRefreshTasks(config, true, 14L)).thenReturn(1);
    when(factBuildTaskService.claimNextQueuedTaskForRun(14L, "fact-run-worker", 30))
        .thenReturn(task)
        .thenReturn(null);
    when(factRefreshTaskWorkerService.execute(task))
        .thenReturn(new FactBuildResponse("alpha:issue", true, 8, "issue facts built"));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    verify(factBuildTaskService).enqueueMirrorRefreshTasks(config, true, 14L);
    verify(factRefreshTaskWorkerService).execute(task);
    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(result.plannedTasks()).isEqualTo(1);
    assertThat(result.completedTasks()).isEqualTo(1);
    assertThat(result.affectedRows()).isEqualTo(8L);
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  void shouldReturnPartialSuccessWhenSomeFactTasksDoNotComplete() {
    SyncRun run = run(15L);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(factBuildTaskService.enqueueMirrorRefreshTasks(config, false, 15L)).thenReturn(2);
    when(factBuildTaskService.claimNextQueuedTaskForRun(15L, "fact-run-worker", 30)).thenReturn(null);

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(result.plannedTasks()).isEqualTo(2);
    assertThat(result.completedTasks()).isZero();
    assertThat(result.errorMessage()).isEqualTo("部分事实数据刷新任务未完成");
  }

  @Test
  void shouldUseUnifiedFullBuildForManualRebuild() {
    SyncRun run = run(16L);
    run.setPayloadJson("{\"fullBuild\":true,\"manualFullRebuild\":true}");
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(factBuildService.rebuildAllFactsForConfig(config, true, 16L))
        .thenReturn(new FactBuildResponse("alpha:all", true, 21, "全部事实层已重建"));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    verify(factBuildService).rebuildAllFactsForConfig(config, true, 16L);
    verify(snapshotRefreshService).refreshAfterFactBuild("ALL", true);
    verify(pageRecordSnapshotRefreshService).refreshAfterFactBuild("ALL", true);
    verify(factBuildTaskService, never()).enqueueMirrorRefreshTasks(config, true, 16L);
    verifyNoInteractions(factRefreshTaskWorkerService);
    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(result.plannedTasks()).isEqualTo(1);
    assertThat(result.completedTasks()).isEqualTo(1);
    assertThat(result.affectedRows()).isEqualTo(21L);
  }

  private SyncRun run(Long id) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.FACT_REFRESH);
    return run;
  }
}
