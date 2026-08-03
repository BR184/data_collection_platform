package com.data.collection.platform.entity;

import java.util.List;

/** 一批镜像写入中真正改变业务数据的行及 no-op 数量。 */
public record MirrorMutationResult(
    int sourceRows, List<MirrorRowChange> changes, int unchangedRows) {
  public MirrorMutationResult {
    changes = changes == null ? List.of() : List.copyOf(changes);
    if (sourceRows < 0 || unchangedRows < 0) {
      throw new IllegalArgumentException("镜像批次计数不能为负数");
    }
  }

  public int appliedRows() {
    return changes.size();
  }

  public static MirrorMutationResult empty() {
    return new MirrorMutationResult(0, List.of(), 0);
  }
}
