package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.TestSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CAT 唯一全量采集实现；手动、定时和补偿触发只改变运行元数据。 */
final class BiCatMirrorSyncService {
  private static final Logger log = LoggerFactory.getLogger(BiCatMirrorSyncService.class);

  private final BiCatMirrorRepository repository;
  private final BiCatSnapshotCollector collector;
  private final BiCatProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Duration leaseDuration;

  BiCatMirrorSyncService(
      BiCatMirrorRepository repository,
      BiCatProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.collector = new BiCatSnapshotCollector(clock);
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.leaseDuration = Duration.ofMinutes(requireLeaseMinutes(properties.getLeaseMinutes()));
    requireRange(properties.getConnectTimeoutMs(), 1, 120_000, "CAT 连接超时");
    requireRange(properties.getReadTimeoutMs(), 1, 120_000, "CAT 读取超时");
    requireRange(properties.getMaxResponseBytes(), 1, 52_428_800, "CAT 响应大小上限");
    requireRange(properties.getRetainedSnapshotCount(), 2, 100, "CAT 快照保留数量");
  }

  void execute(UUID runId, String runType) {
    Config config = repository.loadConfig();
    List<ScopeMapping> mappings = repository.loadMappings();
    int publishedStages = 0;
    int failedStages = 0;
    List<String> failures = new ArrayList<>();
    String status = "FAILED";
    String message;
    try {
      BiCatHttpClient client = createClient(config);
      var catalog = collector.collectCatalog(client);
      Instant catalogPublishedAt = clock.instant();
      repository.publishCatalog(runId, catalog, catalogPublishedAt);
      repository.heartbeat(runId, clock.instant(), leaseDuration);

      for (ScopeMapping mapping : mappings) {
        for (String testStage : List.of("UNIT_TEST", "INTEGRATION_TEST")) {
          String phaseId = mapping.testingPhaseId(testStage);
          if (phaseId == null || phaseId.isBlank()) {
            continue;
          }
          try {
            TestSnapshot snapshot = collector.collectStage(client, mapping, testStage, catalog);
            repository.publishTestSnapshot(runId, snapshot, clock.instant());
            publishedStages++;
          } catch (RuntimeException failure) {
            failedStages++;
            String scopeLabel = mapping.productVersionKey() + "/" + stageLabel(testStage);
            failures.add(scopeLabel + "：" + safeFailureMessage(failure));
            log.warn(
                "bi_cat_stage_sync_failed runId={} productVersionId={} stage={} message={}",
                runId,
                mapping.productVersionId(),
                testStage,
                failure.getMessage());
          }
          repository.heartbeat(runId, clock.instant(), leaseDuration);
        }
      }
      if (failedStages == 0) {
        status = "SUCCEEDED";
      } else if (publishedStages > 0) {
        status = "PARTIAL_SUCCESS";
      }
      message = successMessage(mappings.size(), publishedStages, failedStages, failures);
      repository.pruneSnapshots(properties.getRetainedSnapshotCount());
    } catch (RuntimeException failure) {
      message = "CAT 目录采集失败，已保留上一份发布快照：" + safeFailureMessage(failure);
      log.error("bi_cat_sync_failed runId={}", runId, failure);
    }
    repository.finishRun(
        runId,
        runType,
        status,
        publishedStages,
        failedStages,
        message,
        clock.instant(),
        config.syncIntervalMinutes());
  }

  ConnectionResult testConnection() {
    Config config = repository.loadConfig();
    var catalog = collector.collectCatalog(createClient(config));
    long versionCount = catalog.nodes().stream()
        .filter(node -> "VERSION".equals(node.nodeType()))
        .count();
    long phaseCount = catalog.nodes().stream()
        .filter(node -> "TEST_PHASE".equals(node.nodeType()))
        .count();
    return new ConnectionResult(
        catalog.projects().size(), Math.toIntExact(versionCount), Math.toIntExact(phaseCount));
  }

  private BiCatHttpClient createClient(Config config) {
    URI baseUri = requireBaseUri(config.baseUrl());
    BiCatProperties.Paths paths = properties.getPaths();
    return new BiCatHttpClient(
        new BiCatHttpClient.RuntimeConfig(
            baseUri,
            requirePath(paths.getProjects(), "CAT 项目目录路径"),
            requirePath(paths.getPhaseTree(), "CAT 阶段树路径"),
            requirePath(paths.getStatistics(), "CAT 模块统计路径"),
            requirePath(paths.getFeatureStatistics(), "CAT 功能统计路径"),
            Duration.ofMillis(properties.getConnectTimeoutMs()),
            Duration.ofMillis(properties.getReadTimeoutMs()),
            properties.getMaxResponseBytes()),
        objectMapper);
  }

  static URI requireBaseUri(String value) {
    try {
      URI uri = URI.create(requireText(value, "CAT 基地址"));
      if (!uri.isAbsolute()
          || !("http".equalsIgnoreCase(uri.getScheme())
              || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("invalid CAT base URI");
      }
      return uri;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("CAT 基地址必须是无凭据、查询和片段的 HTTP(S) 地址");
    }
  }

  private String requirePath(String value, String label) {
    String path = requireText(value, label);
    if (!path.startsWith("/") || path.contains("?") || path.contains("#") || path.contains("://")) {
      throw new IllegalArgumentException(label + "必须是无查询参数的绝对路径");
    }
    return path;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim();
  }

  private int requireLeaseMinutes(int value) {
    if (value < 1 || value > 720) {
      throw new IllegalArgumentException("CAT 同步租约必须在 1 至 720 分钟之间");
    }
    return value;
  }

  private void requireRange(int value, int minimum, int maximum, String label) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(label + "必须在 " + minimum + " 至 " + maximum + " 之间");
    }
  }

  private String successMessage(
      int mappingCount,
      int publishedStages,
      int failedStages,
      List<String> failures) {
    if (mappingCount == 0) {
      return "CAT 项目、版本和测试阶段目录已发布；尚未配置 BI 产品版本映射";
    }
    String summary = "CAT 目录已发布，成功发布 " + publishedStages + " 个测试阶段快照";
    if (failedStages == 0) {
      return summary;
    }
    String detail = String.join("；", failures.stream().limit(5).toList());
    return summary + "，" + failedStages + " 个阶段失败并继续保留旧快照：" + detail;
  }

  private String safeFailureMessage(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    String normalized = message.replaceAll("[\\r\\n]+", " ").strip();
    return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
  }

  private String stageLabel(String testStage) {
    return "UNIT_TEST".equals(testStage) ? "单元测试" : "集成测试";
  }

  record ConnectionResult(int projectCount, int versionCount, int phaseCount) {}
}
