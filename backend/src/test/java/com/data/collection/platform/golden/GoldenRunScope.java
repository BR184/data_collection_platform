package com.data.collection.platform.golden;

import java.util.List;

/** 黄金局部验收范围；默认全量，显式跳过表单仅用于获准的非表单严格比较。 */
final class GoldenRunScope {
  private GoldenRunScope() {}

  static List<GoldenEndpointCatalog.EndpointEntry> selectedEntries() {
    boolean skipForms = Boolean.getBoolean("golden.skipCollectForms");
    if (skipForms && GoldenSnapshotSupport.updateMode()) {
      throw new IllegalStateException("非表单局部验收只允许严格比较，禁止更新快照");
    }
    return select(List.copyOf(GoldenEndpointCatalog.load().entries().values()), skipForms);
  }

  /** 只过滤表单命名空间；共享来源配置、同步、事实、统计与其他写表继续完整比较。 */
  static List<GoldenEndpointCatalog.EndpointEntry> select(
      List<GoldenEndpointCatalog.EndpointEntry> entries, boolean skipForms) {
    return entries.stream()
        .filter(entry -> !skipForms || !entry.path().startsWith("/api/collect-forms/"))
        .toList();
  }
}
