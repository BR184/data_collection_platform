package com.data.collection.platform.entity;

/** 事实投影缓存的稳定失效范围类型。 */
public enum ProjectionScopeType {
  FULL_EPOCH,
  GLOBAL_VIEW,
  PROJECT,
  ISSUE_SCOPE_GROUP
}
