package com.data.collection.platform.service;

import com.data.collection.platform.service.IssueScopeDimension;

/** 生成和解析事实投影范围的规范稳定键。 */
public final class FactProjectionScopeKeyCodec {
  public static final String SINGLETON_SCOPE_KEY = "*";

  private FactProjectionScopeKeyCodec() {}

  /** 项目范围使用 GitLab project_id 的十进制文本。 */
  public static String project(long projectId) {
    requirePositive(projectId, "projectId");
    return Long.toString(projectId);
  }

  /** 议题范围组键只包含稳定 ID，不包含显示名或事实文本。 */
  public static String issueScopeGroup(
      long projectId, IssueScopeDimension dimension, long groupId) {
    requirePositive(projectId, "projectId");
    requirePositive(groupId, "groupId");
    if (dimension == null) {
      throw new IllegalArgumentException("议题范围组必须声明维度");
    }
    return "project=" + projectId + ";dimension=" + dimension.name() + ";group=" + groupId;
  }

  /** 解析已由本类编码的议题范围组键。 */
  public static IssueScopeGroupKey parseIssueScopeGroup(String scopeKey) {
    if (scopeKey == null) {
      throw new IllegalArgumentException("议题范围组键不能为空");
    }
    String[] parts = scopeKey.split(";", -1);
    if (parts.length != 3
        || !parts[0].startsWith("project=")
        || !parts[1].startsWith("dimension=")
        || !parts[2].startsWith("group=")) {
      throw new IllegalArgumentException("非法议题范围组键：" + scopeKey);
    }
    long projectId = parsePositive(parts[0].substring("project=".length()), "projectId");
    IssueScopeDimension dimension;
    try {
      dimension = IssueScopeDimension.valueOf(parts[1].substring("dimension=".length()));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("非法议题范围维度：" + scopeKey, error);
    }
    long groupId = parsePositive(parts[2].substring("group=".length()), "groupId");
    return new IssueScopeGroupKey(projectId, dimension, groupId);
  }

  private static long parsePositive(String value, String field) {
    try {
      long parsed = Long.parseLong(value);
      requirePositive(parsed, field);
      return parsed;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " 必须是正整数", error);
    }
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) {
      throw new IllegalArgumentException(field + " 必须是正整数");
    }
  }

  public record IssueScopeGroupKey(
      long projectId, IssueScopeDimension dimension, long groupId) {}
}
