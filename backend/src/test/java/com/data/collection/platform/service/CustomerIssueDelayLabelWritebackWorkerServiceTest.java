package com.data.collection.platform.service;

import static org.mockito.Mockito.mock;
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

class CustomerIssueDelayLabelWritebackWorkerServiceTest {
  private GitlabConfigService configService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayLabelWritebackQueueService queueService;
  private CustomerIssueDelayLabelWritebackWorkerService workerService;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    configService = mock(GitlabConfigService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    CustomerIssueDelayLabelWritebackPlanner planner = new CustomerIssueDelayLabelWritebackPlanner(writebackService);
    queueService = mock(CustomerIssueDelayLabelWritebackQueueService.class);
    workerService =
        new CustomerIssueDelayLabelWritebackWorkerService(
            properties,
            configService,
            writebackService,
            planner,
            queueService);
    config = new GitlabSyncConfig();
    config.setSourceInstance("default");
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(configService.getConfig()).thenReturn(config);
    when(writebackService.isEnabled(config)).thenReturn(true);
  }

  @Test
  void shouldSkipWithoutCallingGitlabWhenCurrentLabelsAlreadyMatch() {
    CustomerIssueDelayLabelWritebackJob job = job(1, 0, 10);
    IssueFact fact = fact("响应已延期", true, false);
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of("响应已延期"), true, false))
        .thenReturn(new CustomerIssueDelayLabelWritebackService.LabelChange(List.of(), List.of()));

    workerService.process(job);

    verify(queueService).markSkipped(1L, "GitLab 标签已与延期事实一致");
  }

  @Test
  void shouldRetryNetworkFailures() throws Exception {
    CustomerIssueDelayLabelWritebackJob job = job(2, 1, 10);
    IssueFact fact = fact("", true, false);
    CustomerIssueDelayLabelWritebackService.LabelChange change =
        new CustomerIssueDelayLabelWritebackService.LabelChange(List.of("响应已延期"), List.of());
    when(queueService.loadCandidate("default", 325L, 1001L)).thenReturn(Optional.of(fact));
    when(writebackService.delayLabelChange(List.of(), true, false)).thenReturn(change);
    when(writebackService.sendLabelUpdate(config, 325L, 1001L, change)).thenThrow(new IOException("timeout"));

    workerService.process(job);

    verify(queueService).markRetry(2L, 1, 10, "timeout", null);
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

    verify(queueService).markDead(3L, error.getMessage(), 403);
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

    verify(queueService).markRetry(4L, 0, 10, error.getMessage(), 429);
  }

  private CustomerIssueDelayLabelWritebackJob job(long id, int attemptCount, int maxAttempts) {
    return new CustomerIssueDelayLabelWritebackJob(
        id,
        "default",
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
        null);
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
