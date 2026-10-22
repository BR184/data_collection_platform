package com.data.collection.platform.service;

import static com.data.collection.platform.service.CustomerIssueDelayClosureStage.WAIT_FACT;
import static com.data.collection.platform.service.CustomerIssueDelayClosureStage.WAIT_MIRROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 延期写回编排的阶段推进与持久恢复。
 *
 * <p>编排器不保存内存在飞状态；这里用持久进度替身验证：无关运行终态只唤醒检查、重启后的新实例
 * 从同一行继续、WAIT_FACT 未收敛不入队、开关关闭只重算事实。
 */
class CustomerIssueDelayClosureOrchestratorTest {
  private static final long CONFIG_ID = 1L;
  private static final long MIRROR_RUN_ID = 77L;
  private static final String SOURCE = "default";
  private static final List<String> TABLES = List.of("issues", "notes", "label_links", "labels");

  private GitlabConfigService configService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private CustomerIssueDelayClosureProgressRepository progressRepository;
  private CustomerIssueDelayClosureRoundService roundService;
  private CustomerIssueDelaySourceReadinessService readinessService;
  private GitlabMirrorProperties properties;
  private CustomerIssueDelayClosureOrchestrator orchestrator;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    preWritebackSyncService = mock(CustomerIssueDelayPreWritebackSyncService.class);
    progressRepository = mock(CustomerIssueDelayClosureProgressRepository.class);
    roundService = mock(CustomerIssueDelayClosureRoundService.class);
    readinessService = mock(CustomerIssueDelaySourceReadinessService.class);
    properties = new GitlabMirrorProperties();
    properties.setCustomerIssueDelayProgressCheckDelayMs(1_000);
    properties.setCustomerIssueDelayProgressLeaseSeconds(60);
    properties.setCustomerIssueDelayEnqueueBatchSize(100);
    Executor directExecutor = Runnable::run;
    orchestrator =
        new CustomerIssueDelayClosureOrchestrator(
            configService,
            writebackService,
            preWritebackSyncService,
            progressRepository,
            roundService,
            readinessService,
            properties,
            directExecutor);
    config = config();
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.getConfigById(CONFIG_ID)).thenReturn(config);
    when(configService.isSourceEnabled(config)).thenReturn(true);
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.preWritebackTables()).thenReturn(TABLES);
    when(progressRepository.release(any())).thenReturn(true);
  }

  @Test
  void periodicRoundStartsMirrorAndDoesNotEnqueueBeforeConvergence() {
    CustomerIssueDelayClosureProgress requested = progress(CustomerIssueDelayClosureStage.REQUESTED, 0L, 0);
    stubClaimOnce(requested);
    CustomerIssueDelayClosureProgressClaim boundClaim = claim(progress(WAIT_MIRROR));
    when(roundService.startMirrorRound(any(), eq(config)))
        .thenReturn(new CustomerIssueDelayClosureRoundService.StartResult(
            CustomerIssueDelayClosureRoundService.StartStatus.WAITING, boundClaim, null));

    orchestrator.runCycleForAllSources();

    verify(progressRepository).requestRound(config, "periodic");
    verify(roundService).startMirrorRound(any(), eq(config));
    verify(roundService, never()).enqueuePage(any(), any(), anyLong(), anyInt());
  }

  @Test
  void unrelatedTerminalEventMustNotAdvanceWhileBoundMirrorIsStillRunning() {
    CustomerIssueDelayClosureProgress waiting = progress(WAIT_MIRROR);
    stubEventWaiting(waiting);
    when(readinessService.checkMirrorRun(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.MirrorCheck(false, false, "仍在运行"));
    when(progressRepository.waitFor(any(), anyString(), any()))
        .thenReturn(Optional.of(waiting));

    orchestrator.onRunTerminal(
        new SyncRunCompletionEvent(
            88L, CONFIG_ID, SOURCE, SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 0L));

    verify(roundService, never()).authorizeEnqueue(any(), any(), anyLong());
    verify(roundService, never()).enqueuePage(any(), any(), anyLong(), anyInt());
  }

  @Test
  void restartedOrchestratorResumesWaitMirrorFromPersistentProgress() {
    CustomerIssueDelayClosureProgress waiting = progress(WAIT_MIRROR);
    stubEventWaiting(waiting);
    when(readinessService.checkMirrorRun(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.MirrorCheck(true, true, null));
    CustomerIssueDelayClosureProgress factWait = progress(WAIT_FACT);
    when(progressRepository.moveToFactWait(any(), anyString()))
        .thenReturn(Optional.of(factWait));
    when(readinessService.assess(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.Readiness(true, null, 42L, 0L));
    CustomerIssueDelayClosureProgressClaim authorizedClaim =
        claim(progress(CustomerIssueDelayClosureStage.ENQUEUE, 42L, 7L));
    when(roundService.authorizeEnqueue(any(), eq(config), eq(42L)))
        .thenReturn(CustomerIssueDelayClosureRoundService.AuthorizationResult.authorized(
            authorizedClaim, 42L));
    when(roundService.enqueuePage(any(), eq(CONFIG_ID), eq(42L), anyInt()))
        .thenReturn(CustomerIssueDelayClosureRoundService.EnqueueResult.completed(
            authorizedClaim, 3));

    CustomerIssueDelayClosureOrchestrator restarted = newInstance(Runnable::run);
    restarted.onRunTerminal(terminalEvent(77L));

    verify(roundService).authorizeEnqueue(any(), eq(config), eq(42L));
    verify(roundService).enqueuePage(any(), eq(CONFIG_ID), eq(42L), anyInt());
  }

  @Test
  void waitFactWithoutConvergenceDoesNotAuthorizeOrEnqueue() {
    CustomerIssueDelayClosureProgress waiting = progress(WAIT_FACT);
    stubEventWaiting(waiting);
    when(readinessService.checkMirrorRun(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.MirrorCheck(true, true, null));
    when(readinessService.assess(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(
            new CustomerIssueDelaySourceReadinessService.Readiness(
                false, "仍有 2 项待发布", 42L, 2L));
    when(progressRepository.waitFor(any(), anyString(), any()))
        .thenReturn(Optional.of(waiting));

    orchestrator.onRunTerminal(terminalEvent(88L));

    verify(roundService, never()).authorizeEnqueue(any(), any(), anyLong());
    verify(roundService, never()).enqueuePage(any(), any(), anyLong(), anyInt());
  }

  @Test
  void writebackDisabledRefreshesFactsOnlyWithoutSchedulingMirror() {
    CustomerIssueDelayClosureProgress requested = progress(CustomerIssueDelayClosureStage.REQUESTED, 0L, 0);
    stubClaimOnce(requested);
    when(writebackService.isEnabled(config)).thenReturn(false);
    CustomerIssueDelayClosureProgressClaim finishedClaim = claim(progress(0L, 0));
    when(roundService.refreshFactsOnly(any(), eq(config), anyString()))
        .thenReturn(new CustomerIssueDelayClosureRoundService.StartResult(
            CustomerIssueDelayClosureRoundService.StartStatus.FACT_ONLY, finishedClaim, "关闭"));

    orchestrator.runCycleForAllSources();

    verify(roundService).refreshFactsOnly(any(), eq(config), anyString());
    verify(roundService, never()).startMirrorRound(any(), any());
  }

  @Test
  void terminalMirrorFailureRestartsRoundWithBackoff() {
    CustomerIssueDelayClosureProgress waiting = progress(WAIT_MIRROR);
    stubEventWaiting(waiting);
    when(readinessService.checkMirrorRun(config, MIRROR_RUN_ID, TABLES))
        .thenReturn(
            new CustomerIssueDelaySourceReadinessService.MirrorCheck(
                false, true, "前置镜像终态非 SUCCESS：PARTIAL_SUCCESS"));
    CustomerIssueDelayClosureProgress restarted = progress(CustomerIssueDelayClosureStage.REQUESTED, 0L, 0);
    when(progressRepository.restartRound(any(), anyString(), anyInt(), any()))
        .thenReturn(Optional.of(restarted));

    orchestrator.onRunTerminal(terminalEvent(77L));

    verify(progressRepository)
        .restartRound(any(), anyString(), eq(1), any());
    verify(roundService, never()).authorizeEnqueue(any(), any(), anyLong());
  }

  @Test
  void enqueuePageOnlyAdvancesOneBoundedPagePerClaim() {
    CustomerIssueDelayClosureProgress enqueue =
        progress(CustomerIssueDelayClosureStage.ENQUEUE, 42L, 7L);
    enqueue = new CustomerIssueDelayClosureProgress(
        enqueue.id(), enqueue.sourceInstance(), enqueue.configId(), enqueue.stage(),
        enqueue.requestRevision(), enqueue.processedRevision(), enqueue.completedRevision(),
        MIRROR_RUN_ID, enqueue.authorizedFactVersion(), enqueue.authorizationRevision(),
        900L, enqueue.failureCount(), enqueue.nextCheckAt(), enqueue.leaseOwner(),
        enqueue.leaseToken(), enqueue.leaseUntil(), enqueue.lastError());
    stubClaimOnce(enqueue);
    CustomerIssueDelayClosureProgressClaim advanced = claim(enqueue);
    when(roundService.enqueuePage(any(), eq(CONFIG_ID), eq(42L), anyInt()))
        .thenReturn(CustomerIssueDelayClosureRoundService.EnqueueResult.advanced(advanced, 100));

    orchestrator.checkProgressForAllSources();

    verify(roundService, times(1)).enqueuePage(any(), eq(CONFIG_ID), eq(42L), anyInt());
  }

  private void stubClaimOnce(CustomerIssueDelayClosureProgress progress) {
    when(progressRepository.claimNext(anyString(), anyString(), anyInt()))
        .thenReturn(Optional.of(claim(progress)))
        .thenReturn(Optional.empty());
  }

  private void stubEventWaiting(CustomerIssueDelayClosureProgress progress) {
    when(progressRepository.findByConfigId(CONFIG_ID)).thenReturn(Optional.of(progress));
    when(progressRepository.claimBySource(eq(SOURCE), anyString(), anyString(), anyInt()))
        .thenReturn(Optional.of(claim(progress)))
        .thenReturn(Optional.empty());
  }

  private CustomerIssueDelayClosureOrchestrator newInstance(Executor executor) {
    return new CustomerIssueDelayClosureOrchestrator(
        configService,
        writebackService,
        preWritebackSyncService,
        progressRepository,
        roundService,
        readinessService,
        properties,
        executor);
  }

  private SyncRunCompletionEvent terminalEvent(long runId) {
    return new SyncRunCompletionEvent(
        runId, CONFIG_ID, SOURCE, SyncRunType.TABLE_REFRESH, SyncRunStatus.SUCCESS, 7L);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig result = new GitlabSyncConfig();
    result.setId(CONFIG_ID);
    result.setSourceInstance(SOURCE);
    result.setSourceEnabled(true);
    return result;
  }

  private CustomerIssueDelayClosureProgressClaim claim(CustomerIssueDelayClosureProgress progress) {
    return new CustomerIssueDelayClosureProgressClaim(progress, "owner", "token");
  }

  private CustomerIssueDelayClosureProgress progress(CustomerIssueDelayClosureStage stage) {
    return progress(stage, null, 0L);
  }

  private CustomerIssueDelayClosureProgress progress(
      CustomerIssueDelayClosureStage stage, Long factVersion, long processedRevision) {
    return new CustomerIssueDelayClosureProgress(
        10L, SOURCE, CONFIG_ID, stage, processedRevision, processedRevision, 0L,
        MIRROR_RUN_ID, factVersion, factVersion == null ? null : processedRevision,
        null, 0, null, null, null, null, null);
  }

  private CustomerIssueDelayClosureProgress progress(Long factVersion, long processedRevision) {
    return progress(CustomerIssueDelayClosureStage.IDLE, factVersion, processedRevision);
  }
}
