package com.data.collection.platform.entity;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;

/** 一个事实类型的稳定投影失效范围。 */
public record FactProjectionScope(
    String sourceInstance,
    FactType factType,
    ProjectionScopeType scopeType,
    String scopeKey)
    implements Comparable<FactProjectionScope> {

  public FactProjectionScope {
    sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    if (factType == null || scopeType == null) {
      throw new IllegalArgumentException("事实投影范围必须声明事实类型和范围类型");
    }
    if (scopeKey == null || scopeKey.isBlank() || scopeKey.length() > 512) {
      throw new IllegalArgumentException("事实投影范围键不能为空且长度不能超过 512");
    }
    scopeKey = scopeKey.trim();
  }

  @Override
  public int compareTo(FactProjectionScope other) {
    int sourceOrder = sourceInstance.compareTo(other.sourceInstance);
    if (sourceOrder != 0) {
      return sourceOrder;
    }
    int factOrder = factType.compareTo(other.factType);
    if (factOrder != 0) {
      return factOrder;
    }
    int typeOrder = scopeType.compareTo(other.scopeType);
    return typeOrder != 0 ? typeOrder : scopeKey.compareTo(other.scopeKey);
  }
}
