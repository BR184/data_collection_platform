package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JavaNetGitlabDiffTransportTest {

  @Test
  void realHttpRoundTripSendsTokenAndParsesChangesResponse() throws Exception {
    AtomicReference<String> requestMethod = new AtomicReference<>();
    AtomicReference<String> privateToken = new AtomicReference<>();
    AtomicReference<String> requestPath = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requestMethod.set(exchange.getRequestMethod());
      privateToken.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
      requestPath.set(exchange.getRequestURI().toString());
      byte[] response = """
          {"overflow":false,"changes":[{"diff":"--- a/file\\n+++ b/file\\n-before\\n+after"}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      GitlabMirrorProperties properties = new GitlabMirrorProperties();
      properties.setCodeReviewMetricRequestTimeoutSeconds(5);
      GitlabMergeRequestDiffClient client = new GitlabMergeRequestDiffClient(
          new JavaNetGitlabDiffTransport(), new ObjectMapper(), properties);
      GitlabSyncConfig config = new GitlabSyncConfig();
      config.setWebBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      config.setApiToken("test-token");
      var claim = new CodeReviewMetricEnrichmentRepository.EnrichmentClaim(
          1L, "default", 9L, 99L, 7L, "cloudcad/crowncad", 1);

      CodeReviewDiffMetrics metrics = client.fetch(config, claim);

      assertThat(metrics.addedLines()).isEqualTo(1);
      assertThat(metrics.deletedLines()).isEqualTo(1);
      assertThat(requestMethod).hasValue("GET");
      assertThat(privateToken).hasValue("test-token");
      assertThat(requestPath).hasValue("/api/v4/projects/9/merge_requests/7/changes");
    } finally {
      server.stop(0);
    }
  }
}
