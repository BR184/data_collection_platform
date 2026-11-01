package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 本轮完整评估结束时的旧任务结算边界。 */
class CustomerIssueDelayClosureRoundServiceTest {
  private static final String SOURCE = "default";
  private static final long CONFIG_ID = 1L;
  private static final long FACT_VERSION = 42L;
  private static final long AUTHORIZATION_REVISION = 7L;

  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayLabelWritebackQueueService queueService;
  private CustomerIssueDelaySourceReadinessService readinessService;
  private CustomerIssueDelayClosureProgressRepository progressRepository;
  private CustomerIssueDelayClosureRoundService roundService;
  private GitlabSyncConfig config;
  private CustomerIssueDelayClosureProgressClaim claim;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    preWritebackSyncService = mock(CustomerIssueDelayPreWritebackSyncService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    queueService = mock(CustomerIssueDelayLabelWritebackQueueService.class);
    readinessService = mock(CustomerIssueDelaySourceReadinessService.class);
    progressRepository = mock(CustomerIssueDelayClosureProgressRepository.class);
    roundService =
        new CustomerIssueDelayClosureRoundService(
            configService,
            factBuildService,
            preWritebackSyncService,
            writebackService,
            queueService,
            readinessService,
            progressRepository);
    config = new GitlabSyncConfig();
    config.setId(CONFIG_ID);
    config.setSourceInstance(SOURCE);
    config.setSourceEnabled(true);
    claim = new CustomerIssueDelayClosureProgressClaim(
        progress(CustomerIssueDelayClosureStage.ENQUEUE), "owner", "token");
    when(configService.getConfigById(CONFIG_ID)).thenReturn(config);
    when(configService.isSourceEnabled(config)).thenReturn(true);
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.preWritebackTables()).thenReturn(List.of("issues"));
    when(readinessService.assess(any(), any(), any())).thenReturn(
        new CustomerIssueDelaySourceReadinessService.Readiness(
            true, null, FACT_VERSION, 0L));
  }

  @Test
  void completedFinalPageSettlesObsoleteJobsBeforeFinishingTheRound() {
    when(queueService.enqueuePage(any(), anyLong(), anyLong(), any(), any(), anyInt()))
        .thenReturn(new CustomerIssueDelayLabelWritebackQueueService.PageResult(3, 900L, false));
    CustomerIssueDelayClosureProgress completed =
        progress(CustomerIssueDelayClosureStage.IDLE);
    when(progressRepository.completeRound(claim)).thenReturn(Optional.of(completed));

    CustomerIssueDelayClosureRoundService.EnqueueResult result =
        roundService.enqueuePage(claim, CONFIG_ID, FACT_VERSION, 100);

    assertThat(result.status())
        .isEqualTo(CustomerIssueDelayClosureRoundService.EnqueueStatus.COMPLETED);
    InOrder order = inOrder(queueService, progressRepository);
    order.verify(queueService).settleObsoletePendingJobs(SOURCE, AUTHORIZATION_REVISION);
    order.verify(progressRepository).completeRound(claim);
  }

  @Test
  void advancedPageMustNotSettleJobsBeforeTheRoundIsComplete() {
    CustomerIssueDelayClosureProgress advanced = progress(CustomerIssueDelayClosureStage.ENQUEUE);
    when(queueService.enqueuePage(any(), anyLong(), anyLong(), any(), any(), anyInt()))
        .thenReturn(new CustomerIssueDelayLabelWritebackQueueService.PageResult(100, 900L, true));
    when(progressRepository.advanceCursor(claim, 900L)).thenReturn(Optional.of(advanced));

    CustomerIssueDelayClosureRoundService.EnqueueResult result =
        roundService.enqueuePage(claim, CONFIG_ID, FACT_VERSION, 100);

    assertThat(result.status())
        .isEqualTo(CustomerIssueDelayClosureRoundService.EnqueueStatus.ADVANCED);
    verify(queueService, never()).settleObsoletePendingJobs(anyString(), anyLong());
  }

  private CustomerIssueDelayClosureProgress progress(CustomerIssueDelayClosureStage stage) {
    return new CustomerIssueDelayClosureProgress(
        10L,
        SOURCE,
        CONFIG_ID,
        stage,
        AUTHORIZATION_REVISION,
        AUTHORIZATION_REVISION,
        AUTHORIZATION_REVISION,
        77L,
        FACT_VERSION,
        AUTHORIZATION_REVISION,
        900L,
        0,
        null,
        null,
        null,
        null,
        null);
  }
}
