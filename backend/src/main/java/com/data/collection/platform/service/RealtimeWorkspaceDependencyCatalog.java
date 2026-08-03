package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** 页面工作区到事实类型和镜像扫描依赖的唯一目录。 */
public final class RealtimeWorkspaceDependencyCatalog {
  private static final List<WorkspaceDefinition> DEFINITIONS =
      List.of(
          WorkspaceDefinition.prefix("customer-issue-", List.of(FactType.ISSUE)),
          WorkspaceDefinition.prefix("system-test-", List.of(FactType.ISSUE)),
          WorkspaceDefinition.prefix("code-review-", List.of(FactType.MERGE_REQUEST)));

  private RealtimeWorkspaceDependencyCatalog() {}

  /** 解析工作区依赖；未登记工作区显式失败。 */
  public static WorkspaceDependency require(String workspaceKey) {
    String normalizedKey = normalizeWorkspaceKey(workspaceKey);
    WorkspaceDefinition definition = DEFINITIONS.stream()
        .filter(candidate -> candidate.matches(normalizedKey))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("实时刷新工作区尚未登记：" + normalizedKey));
    LinkedHashSet<String> sourceTables = new LinkedHashSet<>();
    definition.factTypes().stream()
        .map(GitlabFactDependencyCatalog::require)
        .forEach(dependency -> sourceTables.addAll(dependency.requiredTables()));
    return new WorkspaceDependency(normalizedKey, definition.factTypes(), List.copyOf(sourceTables));
  }

  /** 返回当前配置真正支持的工作区事实要求。 */
  public static Requirement resolve(String workspaceKey, GitlabSyncConfig config) {
    WorkspaceDependency dependency = require(workspaceKey);
    List<FactType> supported = GitlabFactDependencyCatalog.supportedFactTypes(config);
    List<FactType> required = dependency.factTypes().stream().filter(supported::contains).toList();
    return new Requirement(!required.isEmpty(), required);
  }

  private static String normalizeWorkspaceKey(String workspaceKey) {
    if (workspaceKey == null || workspaceKey.isBlank()) {
      throw new IllegalArgumentException("实时刷新必须声明工作区键");
    }
    return workspaceKey.trim().toLowerCase(Locale.ROOT);
  }

  private record WorkspaceDefinition(String keyPrefix, List<FactType> factTypes) {
    private WorkspaceDefinition {
      factTypes = List.copyOf(factTypes);
    }

    private static WorkspaceDefinition prefix(String keyPrefix, List<FactType> factTypes) {
      return new WorkspaceDefinition(keyPrefix, factTypes);
    }

    private boolean matches(String workspaceKey) {
      return workspaceKey.startsWith(keyPrefix);
    }
  }

  public record WorkspaceDependency(
      String workspaceKey, List<FactType> factTypes, List<String> sourceTables) {
    public WorkspaceDependency {
      factTypes = List.copyOf(factTypes);
      sourceTables = List.copyOf(sourceTables);
    }
  }

  public record Requirement(boolean factRefreshRequired, List<FactType> factTypes) {
    public Requirement {
      factTypes = factTypes == null ? List.of() : List.copyOf(factTypes);
    }

    public List<String> factTypeNames() {
      return factTypes.stream().map(Enum::name).toList();
    }
  }
}
