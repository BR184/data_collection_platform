package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class GitlabMergeRequestDiffClientTest {

  @Test
  void successfulResponseCountsAddedAndRemovedDiffLines() {
    GitlabDiffTransport transport = (uri, token, timeout) ->
        new GitlabDiffTransport.Response(200, """
            {"overflow":false,"changes":[
              {"old_path":"a.txt","new_path":"a.txt","diff":"--- a/a.txt\\n+++ b/a.txt\\n-old\\n+new\\n context\\n++++content-starting-with-three-plus"},
              {"old_path":"b.txt","new_path":"b.txt","diff":"@@ -0,0 +1,2 @@\\n+one\\n+two"}
            ]}
            """);
    GitlabMergeRequestDiffClient client = client(transport);

    CodeReviewMetricEnrichmentRepository.EnrichmentClaim claim = claim("group/sub/project");
    CodeReviewDiffMetrics metrics = client.fetch(config(), claim);

    assertThat(metrics.addedLines()).isEqualTo(4);
    assertThat(metrics.deletedLines()).isEqualTo(1);
    assertThat(metrics.rawPayload()).isEqualTo("{\"added_lines\":4,\"removed_lines\":1}");
    assertThat(metrics.sourceUri()).isEqualTo(
        URI.create("http://gitlab.local/api/v4/projects/9/merge_requests/7/changes"));
  }

  @Test
  void emptyAndBinaryDiffsProduceZeroCounts() {
    GitlabDiffTransport transport = (uri, token, timeout) ->
        new GitlabDiffTransport.Response(
            200, "{\"overflow\":false,\"changes\":[{\"diff\":\"\"},{\"diff\":null}]}");

    CodeReviewDiffMetrics metrics = client(transport).fetch(config(), claim("group/project"));

    assertThat(metrics.addedLines()).isZero();
    assertThat(metrics.deletedLines()).isZero();
  }

  @Test
  void serverFailureIsRetryable() {
    GitlabDiffTransport transport = (uri, token, timeout) ->
        new GitlabDiffTransport.Response(503, "temporarily unavailable");

    assertThatThrownBy(() -> client(transport).fetch(config(), claim("group/project")))
        .isInstanceOf(GitlabDiffFetchException.class)
        .satisfies(error -> assertThat(((GitlabDiffFetchException) error).retryable()).isTrue());
  }

  @Test
  void malformedSuccessfulResponseIsPermanent() {
    GitlabDiffTransport transport = (uri, token, timeout) ->
        new GitlabDiffTransport.Response(200, "{\"diffs\":[]}");

    assertThatThrownBy(() -> client(transport).fetch(config(), claim("group/project")))
        .isInstanceOf(GitlabDiffFetchException.class)
        .satisfies(error -> assertThat(((GitlabDiffFetchException) error).retryable()).isFalse());
  }

  @Test
  void truncatedSuccessfulResponseIsPermanentInsteadOfPersistingPartialCounts() {
    GitlabDiffTransport transport = (uri, token, timeout) ->
        new GitlabDiffTransport.Response(
            200, "{\"overflow\":true,\"changes\":[{\"diff\":\"+partial\"}]}");

    assertThatThrownBy(() -> client(transport).fetch(config(), claim("group/project")))
        .isInstanceOf(GitlabDiffFetchException.class)
        .hasMessageContaining("已截断")
        .satisfies(error -> assertThat(((GitlabDiffFetchException) error).retryable()).isFalse());
  }

  @Test
  void malformedGitlabUrlIsPermanentConfigurationFailure() {
    GitlabSyncConfig malformed = config();
    malformed.setWebBaseUrl("http://gitlab.local/invalid path");

    assertThatThrownBy(() -> client((uri, token, timeout) -> null)
            .fetch(malformed, claim("group/project")))
        .isInstanceOf(GitlabDiffFetchException.class)
        .hasMessage("GitLab 地址格式无效")
        .satisfies(error -> assertThat(((GitlabDiffFetchException) error).retryable()).isFalse());
  }

  private GitlabMergeRequestDiffClient client(GitlabDiffTransport transport) {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setCodeReviewMetricRequestTimeoutSeconds(5);
    return new GitlabMergeRequestDiffClient(transport, new ObjectMapper(), properties);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setWebBaseUrl("http://gitlab.local/");
    config.setApiToken("token");
    return config;
  }

  private CodeReviewMetricEnrichmentRepository.EnrichmentClaim claim(String path) {
    return new CodeReviewMetricEnrichmentRepository.EnrichmentClaim(
        1L, "default", 9L, 99L, 7L, path, 1);
  }
}
