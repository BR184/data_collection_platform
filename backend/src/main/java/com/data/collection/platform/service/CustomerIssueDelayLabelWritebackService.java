package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFact;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackService {
  static final String RESPONSE_DELAY_LABEL = "响应已延期";
  static final String RESOLVE_DELAY_LABEL = "解决已延期";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  public CustomerIssueDelayLabelWritebackService() {
    this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
  }

  CustomerIssueDelayLabelWritebackService(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public void syncLabels(GitlabSyncConfig config, IssueFact fact) {
    if (!isEnabled(config) || fact == null || fact.getProjectId() == null || fact.getIssueIid() == null) {
      return;
    }
    List<String> currentLabels = parseLabels(fact.getLabelNames());
    List<String> nextLabels =
        desiredDelayLabels(currentLabels, Boolean.TRUE.equals(fact.getResponseDelayed()), Boolean.TRUE.equals(fact.getResolveDelayed()));
    if (sameLabels(currentLabels, nextLabels)) {
      return;
    }
    try {
      sendLabelUpdate(config, fact.getProjectId(), fact.getIssueIid(), nextLabels);
      log.info(
          "Customer issue delay labels written back, sourceInstance={}, projectId={}, issueIid={}, labels={}",
          fact.getSourceInstance(),
          fact.getProjectId(),
          fact.getIssueIid(),
          nextLabels);
    } catch (IOException error) {
      log.warn(
          "Customer issue delay label writeback failed, projectId={}, issueIid={}",
          fact.getProjectId(),
          fact.getIssueIid(),
          error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      log.warn(
          "Customer issue delay label writeback interrupted, projectId={}, issueIid={}",
          fact.getProjectId(),
          fact.getIssueIid(),
          error);
    }
  }

  List<String> desiredDelayLabels(List<String> currentLabels, boolean responseDelayed, boolean resolveDelayed) {
    List<String> nextLabels = new ArrayList<>();
    for (String label : currentLabels == null ? List.<String>of() : currentLabels) {
      if (!StringUtils.hasText(label)) {
        continue;
      }
      String trimmed = label.trim();
      if (!RESPONSE_DELAY_LABEL.equals(trimmed) && !RESOLVE_DELAY_LABEL.equals(trimmed)) {
        nextLabels.add(trimmed);
      }
    }
    if (responseDelayed) {
      nextLabels.add(RESPONSE_DELAY_LABEL);
    }
    if (resolveDelayed) {
      nextLabels.add(RESOLVE_DELAY_LABEL);
    }
    return List.copyOf(new LinkedHashSet<>(nextLabels));
  }

  private boolean isEnabled(GitlabSyncConfig config) {
    return config != null
        && Boolean.TRUE.equals(config.getDelayLabelWritebackEnabled())
        && StringUtils.hasText(config.getWebBaseUrl())
        && StringUtils.hasText(config.getApiToken());
  }

  private List<String> parseLabels(String labelNames) {
    if (!StringUtils.hasText(labelNames)) {
      return List.of();
    }
    List<String> labels = new ArrayList<>();
    for (String part : labelNames.split(",")) {
      if (StringUtils.hasText(part)) {
        labels.add(part.trim());
      }
    }
    return labels;
  }

  private boolean sameLabels(List<String> left, List<String> right) {
    return new LinkedHashSet<>(left == null ? List.<String>of() : left)
        .equals(new LinkedHashSet<>(right == null ? List.<String>of() : right));
  }

  private void sendLabelUpdate(GitlabSyncConfig config, Long projectId, Long issueIid, List<String> labels)
      throws IOException, InterruptedException {
    String baseUrl = stripTrailingSlash(config.getWebBaseUrl());
    URI uri = URI.create(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + issueIid);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("PRIVATE-TOKEN", config.getApiToken())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .PUT(HttpRequest.BodyPublishers.ofString(formBody(labels), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("GitLab label update failed: HTTP " + response.statusCode() + " " + response.body());
    }
  }

  private String formBody(List<String> labels) {
    StringBuilder body = new StringBuilder();
    for (String label : labels == null ? List.<String>of() : labels) {
      if (!StringUtils.hasText(label)) {
        continue;
      }
      if (body.length() > 0) {
        body.append('&');
      }
      body.append("labels=").append(URLEncoder.encode(label.trim(), StandardCharsets.UTF_8));
    }
    return body.toString();
  }

  private String stripTrailingSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
