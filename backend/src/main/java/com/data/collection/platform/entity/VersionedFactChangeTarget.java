package com.data.collection.platform.entity;

/** 已分配全局变化版本并写入唯一发布 outbox 的事实目标。 */
public record VersionedFactChangeTarget(
    long mirrorRunId, FactChangeIdentity identity, long changeVersion) {
  public VersionedFactChangeTarget {
    if (mirrorRunId <= 0L || identity == null || changeVersion <= 0L) {
      throw new IllegalArgumentException("版本化事实目标缺少有效运行、身份或版本");
    }
  }
}
