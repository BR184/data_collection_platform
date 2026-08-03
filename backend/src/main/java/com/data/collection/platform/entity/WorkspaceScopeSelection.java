package com.data.collection.platform.entity;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;

/** 页面手动刷新使用的稳定范围选择，不接受显示名称或事实文本。 */
public record WorkspaceScopeSelection(
    WorkspaceScopeSelectionType type,
    Set<Long> projectIds,
    Set<Long> issueScopeGroupIds) {

  public WorkspaceScopeSelection {
    if (type == null) {
      throw new IllegalArgumentException("页面刷新范围类型不能为空");
    }
    projectIds = normalizeIds(projectIds, "projectId");
    issueScopeGroupIds = normalizeIds(issueScopeGroupIds, "issueScopeGroupId");
    switch (type) {
      case GLOBAL -> {
        if (!projectIds.isEmpty() || !issueScopeGroupIds.isEmpty()) {
          throw new IllegalArgumentException("全局刷新范围不能携带局部 ID");
        }
      }
      case PROJECT -> {
        if (projectIds.isEmpty() || !issueScopeGroupIds.isEmpty()) {
          throw new IllegalArgumentException("项目刷新范围必须且只能包含 projectId");
        }
      }
      case ISSUE_SCOPE_GROUP -> {
        if (issueScopeGroupIds.isEmpty() || !projectIds.isEmpty()) {
          throw new IllegalArgumentException("议题范围组刷新必须且只能包含 scope group ID");
        }
      }
    }
  }

  /** 创建没有局部稳定选择的全局范围。 */
  public static WorkspaceScopeSelection global() {
    return new WorkspaceScopeSelection(
        WorkspaceScopeSelectionType.GLOBAL, Set.of(), Set.of());
  }

  /** 创建一个或多个 GitLab 项目的稳定范围。 */
  public static WorkspaceScopeSelection projects(Set<Long> projectIds) {
    return new WorkspaceScopeSelection(
        WorkspaceScopeSelectionType.PROJECT, projectIds, Set.of());
  }

  /** 创建一个或多个议题范围组的稳定范围。 */
  public static WorkspaceScopeSelection issueScopeGroups(Set<Long> groupIds) {
    return new WorkspaceScopeSelection(
        WorkspaceScopeSelectionType.ISSUE_SCOPE_GROUP, Set.of(), groupIds);
  }

  /** 返回用于 publication fence 的稳定选择键。 */
  public List<String> selectorKeys() {
    return switch (type) {
      case GLOBAL -> List.of("*");
      case PROJECT -> projectIds.stream().map(String::valueOf).toList();
      case ISSUE_SCOPE_GROUP -> issueScopeGroupIds.stream().map(String::valueOf).toList();
    };
  }

  private static Set<Long> normalizeIds(Set<Long> values, String field) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    TreeSet<Long> normalized = new TreeSet<>();
    for (Long value : values) {
      if (value == null || value <= 0L) {
        throw new IllegalArgumentException(field + " 必须是正整数");
      }
      normalized.add(value);
    }
    return Collections.unmodifiableSet(normalized);
  }
}
