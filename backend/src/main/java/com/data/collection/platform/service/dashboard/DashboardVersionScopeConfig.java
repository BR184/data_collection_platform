package com.data.collection.platform.service.dashboard;

import java.util.List;

/**
 * 看板版本分组的静态配置。
 *
 * <p>分组只影响看板查询范围，不修改议题测试阶段目录本身；成员始终按稳定业务键解析。
 */
public record DashboardVersionScopeConfig(int schemaVersion, List<ScopeDefinition> scopes) {
  public DashboardVersionScopeConfig {
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }

  /** 单个看板版本分组。 */
  public record ScopeDefinition(
      String key,
      String displayName,
      long projectId,
      String dimension,
      String anchorBusinessKey,
      List<String> memberBusinessKeys,
      List<String> reviewProjectNames,
      List<String> integrationTestPhases) {
    public ScopeDefinition {
      memberBusinessKeys =
          memberBusinessKeys == null ? List.of() : List.copyOf(memberBusinessKeys);
      reviewProjectNames =
          reviewProjectNames == null ? List.of() : List.copyOf(reviewProjectNames);
      integrationTestPhases =
          integrationTestPhases == null ? List.of() : List.copyOf(integrationTestPhases);
    }
  }
}
