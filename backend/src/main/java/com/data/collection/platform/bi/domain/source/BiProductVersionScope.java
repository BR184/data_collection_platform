package com.data.collection.platform.bi.domain.source;

import java.util.List;

/** BI 顶部产品版本对应的平台稳定范围和测试轮次成员。 */
public record BiProductVersionScope(
    long id,
    long projectId,
    String businessKey,
    String displayName,
    int sortOrder,
    List<RoundScope> rounds) {
  public BiProductVersionScope {
    rounds = rounds == null ? List.of() : List.copyOf(rounds);
  }

  /** 议题测试阶段定义中的稳定轮次成员。 */
  public record RoundScope(
      String id,
      String sourceValue,
      String displayName,
      int sortOrder) {}
}
