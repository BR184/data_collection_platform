package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.CustomerIssueDelayPreWritebackSyncService.Outcome;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 延期标签写回编排的状态机：三段推进、单飞、周期重启与来源实例隔离。
 *
 * <p>执行器用记录式替身，所有阶段 3 任务都需要显式 {@link RecordingExecutor#drain()} 才运行，
 * 因此断言可以精确区分"已提交"与"已执行"。
 */
class CustomerIssueDelayClosureOrchestratorTest {
  private static final long MIRROR_RUN_ID = 77L;

  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private CustomerIssueDelayLabelWritebackQueueService queueService;
  private SyncFactPublicationStateService publicationStateService;
  private RecordingExecutor executor;
  private CustomerIssueDelayClosureOrchestrator orchestrator;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    preWritebackSyncService = mock(CustomerIssueDelayPreWritebackSyncService.class);
    queueService = mock(CustomerIssueDelayLabelWritebackQueueService.class);
    publicationStateService = mock(SyncFactPublicationStateService.class);
    executor = new RecordingExecutor();
    orchestrator =
        new CustomerIssueDelayClosureOrchestrator(
            configService,
            factBuildService,
            writebackService,
            preWritebackSyncService,
            queueService,
            publicationStateService,
            executor);
    config = config(1L, "default");
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.submitPreWritebackSync(config))
        .thenReturn(new Outcome.Submitted(MIRROR_RUN_ID));
  }

  @Test
  void shouldSubmitStageOneAndAdvanceOnlyAfterPublicationConverged() {
    orchestrator.runCycleForAllSources();

    verify(preWritebackSyncService).submitPreWritebackSync(config);
    assertThat(executor.tasks()).isEmpty();

    when(publicationStateService.countUnpublishedTargets("default", FactType.ISSUE)).thenReturn(0L);
    orchestrator.onRunTerminal(mirrorTerminalEvent(MIRROR_RUN_ID, SyncRunStatus.SUCCESS, 7L));

    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(queueService).enqueueCandidates(config);
  }

  @Test
  void shouldAdvanceWhenStageOneAppliedNoRowsAndPublicationIsConverged() {
    orchestrator.runCycleForAllSources();
    when(publicationStateService.countUnpublishedTargets("default", FactType.ISSUE)).thenReturn(0L);

    orchestrator.onRunTerminal(mirrorTerminalEvent(MIRROR_RUN_ID, SyncRunStatus.SUCCESS, 0L));

    assertThat(executor.tasks()).hasSize(1);
  }

  @Test
  void shouldKeepWaitingWhileTargetsRemainUnpublished() {
    orchestrator.runCycleForAllSources();
    when(publicationStateService.countUnpublishedTargets("default", FactType.ISSUE)).thenReturn(2L);

    orchestrator.onRunTerminal(mirrorTerminalEvent(MIRROR_RUN_ID, SyncRunStatus.SUCCESS, 7L));

    assertThat(executor.tasks()).isEmpty();

    when(publicationStateService.countUnpublishedTargets("default", FactType.ISSUE)).thenReturn(0L);
    orchestrator.onRunTerminal(factTerminalEvent(88L, SyncRunStatus.SUCCESS, 3L));

    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(queueService).enqueueCandidates(config);
  }

  @Test
  void shouldAbortCycleWhenStageOneRunDidNotSucceed() {
    orchestrator.runCycleForAllSources();

    orchestrator.onRunTerminal(mirrorTerminalEvent(MIRROR_RUN_ID, SyncRunStatus.FAILED, 0L));

    verify(publicationStateService, never()).countUnpublishedTargets(any(), any());
    assertThat(executor.tasks()).isEmpty();

    orchestrator.onRunTerminal(factTerminalEvent(88L, SyncRunStatus.SUCCESS, 3L));

    assertThat(executor.tasks()).isEmpty();
    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(config);
  }

  @Test
  void shouldRestartCycleInTheNextSchedulingPeriod() {
    orchestrator.runCycleForAllSources();
    when(preWritebackSyncService.submitPreWritebackSync(config))
        .thenReturn(new Outcome.Submitted(99L));

    orchestrator.runCycleForAllSources();

    verify(preWritebackSyncService, times(2)).submitPreWritebackSync(config);
    assertThat(executor.tasks()).isEmpty();

    orchestrator.onRunTerminal(mirrorTerminalEvent(99L, SyncRunStatus.FAILED, 0L));

    assertThat(executor.tasks()).isEmpty();
    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(config);
  }

  @Test
  void shouldSkipCycleWhenPreWritebackSyncRunWasRejected() {
    when(preWritebackSyncService.submitPreWritebackSync(config))
        .thenReturn(new Outcome.Rejected("未配置写回前增量刷新的来源表"));

    orchestrator.runCycleForAllSources();

    assertThat(executor.tasks()).isEmpty();
    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(config);

    orchestrator.runCycleForAllSources();

    verify(preWritebackSyncService, times(2)).submitPreWritebackSync(config);
  }

  @Test
  void shouldAdvanceWithoutWaitingWhenPreWritebackSyncIsNotRequired() {
    when(preWritebackSyncService.submitPreWritebackSync(config)).thenReturn(new Outcome.NotRequired());

    orchestrator.runCycleForAllSources();

    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(queueService).enqueueCandidates(config);
  }

  @Test
  void shouldRefreshFactsWithoutEnqueueingOrWaitingWhenWritebackIsDisabled() {
    when(writebackService.isEnabled(config)).thenReturn(false);

    orchestrator.runCycleForAllSources();

    verify(preWritebackSyncService, never()).submitPreWritebackSync(any());
    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(queueService, never()).enqueueCandidates(any());
    verify(publicationStateService, never()).countUnpublishedTargets(any(), any());
  }

  @Test
  void shouldIgnoreTerminalEventsWhenNoCycleIsInFlight() {
    orchestrator.onRunTerminal(mirrorTerminalEvent(MIRROR_RUN_ID, SyncRunStatus.SUCCESS, 5L));

    assertThat(executor.tasks()).isEmpty();
    verify(publicationStateService, never()).countUnpublishedTargets(any(), any());
    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(any());
  }

  @Test
  void shouldKeepDataSourceCyclesIndependent() {
    GitlabSyncConfig other = config(2L, "default");
    when(configService.listConfigs()).thenReturn(List.of(config, other));
    when(writebackService.isEnabled(other)).thenReturn(true);
    when(preWritebackSyncService.submitPreWritebackSync(other))
        .thenReturn(new Outcome.Submitted(88L));

    orchestrator.runCycleForAllSources();

    verify(preWritebackSyncService).submitPreWritebackSync(other);
    orchestrator.onRunTerminal(
        new SyncRunCompletionEvent(
            MIRROR_RUN_ID, 1L, "default", SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 3L));

    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(other);

    orchestrator.onRunTerminal(
        new SyncRunCompletionEvent(
            88L, 2L, "default", SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 3L));

    assertThat(executor.tasks()).hasSize(1);
    executor.drain();
    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(other);
  }

  private GitlabSyncConfig config(Long id, String sourceInstance) {
    GitlabSyncConfig result = new GitlabSyncConfig();
    result.setId(id);
    result.setSourceInstance(sourceInstance);
    result.setSourceEnabled(true);
    return result;
  }

  private SyncRunCompletionEvent mirrorTerminalEvent(
      long runId, SyncRunStatus status, long appliedRows) {
    return new SyncRunCompletionEvent(
        runId, 1L, "default", SyncRunType.TABLE_REFRESH, status, appliedRows);
  }

  private SyncRunCompletionEvent factTerminalEvent(
      long runId, SyncRunStatus status, long appliedRows) {
    return new SyncRunCompletionEvent(
        runId, 1L, "default", SyncRunType.FACT_REFRESH, status, appliedRows);
  }

  private static final class RecordingExecutor implements Executor {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    List<Runnable> tasks() {
      return tasks;
    }

    void drain() {
      List<Runnable> pending = new ArrayList<>(tasks);
      tasks.clear();
      pending.forEach(Runnable::run);
    }
  }
}
