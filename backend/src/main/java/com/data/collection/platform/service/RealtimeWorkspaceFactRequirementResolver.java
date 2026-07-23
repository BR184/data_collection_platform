package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;

/**
 * 将页面工作区映射为其真正依赖的事实类型。
 *
 * <p>页面刷新只能等待自身查询会读取的事实层；不能因为同一次镜像运行还刷新了其他事实类型而误报数据就绪。
 */
final class RealtimeWorkspaceFactRequirementResolver {
  private RealtimeWorkspaceFactRequirementResolver() {
  }

  static Requirement resolve(String workspaceKey, GitlabSyncConfig config) {
    List<String> supportedFactTypes = GitlabFactRefreshRequirements.supportedFactTypes(config);
    List<String> requiredFactTypes = requiredFactTypes(workspaceKey, supportedFactTypes);
    return new Requirement(!requiredFactTypes.isEmpty(), requiredFactTypes);
  }

  private static List<String> requiredFactTypes(String workspaceKey, List<String> supportedFactTypes) {
    String normalizedWorkspaceKey = workspaceKey == null ? "" : workspaceKey.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalizedWorkspaceKey.startsWith("customer-issue-")
        || normalizedWorkspaceKey.startsWith("system-test-")) {
      return supportedFactTypes.contains(GitlabFactRefreshRequirements.FACT_TYPE_ISSUE)
          ? List.of(GitlabFactRefreshRequirements.FACT_TYPE_ISSUE)
          : List.of();
    }
    if (normalizedWorkspaceKey.startsWith("code-review-")) {
      return supportedFactTypes.contains(GitlabFactRefreshRequirements.FACT_TYPE_MERGE_REQUEST)
          ? List.of(GitlabFactRefreshRequirements.FACT_TYPE_MERGE_REQUEST)
          : List.of();
    }
    return supportedFactTypes;
  }

  record Requirement(boolean factRefreshRequired, List<String> factTypes) {
    Requirement {
      factTypes = factTypes == null ? List.of() : List.copyOf(factTypes);
    }
  }
}
