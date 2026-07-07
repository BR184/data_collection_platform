package com.data.collection.platform.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerIssueDelayClosureSchedulerTest {
  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private CustomerIssueDelayLabelWritebackService writebackService;
  private CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private CustomerIssueDelayLabelWritebackQueueService queueService;
  private CustomerIssueDelayClosureScheduler scheduler;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    writebackService = mock(CustomerIssueDelayLabelWritebackService.class);
    preWritebackSyncService = mock(CustomerIssueDelayPreWritebackSyncService.class);
    queueService = mock(CustomerIssueDelayLabelWritebackQueueService.class);
    scheduler =
        new CustomerIssueDelayClosureScheduler(
            configService,
            factBuildService,
            writebackService,
            preWritebackSyncService,
            queueService);
    config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceEnabled(true);
    config.setSourceInstance("default");
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(factBuildService.refreshCustomerIssueDelayFactsForConfig(config))
        .thenReturn(new FactBuildResponse("customer-issue-delay", false, 1, "ok"));
  }

  @Test
  void shouldRefreshFactsButNotEnqueueWhenWritebackSwitchIsOff() {
    when(writebackService.isEnabled(config)).thenReturn(false);

    scheduler.refreshCustomerIssueDelayFacts();

    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(preWritebackSyncService, never()).refreshBeforeWriteback(config);
    verify(queueService, never()).enqueueCandidates(config);
  }

  @Test
  void shouldNotEnqueueWhenPreSyncFails() {
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.refreshBeforeWriteback(config))
        .thenReturn(new CustomerIssueDelayPreWritebackSyncService.PreWritebackSyncResult(false, false));

    scheduler.refreshCustomerIssueDelayFacts();

    verify(factBuildService, never()).refreshCustomerIssueDelayFactsForConfig(config);
    verify(queueService, never()).enqueueCandidates(config);
  }

  @Test
  void shouldEnqueueAfterSuccessfulFactRefreshWhenWritebackIsEnabled() {
    when(writebackService.isEnabled(config)).thenReturn(true);
    when(preWritebackSyncService.refreshBeforeWriteback(config))
        .thenReturn(new CustomerIssueDelayPreWritebackSyncService.PreWritebackSyncResult(true, true));

    scheduler.refreshCustomerIssueDelayFacts();

    verify(factBuildService).refreshCustomerIssueDelayFactsForConfig(config);
    verify(queueService).enqueueCandidates(config);
  }
}
