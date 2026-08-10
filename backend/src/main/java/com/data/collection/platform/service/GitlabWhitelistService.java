package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.sync.GitlabSourceLineageCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class GitlabWhitelistService {
  private static final Duration CACHE_TTL = Duration.ofHours(12);
  private static final int MAX_CACHE_ENTRIES = 16;

  private static final Map<String, String> FRIENDLY_LABELS = new LinkedHashMap<>();
  static {
    FRIENDLY_LABELS.put("users", "用户");
    FRIENDLY_LABELS.put("user_details", "用户详情");
    FRIENDLY_LABELS.put("projects", "项目");
    FRIENDLY_LABELS.put("namespaces", "命名空间");
    FRIENDLY_LABELS.put("members", "成员关系");
    FRIENDLY_LABELS.put("milestones", "里程碑");
    FRIENDLY_LABELS.put("issues", "缺陷 / Issue");
    FRIENDLY_LABELS.put("issue_assignees", "缺陷指派");
    FRIENDLY_LABELS.put("issue_metrics", "缺陷指标");
    FRIENDLY_LABELS.put("notes", "评论 / Notes");
    FRIENDLY_LABELS.put("labels", "标签");
    FRIENDLY_LABELS.put("label_links", "标签关联");
    FRIENDLY_LABELS.put("resource_label_events", "标签变更事件");
    FRIENDLY_LABELS.put("merge_requests", "合并请求");
    FRIENDLY_LABELS.put("merge_request_diffs", "合并请求差异版本");
    FRIENDLY_LABELS.put("merge_request_diff_commits", "合并请求提交明细");
    FRIENDLY_LABELS.put("merge_request_assignees", "MR 指派");
    FRIENDLY_LABELS.put("merge_request_reviewers", "MR Reviewer");
    FRIENDLY_LABELS.put("merge_request_metrics", "MR 指标");
    FRIENDLY_LABELS.put("ci_pipelines", "流水线");
    FRIENDLY_LABELS.put("ci_builds", "构建任务");
    FRIENDLY_LABELS.put("deployments", "部署");
    FRIENDLY_LABELS.put("environments", "环境");
    FRIENDLY_LABELS.put("events", "事件");
    FRIENDLY_LABELS.put("todos", "待办");
  }

  private final SourceMetadataInspector sourceMetadataInspector;

  private final Map<String, CacheEntry> cacheEntries = new ConcurrentHashMap<>();

  public GitlabWhitelistService(SourceMetadataInspector sourceMetadataInspector) {
    this.sourceMetadataInspector = sourceMetadataInspector;
  }

  public List<TableWhitelistOption> listOptions(GitlabSyncConfig config) {
    try {
      return new ArrayList<>(loadAvailableTables(config));
    } catch (Exception ignored) {
      return fallbackRecommendedOptions();
    }
  }

  public List<TableWhitelistOption> listOptionsStrict(GitlabSyncConfig config) {
    return new ArrayList<>(loadAvailableTables(config));
  }

  public List<TableWhitelistOption> resolveOptions(GitlabSyncConfig config) {
    List<TableWhitelistOption> allOptions = listOptionsStrict(config);
    if (config == null
        || config.getWhitelistMode() == null
        || config.getWhitelistMode() == WhitelistMode.RECOMMENDED) {
      return allOptions.stream().filter(TableWhitelistOption::recommended).toList();
    }
    if (config.getWhitelistMode() == WhitelistMode.ALL) {
      return allOptions;
    }
    List<String> tables = config.getWhitelistTables() == null ? List.of() : config.getWhitelistTables();
    return tables.stream()
        .map(tableName -> allOptions.stream().filter(option -> option.tableName().equals(tableName)).findFirst().orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private synchronized List<TableWhitelistOption> loadAvailableTables(GitlabSyncConfig config) {
    String signature = buildSignature(config);
    CacheEntry currentCache = cacheEntries.get(signature);
    if (currentCache != null
        && Duration.between(currentCache.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
      return currentCache.options();
    }
    List<TableWhitelistOption> discovered = sourceMetadataInspector.discoverTables(
        config,
        FRIENDLY_LABELS,
        GitlabSourceLineageCatalog.recommendedTables());
    evictOldestEntryIfNecessary(signature);
    List<TableWhitelistOption> options = List.copyOf(discovered);
    cacheEntries.put(signature, new CacheEntry(Instant.now(), options));
    return options;
  }

  private void evictOldestEntryIfNecessary(String signature) {
    if (cacheEntries.containsKey(signature) || cacheEntries.size() < MAX_CACHE_ENTRIES) {
      return;
    }
    cacheEntries.entrySet().stream()
        .min(Map.Entry.comparingByValue((first, second) -> first.loadedAt().compareTo(second.loadedAt())))
        .ifPresent(entry -> cacheEntries.remove(entry.getKey()));
  }

  private String buildSignature(GitlabSyncConfig config) {
    if (config == null) {
      return "default";
    }
    return String.join("|",
        GitlabSourceInstanceSupport.sourceInstanceOf(config),
        String.valueOf(config.getSourceMode()),
        String.valueOf(config.getDockerContainerName()),
        String.valueOf(config.getDbHost()),
        String.valueOf(config.getDbPort()),
        String.valueOf(config.getDbName()),
        String.valueOf(config.getDbUsername()));
  }

  private List<TableWhitelistOption> fallbackRecommendedOptions() {
    List<TableWhitelistOption> options = new ArrayList<>();
    for (String tableName : GitlabSourceLineageCatalog.recommendedTables().stream().sorted().toList()) {
      options.add(new TableWhitelistOption(
          tableName,
          FRIENDLY_LABELS.getOrDefault(tableName, tableName),
          "id",
          null,
          SourceCursorStrategy.NONE,
          true));
    }
    return options;
  }

  private record CacheEntry(Instant loadedAt, List<TableWhitelistOption> options) {
  }
}
