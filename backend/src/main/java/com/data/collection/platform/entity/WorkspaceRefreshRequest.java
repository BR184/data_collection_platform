package com.data.collection.platform.entity;

import java.util.Locale;

/** 页面手动刷新请求，由稳定工作区键和稳定范围选择共同定义。 */
public record WorkspaceRefreshRequest(
    String workspaceKey,
    WorkspaceScopeSelection scopeSelection) {

  public WorkspaceRefreshRequest {
    if (workspaceKey == null || workspaceKey.isBlank()) {
      throw new IllegalArgumentException("页面刷新必须声明工作区键");
    }
    workspaceKey = workspaceKey.trim().toLowerCase(Locale.ROOT);
    if (scopeSelection == null) {
      throw new IllegalArgumentException("页面刷新必须声明稳定范围");
    }
  }

  /** 创建当前没有局部稳定筛选参数的工作区刷新请求。 */
  public static WorkspaceRefreshRequest global(String workspaceKey) {
    return new WorkspaceRefreshRequest(workspaceKey, WorkspaceScopeSelection.global());
  }
}
