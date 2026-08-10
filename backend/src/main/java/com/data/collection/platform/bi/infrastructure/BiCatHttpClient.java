package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.infrastructure.BiCatApiModels.Envelope;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.FeaturePage;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.FeatureQuery;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.PhaseNode;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.Project;
import com.data.collection.platform.bi.infrastructure.BiCatApiModels.StatisticsPayload;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;

/** 对 CAT 四个镜像接口执行有界、无重试、强类型的只读 POST 请求并保留原始响应。 */
final class BiCatHttpClient {
  private static final int CAT_SUCCESS_CODE = 200;

  private final URI baseUri;
  private final String projectsPath;
  private final String phaseTreePath;
  private final String statisticsPath;
  private final String featureStatisticsPath;
  private final Duration readTimeout;
  private final int maxResponseBytes;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  BiCatHttpClient(RuntimeConfig config, ObjectMapper objectMapper) {
    this.baseUri = config.baseUri();
    this.projectsPath = config.projectsPath();
    this.phaseTreePath = config.phaseTreePath();
    this.statisticsPath = config.statisticsPath();
    this.featureStatisticsPath = config.featureStatisticsPath();
    this.readTimeout = config.readTimeout();
    this.maxResponseBytes = config.maxResponseBytes();
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(config.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
  }

  Captured<List<Project>> projects() {
    return post(
        uri(projectsPath),
        null,
        new TypeReference<Envelope<List<Project>>>() {},
        "CAT 项目目录查询");
  }

  Captured<List<PhaseNode>> phaseTree(String projectId) {
    URI uri = uri(phaseTreePath, "projectId", projectId);
    return post(uri, null, new TypeReference<Envelope<List<PhaseNode>>>() {}, "CAT 测试阶段树查询");
  }

  Captured<StatisticsPayload> statistics(String testingPhaseId) {
    URI uri = uri(statisticsPath, "testingPhaseId", testingPhaseId);
    return post(
        uri,
        null,
        new TypeReference<Envelope<StatisticsPayload>>() {},
        "CAT 模块统计查询");
  }

  Captured<FeaturePage> features(String moduleId, String testingPhaseId) {
    return post(
        uri(featureStatisticsPath),
        new FeatureQuery(moduleId, testingPhaseId),
        new TypeReference<Envelope<FeaturePage>>() {},
        "CAT 功能统计查询");
  }

  private <T> Captured<T> post(
      URI uri,
      Object body,
      TypeReference<Envelope<T>> responseType,
      String operation) {
    try {
      byte[] bodyBytes = body == null ? new byte[0] : objectMapper.writeValueAsBytes(body);
      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(readTimeout)
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
          .build();
      HttpResponse<InputStream> response = httpClient.send(
          request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] responseBytes = readBounded(response.body(), operation);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new BiCatUpstreamException(operation + "失败：HTTP " + response.statusCode());
      }
      Envelope<T> envelope = objectMapper.readValue(responseBytes, responseType);
      if (envelope == null || envelope.code() == null || envelope.code() != CAT_SUCCESS_CODE) {
        throw new BiCatUpstreamException(operation + "失败：CAT 返回非成功业务码");
      }
      return new Captured<>(
          envelope.data(), new String(responseBytes, StandardCharsets.UTF_8));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new BiCatUpstreamException(operation + "被中断", interrupted);
    } catch (BiCatUpstreamException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new BiCatUpstreamException(operation + "失败", failure);
    }
  }

  private byte[] readBounded(InputStream body, String operation) throws IOException {
    try (body) {
      byte[] bytes = body.readNBytes(maxResponseBytes + 1);
      if (bytes.length > maxResponseBytes) {
        throw new BiCatUpstreamException(operation + "响应超过大小上限");
      }
      return bytes;
    }
  }

  private URI uri(String path, String... queryParts) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUri(baseUri).path(path);
    for (int index = 0; index < queryParts.length; index += 2) {
      builder.queryParam(queryParts[index], queryParts[index + 1]);
    }
    return builder.build().encode(StandardCharsets.UTF_8).toUri();
  }

  /** 已校验且不可变的 CAT 传输设置。 */
  record RuntimeConfig(
      URI baseUri,
      String projectsPath,
      String phaseTreePath,
      String statisticsPath,
      String featureStatisticsPath,
      Duration connectTimeout,
      Duration readTimeout,
      int maxResponseBytes) {}

  /** 一次 CAT 调用的强类型数据和未改写原始 JSON。 */
  record Captured<T>(T data, String rawJson) {}
}
