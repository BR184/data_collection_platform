package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * worker 发送前的执行复核。
 *
 * <p>覆盖配置唯一性、开关、授权修订与事实版本、当前标签读取失败、发送前租约和 HTTP 异常；
 * 等待或授权失效不得计成 HTTP 失败，也不得调用 GitLab。
 */
class CustomerIssueDelayLabelWritebackWorkerServiceTest {
  private static final long CONFIG_ID = 1L;
  private static final long MIRROR_RUN_ID = 77L;
  private static final long AUTHORIZATION_REVISION = 7L;
  private static final long FACT_VERSION = 42L;

  private GitlabConfigService configService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayLabelWritebackQueueService queueService;
  private CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private CustomerIssueDelayClosureProgressRepository progressRepository;
  private CustomerIssueDelaySourceReadinessService readinessService;
  private CustomerIssueDelayLabelWritebackWorkerService workerService;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    configService = mock(GitlabConfigService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    CustomerIssueDelayLabelWritebackPlanner planner = new CustomerIssueDelayLabelWritebackPlanner(writebackService);
    queueService = mock(CustomerIssueDelayLabelWritebackQueueService.class);
    preWritebackSyncService = mock(CustomerIssueDelayPreWritebackSyncService.class);
    progressRepository = mock(CustomerIssueDelayClosureProgressRepository.class);
    readinessService = mock(CustomerIssueDelaySourceReadinessService.class);
    workerService =
        new CustomerIssueDelayLabelWritebackWorkerService(
            properties,
            configService,
            writebackService,
            planner,
            queueService,
            preWritebackSyncService,
            progressRepository,
            readinessService);
    config = new GitlabSyncConfig();
    config.setId(CONFIG_ID);
    config.setSourceInstance("default");
    config.setSourceEnabled(true);
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.isSourceEnabled(config)).thenReturn(true);
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.preWritebackTables())
        .thenReturn(List.of("issues", "notes", "label_links", "labels"));
    when(progressRepository.findBySource("default"))
        .thenReturn(Optional.of(progress(AUTHORIZATION_REVISION, FACT_VERSION)));
    when(readinessService.validateRoundAuthorization(any(), any(), any()))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.AuthorizationCheck(true, null, FACT_VERSION));
    when(queueService.renewLease(any(), anyInt())).thenReturn(true);
  }

  @Test
  void shouldSkipWithoutCallingGitlabWhenCurrentLabelsAlreadyMatch() {
    CustomerIssueDelayLabelWritebackJob job = job(1, 0, 10);
    IssueFact fact = fact("响应已延期", true, false);
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of("响应已延期"), true, false))
        .thenReturn(new CustomerIssueDelayLabelWritebackService.LabelChange(List.of(), List.of()));

    workerService.process(job);

    verify(queueService).markSkipped(job, "GitLab 标签已与延期事实一致");
  }

  @Test
  void shouldRetryNetworkFailuresWithUnknownResultMessage() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(2, 1, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change))
        .thenThrow(new IOException("timeout"));

    workerService.process(job);

    verify(queueService).markRetry(job, "发送结果未知：timeout", null);
  }

  @Test
  void shouldMarkPermissionErrorsDead() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(3, 0, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error =
        new CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException(403, "forbidden");
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change)).thenThrow(error);

    workerService.process(job);

    verify(queueService).markDead(job, error.getMessage(), 403);
  }

  @Test
  void shouldRetryGitlabRateLimit() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(4, 0, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error =
        new CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException(429, "rate limited");
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change)).thenThrow(error);

    workerService.process(job);

    verify(queueService).markRetry(job, error.getMessage(), 429);
  }

  @Test
  void shouldSettleSucceededAfterHttpSuccess() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(12, 0, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change)).thenReturn(200);
    when(queueService.markSucceeded(job)).thenReturn(true);

    workerService.process(job);

    verify(queueService).markSucceeded(job);
  }

  @Test
  void shouldRetryServerErrors() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(13, 0, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException error =
        new CustomerIssueDelayLabelWritebackService.GitlabLabelWritebackException(502, "bad gateway");
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change)).thenThrow(error);

    workerService.process(job);

    verify(queueService).markRetry(job, error.getMessage(), 502);
  }

  @Test
  void shouldNotFallBackToDefaultConfigWhenSourceConfigIsMissing() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(5, 0, 10);
    when(configService.listConfigs()).thenReturn(List.of());

    workerService.process(job);

    verify(queueService).markSkipped(job, "数据源配置缺失或不唯一，未调用 GitLab");
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldNotSendWhenSourceConfigIsAmbiguous() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(6, 0, 10);
    GitlabSyncConfig other = new GitlabSyncConfig();
    other.setId(2L);
    other.setSourceInstance("default");
    when(configService.listConfigs()).thenReturn(List.of(config, other));

    workerService.process(job);

    verify(queueService).markSkipped(job, "数据源配置缺失或不唯一，未调用 GitLab");
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldSkipWhenSwitchIsOff() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(7, 0, 10);
    when(writebackService.isEnabled(config)).thenReturn(false);

    workerService.process(job);

    verify(queueService).markSkipped(job, "延期标签写回开关关闭，任务未调用 GitLab");
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldSkipWhenSourceDisabled() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(7, 0, 10);
    when(configService.isSourceEnabled(config)).thenReturn(false);
    workerService.process(job);
    verify(queueService).markSkipped(job, "数据源已停用，任务未调用 GitLab");
    verify(writebackService, never()).sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldSkipJobFromEarlierAuthorizationRevision() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(8, 0, 10);
    when(progressRepository.findBySource("default"))
        .thenReturn(Optional.of(progress(AUTHORIZATION_REVISION + 1, FACT_VERSION)));

    workerService.process(job);

    verify(queueService).markSkipped(eq(job), anyString());
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldWaitWhenRoundAuthorizationWasRevoked() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(14, 0, 10);
    when(progressRepository.findBySource("default"))
        .thenReturn(Optional.of(progress(null, null)));

    workerService.process(job);

    verify(queueService).defer(eq(job), anyString(), any());
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldDeferWhenFactVersionChangedAfterEnqueue() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(9, 0, 10);
    when(readinessService.validateRoundAuthorization(any(), any(), any()))
        .thenReturn(new CustomerIssueDelaySourceReadinessService.AuthorizationCheck(
            false, "相关事实版本已从 42 变为 43", 43L));

    workerService.process(job);

    verify(queueService).defer(eq(job), anyString(), any());
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldDeferInsteadOfTreatingLabelReadFailureAsEmptyLabels() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(10, 0, 10);
    when(queueService.loadCandidate("default", 325L, 1001L))
        .thenThrow(new DataAccessResourceFailureException("labels query failed"));

    workerService.process(job);

    verify(queueService).defer(eq(job), anyString(), any());
    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  @Test
  void shouldNotSendWhenLeaseWasLostBeforeHttp() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(11, 0, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(queueService.renewLease(job, 120)).thenReturn(false);

    workerService.process(job);

    verify(writebackService, never())
        .sendLabelUpdate(any(GitlabSyncConfig.class), any(), any(), any());
  }

  private CustomerIssueDelayClosureProgress progress(Long authorizationRevision, Long factVersion) {
    return new CustomerIssueDelayClosureProgress(
        10L,
        "default",
        CONFIG_ID,
        CustomerIssueDelayClosureStage.IDLE,
        1L,
        1L,
        1L,
        MIRROR_RUN_ID,
        factVersion,
        authorizationRevision,
        null,
        0,
        null,
        null,
        null,
        null,
        null);
  }

  private CustomerIssueDelayLabelWritebackJob job(long id, int attemptCount, int maxAttempts) {
    return new CustomerIssueDelayLabelWritebackJob(
        id,
        "default",
        CONFIG_ID,
        325L,
        1001L,
        9001L,
        true,
        false,
        "",
        List.of("响应已延期"),
        List.of(),
        "RUNNING",
        attemptCount,
        maxAttempts,
        null,
        "worker",
        null,
        null,
        null,
        MIRROR_RUN_ID,
        AUTHORIZATION_REVISION,
        FACT_VERSION,
        "token",
        1L,
        1L);
  }

  private IssueFact fact(String labels, boolean responseDelayed, boolean resolveDelayed) {
    IssueFact fact = new IssueFact();
    fact.setProjectId(325L);
    fact.setIssueIid(1001L);
    fact.setLabelNames(labels);
    fact.setResponseDelayed(responseDelayed);
    fact.setResolveDelayed(resolveDelayed);
    return fact;
  }
}
