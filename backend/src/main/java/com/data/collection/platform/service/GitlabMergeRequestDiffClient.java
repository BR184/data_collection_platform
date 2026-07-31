package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
class GitlabMergeRequestDiffClient {
  private final GitlabDiffTransport transport;
  private final ObjectMapper objectMapper;
  private final GitlabMirrorProperties properties;

  GitlabMergeRequestDiffClient(
      GitlabDiffTransport transport,
      ObjectMapper objectMapper,
      GitlabMirrorProperties properties) {
    this.transport = transport;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  CodeReviewDiffMetrics fetch(
      GitlabSyncConfig config,
      CodeReviewMetricEnrichmentRepository.EnrichmentClaim claim) {
    URI uri = diffUri(config, claim);
    GitlabDiffTransport.Response response = transport.get(
        uri,
        config.getApiToken(),
        Duration.ofSeconds(Math.max(1, properties.getCodeReviewMetricRequestTimeoutSeconds())));
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      boolean retryable = status == 408 || status == 429 || status >= 500;
      throw new GitlabDiffFetchException("GitLab diff 接口返回 HTTP " + status, retryable);
    }
    try {
      JsonNode payload = objectMapper.readTree(response.body());
      LineCounts counts = countChangedLines(payload);
      return new CodeReviewDiffMetrics(
          counts.addedLines(),
          counts.deletedLines(),
          uri,
          objectMapper.createObjectNode()
              .put("added_lines", counts.addedLines())
              .put("removed_lines", counts.deletedLines())
              .toString());
    } catch (GitlabDiffFetchException error) {
      throw error;
    } catch (Exception error) {
      throw new GitlabDiffFetchException("GitLab diff 响应无法解析", false);
    }
  }

  private LineCounts countChangedLines(JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new GitlabDiffFetchException("GitLab changes 响应不是 JSON 对象", false);
    }
    if (payload.path("overflow").asBoolean(false)) {
      throw new GitlabDiffFetchException("GitLab changes 响应已截断，无法生成准确行数", false);
    }
    JsonNode changes = payload.get("changes");
    if (changes == null || !changes.isArray()) {
      throw new GitlabDiffFetchException("GitLab changes 响应缺少 changes 数组", false);
    }
    long addedLines = 0L;
    long deletedLines = 0L;
    for (JsonNode change : changes) {
      if (change.path("too_large").asBoolean(false)
          || change.path("collapsed").asBoolean(false)) {
        throw new GitlabDiffFetchException("GitLab changes 文件内容已截断，无法生成准确行数", false);
      }
      String diff = change.path("diff").asText("");
      for (String line : diff.split("\\R", -1)) {
        if (line.startsWith("+") && !line.startsWith("+++ ")) {
          addedLines++;
        } else if (line.startsWith("-") && !line.startsWith("--- ")) {
          deletedLines++;
        }
      }
    }
    if (addedLines > Integer.MAX_VALUE || deletedLines > Integer.MAX_VALUE) {
      throw new GitlabDiffFetchException("GitLab changes 行数超过平台可存储范围", false);
    }
    return new LineCounts((int) addedLines, (int) deletedLines);
  }

  private URI diffUri(
      GitlabSyncConfig config,
      CodeReviewMetricEnrichmentRepository.EnrichmentClaim claim) {
    if (!StringUtils.hasText(config.getWebBaseUrl())
        || claim.projectId() == null
        || claim.projectId() <= 0
        || claim.mergeRequestIid() == null
        || claim.mergeRequestIid() <= 0) {
      throw new GitlabDiffFetchException("GitLab 地址、项目 ID 或合并请求 IID 缺失", false);
    }
    String baseUrl = config.getWebBaseUrl().trim().replaceAll("/+$", "");
    try {
      return URI.create(baseUrl + "/api/v4/projects/" + claim.projectId()
          + "/merge_requests/" + claim.mergeRequestIid() + "/changes");
    } catch (IllegalArgumentException error) {
      throw new GitlabDiffFetchException("GitLab 地址格式无效", false);
    }
  }

  private record LineCounts(int addedLines, int deletedLines) {}
}
