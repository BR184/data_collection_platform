package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CustomerIssueDelayClosureScheduler {
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final CustomerIssueDelayLabelWritebackService delayLabelWritebackService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;
  private final CustomerIssueDelayLabelWritebackQueueService queueService;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public CustomerIssueDelayClosureScheduler(
      GitlabConfigService configService,
      FactBuildService factBuildService,
      CustomerIssueDelayLabelWritebackService delayLabelWritebackService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService,
      CustomerIssueDelayLabelWritebackQueueService queueService) {
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.delayLabelWritebackService = delayLabelWritebackService;
    this.preWritebackSyncService = preWritebackSyncService;
    this.queueService = queueService;
  }

  @Async
  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.customer-issue-delay-check-delay-ms:3600000}")
  public void refreshCustomerIssueDelayFacts() {
    if (!running.compareAndSet(false, true)) {
      log.info("Customer issue delay closure refresh skipped because previous run is still active");
      return;
    }
    try {
      refreshCustomerIssueDelayFactsInternal();
    } finally {
      running.set(false);
    }
  }

  private void refreshCustomerIssueDelayFactsInternal() {
    List<GitlabSyncConfig> configs = configService.listConfigs().stream()
        .filter(config -> config.getId() != null)
        .filter(config -> Boolean.TRUE.equals(config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled()))
        .toList();
    for (GitlabSyncConfig config : configs) {
      try {
        boolean writebackEnabled = delayLabelWritebackService.isEnabled(config);
        if (writebackEnabled) {
          CustomerIssueDelayPreWritebackSyncService.PreWritebackSyncResult preSyncResult =
              preWritebackSyncService.refreshBeforeWriteback(config);
          if (!preSyncResult.success()) {
            log.warn(
                "Customer issue delay closure refresh skipped because pre-writeback sync was not successful, sourceInstance={}",
                GitlabSourceInstanceSupport.sourceInstanceOf(config));
            continue;
          }
        }
        factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
        if (writebackEnabled) {
          int enqueued = queueService.enqueueCandidates(config);
          log.info(
              "Customer issue delay label writeback jobs enqueued, sourceInstance={}, count={}",
              GitlabSourceInstanceSupport.sourceInstanceOf(config),
              enqueued);
        }
      } catch (RuntimeException error) {
        log.warn(
            "Customer issue delay closure refresh failed, sourceInstance={}",
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            error);
      }
    }
  }
}
