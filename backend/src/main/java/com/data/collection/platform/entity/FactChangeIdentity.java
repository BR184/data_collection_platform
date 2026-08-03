package com.data.collection.platform.entity;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;

/** 一个需要按当前 ODS 状态重新发布的稳定事实根。 */
public record FactChangeIdentity(
    String sourceInstance,
    FactType factType,
    long rootId,
    Long projectId,
    Long iid) implements Comparable<FactChangeIdentity> {
  public FactChangeIdentity {
    sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    if (factType == null || rootId <= 0L) {
      throw new IllegalArgumentException("事实变化身份必须包含事实类型和正数根 ID");
    }
  }

  @Override
  public int compareTo(FactChangeIdentity other) {
    int sourceOrder = sourceInstance.compareTo(other.sourceInstance);
    if (sourceOrder != 0) {
      return sourceOrder;
    }
    int typeOrder = factType.compareTo(other.factType);
    if (typeOrder != 0) {
      return typeOrder;
    }
    return Long.compare(rootId, other.rootId);
  }
}
