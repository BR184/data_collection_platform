package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class GitlabWhitelistService {
  private static final Duration CACHE_TTL = Duration.ofMinutes(1);

  private static final Map<String, String> FRIENDLY_LABELS = new LinkedHashMap<>();
  private static final Set<String> RECOMMENDED_TABLES = Set.of(
      "users",
      "user_details",
      "projects",
      "namespaces",
      "members",
      "issues",
      "issue_assignees",
      "issue_metrics",
      "notes",
      "labels",
      "label_links",
      "merge_requests",
      "merge_request_assignees",
      "merge_request_reviewers",
      "merge_request_metrics",
      "ci_pipelines",
      "ci_builds",
      "deployments",
      "environments",
      "events",
      "todos");

  static {
    FRIENDLY_LABELS.put("users", "用户");
    FRIENDLY_LABELS.put("user_details", "用户详情");
    FRIENDLY_LABELS.put("projects", "项目");
    FRIENDLY_LABELS.put("namespaces", "命名空间");
    FRIENDLY_LABELS.put("members", "成员关系");
    FRIENDLY_LABELS.put("issues", "缺陷 / Issue");
    FRIENDLY_LABELS.put("issue_assignees", "缺陷指派");
    FRIENDLY_LABELS.put("issue_metrics", "缺陷指标");
    FRIENDLY_LABELS.put("notes", "评论 / Notes");
    FRIENDLY_LABELS.put("labels", "标签");
    FRIENDLY_LABELS.put("label_links", "标签关联");
    FRIENDLY_LABELS.put("merge_requests", "合并请求");
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

  private final GitlabExternalDbService externalDbService;

  private volatile CacheEntry cacheEntry;

  public GitlabWhitelistService(GitlabExternalDbService externalDbService) {
    this.externalDbService = externalDbService;
  }

  public List<TableWhitelistOption> listOptions(GitlabSyncConfig config) {
    try {
      return new ArrayList<>(loadAvailableTables(config));
    } catch (Exception ignored) {
      return fallbackRecommendedOptions();
    }
  }

  public List<TableWhitelistOption> resolveOptions(GitlabSyncConfig config) {
    List<TableWhitelistOption> allOptions = loadAvailableTables(config);
    if (config == null || config.getWhitelistMode() == null || config.getWhitelistMode() == WhitelistMode.RECOMMENDED) {
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

  private List<TableWhitelistOption> loadAvailableTables(GitlabSyncConfig config) {
    String signature = buildSignature(config);
    CacheEntry currentCache = cacheEntry;
    if (currentCache != null
        && currentCache.signature().equals(signature)
        && Duration.between(currentCache.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
      return currentCache.options();
    }
    List<TableWhitelistOption> discovered = externalDbService.discoverTables(
        config,
        FRIENDLY_LABELS,
        new ArrayList<>(RECOMMENDED_TABLES));
    cacheEntry = new CacheEntry(signature, Instant.now(), discovered);
    return discovered;
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
    for (String tableName : RECOMMENDED_TABLES.stream().sorted().toList()) {
      options.add(new TableWhitelistOption(
          tableName,
          FRIENDLY_LABELS.getOrDefault(tableName, tableName),
          "id",
          null,
          true));
    }
    return options;
  }

  private record CacheEntry(String signature, Instant loadedAt, List<TableWhitelistOption> options) {
  }
}
