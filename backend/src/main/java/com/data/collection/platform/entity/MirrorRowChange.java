package com.data.collection.platform.entity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一行 ODS 业务数据的真实插入、修改、恢复或删除；快照保留数据库 null 值且不可修改。 */
public record MirrorRowChange(Map<String, Object> before, Map<String, Object> after) {
  public MirrorRowChange {
    before = immutableSnapshot(before);
    after = immutableSnapshot(after);
    if (before.isEmpty() && after.isEmpty()) {
      throw new IllegalArgumentException("镜像变化必须包含变更前或变更后数据");
    }
  }

  /** 判断当前变化是否删除了镜像行。 */
  public boolean deleted() {
    return !before.isEmpty() && after.isEmpty();
  }

  /** 判断当前变化是否插入了镜像行。 */
  public boolean inserted() {
    return before.isEmpty() && !after.isEmpty();
  }

  private static Map<String, Object> immutableSnapshot(Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>(source.size());
    source.forEach(
        (column, value) ->
            snapshot.put(Objects.requireNonNull(column, "镜像变化列名不能为空"), value));
    return Collections.unmodifiableMap(snapshot);
  }
}
