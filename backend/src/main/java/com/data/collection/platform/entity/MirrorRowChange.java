package com.data.collection.platform.entity;

import java.util.Map;

/** 一行 ODS 业务数据的真实插入、修改、恢复或删除。 */
public record MirrorRowChange(Map<String, Object> before, Map<String, Object> after) {
  public MirrorRowChange {
    before = before == null ? Map.of() : Map.copyOf(before);
    after = after == null ? Map.of() : Map.copyOf(after);
    if (before.isEmpty() && after.isEmpty()) {
      throw new IllegalArgumentException("镜像变化必须包含变更前或变更后数据");
    }
  }

  public boolean deleted() {
    return !before.isEmpty() && after.isEmpty();
  }

  public boolean inserted() {
    return before.isEmpty() && !after.isEmpty();
  }
}
