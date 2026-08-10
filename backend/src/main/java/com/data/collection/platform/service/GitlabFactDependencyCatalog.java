package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GitLab 事实类型到完整来源依赖的唯一目录。 */
public final class GitlabFactDependencyCatalog {
  private static final Map<FactType, FactDependency> DEPENDENCIES = dependencies();
  private static final List<FactType> FACT_TYPE_ORDER = List.of(FactType.values());

  private GitlabFactDependencyCatalog() {}

  /** 返回指定事实类型的完整来源依赖。 */
  public static FactDependency require(FactType factType) {
    FactDependency dependency = DEPENDENCIES.get(factType);
    if (dependency == null) {
      throw new IllegalArgumentException("事实类型尚未声明 GitLab 来源依赖：" + factType);
    }
    return dependency;
  }

  /** 返回当前配置具备完整来源能力的事实类型。 */
  public static List<FactType> supportedFactTypes(GitlabSyncConfig config) {
    if (config == null || config.getWhitelistMode() != WhitelistMode.CUSTOM) {
      return FACT_TYPE_ORDER;
    }
    Set<String> whitelist = normalizedWhitelist(config);
    if (whitelist.isEmpty()) {
      return List.of();
    }
    return FACT_TYPE_ORDER.stream()
        .filter(factType -> whitelist.containsAll(require(factType).requiredTables()))
        .toList();
  }

  public static boolean supportsAnyFactRefresh(GitlabSyncConfig config) {
    return !supportedFactTypes(config).isEmpty();
  }

  /** 返回全部事实会读取或作为变化信号消费的来源表。 */
  public static List<String> allRequiredTables() {
    LinkedHashSet<String> tables = new LinkedHashSet<>();
    DEPENDENCIES.values().forEach(dependency -> tables.addAll(dependency.requiredTables()));
    return List.copyOf(tables);
  }

  private static Map<FactType, FactDependency> dependencies() {
    EnumMap<FactType, FactDependency> dependencies = new EnumMap<>(FactType.class);
    dependencies.put(
        FactType.ISSUE,
        new FactDependency(
            FactType.ISSUE,
            List.of(
                "issues",
                "projects",
                "users",
                "milestones",
                "labels",
                "label_links",
                "notes",
                "issue_assignees"),
            List.of("resource_label_events", "issue_metrics")));
    dependencies.put(
        FactType.MERGE_REQUEST,
        new FactDependency(
            FactType.MERGE_REQUEST,
            List.of(
                "merge_requests",
                "merge_request_metrics",
                "projects",
                "namespaces",
                "users",
                "merge_request_reviewers",
                "merge_request_assignees",
                "notes",
                "label_links",
                "labels"),
            List.of("resource_label_events")));
    dependencies.put(
        FactType.INTEGRATION_TEST,
        new FactDependency(
            FactType.INTEGRATION_TEST,
            List.of("issues", "projects", "users", "labels", "label_links", "notes"),
            List.of()));
    return Map.copyOf(dependencies);
  }

  private static Set<String> normalizedWhitelist(GitlabSyncConfig config) {
    List<String> whitelist = config.getWhitelistTables();
    if (whitelist == null || whitelist.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String table : whitelist) {
      String value = GitlabSourceInstanceSupport.normalizeSourceTableName(table);
      if (!value.isBlank()) {
        normalized.add(value);
      }
    }
    return normalized;
  }

  /** 一个事实类型的业务读取表和只用于变化发现的信号表。 */
  public record FactDependency(
      FactType factType, List<String> businessTables, List<String> changeSignalTables) {
    public FactDependency {
      businessTables = normalizedDistinct(businessTables);
      changeSignalTables = normalizedDistinct(changeSignalTables);
      Set<String> overlap = new LinkedHashSet<>(businessTables);
      overlap.retainAll(changeSignalTables);
      if (!overlap.isEmpty()) {
        throw new IllegalArgumentException("事实业务表与信号表重复：" + overlap);
      }
    }

    public List<String> requiredTables() {
      ArrayList<String> tables = new ArrayList<>(businessTables);
      tables.addAll(changeSignalTables);
      return List.copyOf(tables);
    }

    /** 返回当前配置下事实类型的完整依赖，MR 提交增强来源按白名单条件加入。 */
    public List<String> requiredTables(GitlabSyncConfig config) {
      ArrayList<String> tables = new ArrayList<>(requiredTables());
      if (factType == FactType.MERGE_REQUEST
          && GitlabMergeRequestCommitFactCapability.isEnabled(config)) {
        tables.add("merge_request_diffs");
        tables.add("merge_request_diff_commits");
      }
      return tables.stream().distinct().toList();
    }

    private static List<String> normalizedDistinct(List<String> tables) {
      LinkedHashSet<String> normalized = new LinkedHashSet<>();
      if (tables != null) {
        tables.stream()
            .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
            .filter(table -> !table.isBlank())
            .forEach(normalized::add);
      }
      return List.copyOf(normalized);
    }
  }
}
