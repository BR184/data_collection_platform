package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
class CodeReviewMetricEnrichmentService {
  private final CodeReviewMetricEnrichmentRepository repository;
  private final GitlabMergeRequestDiffClient diffClient;
  private final GitlabConfigService configService;
  private final FactBuildService factBuildService;
  private final GitlabMirrorProperties properties;
  private final Executor coordinatorExecutor;
  private final Executor workerExecutor;
  private final AtomicBoolean running = new AtomicBoolean(false);

  CodeReviewMetricEnrichmentService(
      CodeReviewMetricEnrichmentRepository repository,
      GitlabMergeRequestDiffClient diffClient,
      GitlabConfigService configService,
      FactBuildService factBuildService,
      GitlabMirrorProperties properties,
      @Qualifier("codeReviewMetricCoordinator") Executor coordinatorExecutor,
      @Qualifier("codeReviewMetricWorkers") Executor workerExecutor) {
    this.repository = repository;
    this.diffClient = diffClient;
    this.configService = configService;
    this.factBuildService = factBuildService;
    this.properties = properties;
    this.coordinatorExecutor = coordinatorExecutor;
    this.workerExecutor = workerExecutor;
  }

  @Scheduled(
      fixedDelayString = "${platform.gitlab-mirror.code-review-metric-enrichment-delay-ms:2000}",
      initialDelayString = "${platform.gitlab-mirror.code-review-metric-enrichment-initial-delay-ms:30000}")
  void schedule() {
    if (!properties.isSchedulerEnabled()
        || !properties.isCodeReviewMetricEnrichmentEnabled()
        || !running.compareAndSet(false, true)) {
      return;
    }
    coordinatorExecutor.execute(() -> {
      try {
        runOnce();
      } catch (RuntimeException error) {
        log.warn("代码评审 GitLab diff 指标补齐批次失败", error);
      } finally {
        running.set(false);
      }
    });
  }

  void runOnce() {
    GitlabSyncConfig config = configService.getConfig();
    if (!ready(config) || !repository.sourceTablesAvailable()) {
      return;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    int scanSize = Math.max(1, properties.getCodeReviewMetricHistoricalScanSize());
    repository.enqueueHistorical(sourceInstance, scanSize);
    repository.enqueueNextCompletedRun(sourceInstance, scanSize);
    publishEnriched(sourceInstance);

    int batchSize = Math.max(1, properties.getCodeReviewMetricBatchSize());
    List<CodeReviewMetricEnrichmentRepository.EnrichmentClaim> claims =
        repository.claim(sourceInstance, batchSize);
    List<CompletableFuture<Void>> tasks = claims.stream()
        .map(claim -> CompletableFuture.runAsync(
            () -> enrich(config, claim), workerExecutor))
        .toList();
    CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
    publishEnriched(sourceInstance);
  }

  private void enrich(
      GitlabSyncConfig config,
      CodeReviewMetricEnrichmentRepository.EnrichmentClaim claim) {
    try {
      CodeReviewDiffMetrics metrics = diffClient.fetch(config, claim);
      repository.markEnriched(
          claim.id(),
          metrics.addedLines(),
          metrics.deletedLines(),
          metrics.rawPayload(),
          metrics.sourceUri().toString());
    } catch (GitlabDiffFetchException error) {
      repository.markFailed(claim.id(), error.retryable(), error.getMessage());
    } catch (RuntimeException error) {
      repository.markFailed(claim.id(), true, rootMessage(error));
    }
  }

  private void publishEnriched(String sourceInstance) {
    int batchSize = Math.max(1, properties.getCodeReviewMetricBatchSize());
    List<CodeReviewMetricEnrichmentRepository.EnrichedTarget> targets =
        repository.loadEnrichedTargets(sourceInstance, batchSize);
    if (targets.isEmpty()) {
      return;
    }
    boolean published = factBuildService.publishEnrichedMergeRequestFacts(
        sourceInstance,
        targets.stream()
            .map(target -> new FactRefreshImpactScopeService.Target(
                target.projectId(), target.mergeRequestIid()))
            .distinct()
            .toList());
    if (published) {
      repository.markPublished(targets.stream().map(
          CodeReviewMetricEnrichmentRepository.EnrichedTarget::id).toList());
    }
  }

  private boolean ready(GitlabSyncConfig config) {
    return config != null
        && config.isEnabled()
        && !Boolean.FALSE.equals(config.getSourceEnabled())
        && StringUtils.hasText(config.getWebBaseUrl())
        && StringUtils.hasText(config.getApiToken());
  }

  private String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return StringUtils.hasText(current.getMessage())
        ? current.getMessage()
        : "GitLab diff 指标补齐失败";
  }
}
