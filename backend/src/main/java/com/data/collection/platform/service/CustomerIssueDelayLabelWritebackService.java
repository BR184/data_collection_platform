package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDelayLabelWritebackService {
  static final String RESPONSE_DELAY_LABEL = "响应已延期";
  static final String RESOLVE_DELAY_LABEL = "解决已延期";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;
  private final boolean apiWritebackEnabled;

  @Autowired
  public CustomerIssueDelayLabelWritebackService(
      @Value("${platform.gitlab-mirror.delay-label-writeback-api-enabled:false}") boolean apiWritebackEnabled) {
    this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(), apiWritebackEnabled);
  }

  CustomerIssueDelayLabelWritebackService(HttpClient httpClient, boolean apiWritebackEnabled) {
    this.httpClient = httpClient;
    this.apiWritebackEnabled = apiWritebackEnabled;
  }

  LabelChange delayLabelChange(List<String> currentLabels, boolean responseDelayed, boolean resolveDelayed) {
    LinkedHashSet<String> currentDelayLabels = new LinkedHashSet<>();
    for (String label : currentLabels == null ? List.<String>of() : currentLabels) {
      if (!StringUtils.hasText(label)) {
        continue;
      }
      String trimmed = label.trim();
      if (RESPONSE_DELAY_LABEL.equals(trimmed) || RESOLVE_DELAY_LABEL.equals(trimmed)) {
        currentDelayLabels.add(trimmed);
      }
    }
    LinkedHashSet<String> desiredDelayLabels = new LinkedHashSet<>();
    if (responseDelayed) {
      desiredDelayLabels.add(RESPONSE_DELAY_LABEL);
    }
    if (resolveDelayed) {
      desiredDelayLabels.add(RESOLVE_DELAY_LABEL);
    }
    LinkedHashSet<String> addLabels = new LinkedHashSet<>(desiredDelayLabels);
    addLabels.removeAll(currentDelayLabels);
    LinkedHashSet<String> removeLabels = new LinkedHashSet<>(currentDelayLabels);
    removeLabels.removeAll(desiredDelayLabels);
    return new LabelChange(List.copyOf(addLabels), List.copyOf(removeLabels));
  }

  boolean isEnabled(GitlabSyncConfig config) {
    return apiWritebackEnabled
        && config != null
        && Boolean.TRUE.equals(config.getDelayLabelWritebackEnabled())
        && StringUtils.hasText(config.getWebBaseUrl())
        && StringUtils.hasText(config.getApiToken());
  }

  int sendLabelUpdate(GitlabSyncConfig config, Long projectId, Long issueIid, LabelChange change)
      throws IOException, InterruptedException, GitlabLabelWritebackException {
    String baseUrl = stripTrailingSlash(config.getWebBaseUrl());
    URI uri = URI.create(baseUrl + "/api/v4/projects/" + projectId + "/issues/" + issueIid);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("PRIVATE-TOKEN", config.getApiToken())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .PUT(HttpRequest.BodyPublishers.ofString(formBody(change), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new GitlabLabelWritebackException(response.statusCode(), response.body());
    }
    return response.statusCode();
  }

  String formBody(LabelChange change) {
    StringBuilder body = new StringBuilder();
    appendLabelParameter(body, "add_labels", change == null ? List.of() : change.addLabels());
    appendLabelParameter(body, "remove_labels", change == null ? List.of() : change.removeLabels());
    return body.toString();
  }

  private void appendLabelParameter(StringBuilder body, String name, List<String> labels) {
    List<String> normalized =
        List.copyOf(new LinkedHashSet<>(
            (labels == null ? List.<String>of() : labels).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList()));
    if (normalized.isEmpty()) {
      return;
    }
    if (body.length() > 0) {
      body.append('&');
    }
    body.append(name).append('=');
    StringBuilder value = new StringBuilder();
    for (String label : normalized) {
      if (!StringUtils.hasText(label)) {
        continue;
      }
      if (value.length() > 0) {
        value.append(',');
      }
      value.append(label.trim());
    }
    body.append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
  }

  private String stripTrailingSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  record LabelChange(List<String> addLabels, List<String> removeLabels) {
    LabelChange {
      addLabels = List.copyOf(new LinkedHashSet<>(addLabels == null ? List.of() : addLabels));
      removeLabels = List.copyOf(new LinkedHashSet<>(removeLabels == null ? List.of() : removeLabels));
    }

    boolean isEmpty() {
      return addLabels.isEmpty() && removeLabels.isEmpty();
    }
  }

  static class GitlabLabelWritebackException extends Exception {
    private final int httpStatus;

    GitlabLabelWritebackException(int httpStatus, String body) {
      super("GitLab label update failed: HTTP " + httpStatus + " " + (body == null ? "" : body));
      this.httpStatus = httpStatus;
    }

    int httpStatus() {
      return httpStatus;
    }
  }
}
