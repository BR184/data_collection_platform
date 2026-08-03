package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeReviewMetricEnrichmentServiceTest {
  private final CodeReviewMetricEnrichmentRepository repository =
      mock(CodeReviewMetricEnrichmentRepository.class);
  private final GitlabMergeRequestDiffClient client = mock(GitlabMergeRequestDiffClient.class);
  private final GitlabConfigService configService = mock(GitlabConfigService.class);
  private final FactBuildService factBuildService = mock(FactBuildService.class);
  private final GitlabMirrorProperties properties = new GitlabMirrorProperties();
  private final Executor directExecutor = Runnable::run;
  private CodeReviewMetricEnrichmentService service;

  @BeforeEach
  void setUp() {
    properties.setCodeReviewMetricBatchSize(10);
    properties.setCodeReviewMetricHistoricalScanSize(20);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSourceInstance("default");
    config.setWebBaseUrl("http://gitlab.local");
    config.setApiToken("token");
    when(configService.getConfig()).thenReturn(config);
    when(repository.sourceTablesAvailable()).thenReturn(true);
    service = new CodeReviewMetricEnrichmentService(
        repository,
        client,
        configService,
        factBuildService,
        properties,
        directExecutor,
        directExecutor);
  }

  @Test
  void successfulFetchPersistsMetricsThenPublishesTargetedFact() {
    var claim = new CodeReviewMetricEnrichmentRepository.EnrichmentClaim(
        8L, "default", 9L, 99L, 7L, "group/project", 1);
    var enriched = new CodeReviewMetricEnrichmentRepository.EnrichedTarget(
        8L, "default", 9L, 99L, 7L);
    when(repository.claim("default", 10)).thenReturn(List.of(claim));
    when(client.fetch(any(), eq(claim)))
        .thenReturn(new CodeReviewDiffMetrics(120, 30, URI.create("http://gitlab/diffs"), "{}"));
    when(repository.loadEnrichedTargets("default", 10))
        .thenReturn(List.of(), List.of(enriched));
    when(factBuildService.publishEnrichedMergeRequestFacts(eq("default"), anyList()))
        .thenReturn(true);

    service.runOnce();

    verify(repository).markEnriched(8L, 120, 30, "{}", "http://gitlab/diffs");
    verify(factBuildService).publishEnrichedMergeRequestFacts(
        "default", List.of(99L));
    verify(repository).markPublished(List.of(8L));
  }

  @Test
  void retryableFailureOnlyUpdatesRetryState() {
    var claim = new CodeReviewMetricEnrichmentRepository.EnrichmentClaim(
        8L, "default", 9L, 99L, 7L, "group/project", 1);
    when(repository.claim("default", 10)).thenReturn(List.of(claim));
    when(client.fetch(any(), eq(claim)))
        .thenThrow(new GitlabDiffFetchException("temporary", true));

    service.runOnce();

    verify(repository).markFailed(8L, true, "temporary");
    verify(repository, never()).markEnriched(any(), anyInt(), anyInt(), any(), any());
  }

  @Test
  void permanentFailureDoesNotOverwritePreviouslyValidMetrics() {
    var claim = new CodeReviewMetricEnrichmentRepository.EnrichmentClaim(
        8L, "default", 9L, 99L, 7L, "group/project", 1);
    when(repository.claim("default", 10)).thenReturn(List.of(claim));
    when(client.fetch(any(), eq(claim)))
        .thenThrow(new GitlabDiffFetchException("invalid response", false));

    service.runOnce();

    verify(repository).markFailed(8L, false, "invalid response");
    verify(repository, never()).markEnriched(any(), anyInt(), anyInt(), any(), any());
  }

  @Test
  void factBuildBusyKeepsEnrichedRowsPendingPublication() {
    var enriched = new CodeReviewMetricEnrichmentRepository.EnrichedTarget(
        8L, "default", 9L, 99L, 7L);
    when(repository.loadEnrichedTargets("default", 10)).thenReturn(List.of(enriched));
    when(factBuildService.publishEnrichedMergeRequestFacts(eq("default"), anyList()))
        .thenReturn(false);

    service.runOnce();

    verify(repository, never()).markPublished(anyList());
  }

  @Test
  void disabledGlobalSchedulerDoesNotStartEnrichment() {
    properties.setSchedulerEnabled(false);

    service.schedule();

    verify(configService, never()).getConfig();
  }
}
