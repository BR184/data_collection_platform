package com.data.collection.platform.service;

import java.util.Map;

/** System Hook 唤醒后需要按完整范围回读的镜像目标。 */
public record GitlabSystemHookPreciseSyncTarget(
    String tableName,
    Map<String, String> lookupScope) {
  public GitlabSystemHookPreciseSyncTarget {
    lookupScope = lookupScope == null ? Map.of() : Map.copyOf(lookupScope);
  }
}
