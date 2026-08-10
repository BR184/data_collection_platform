package com.data.collection.platform.bi.domain.source;

import java.util.List;

/** 单次系统测试页面请求冻结后的轮次目录和有效缺陷来源快照。 */
public record BiSystemTestSource(
    String sourceVersion,
    String snapshotId,
    List<RoundDefinition> rounds,
    List<IssueRecord> issues) {
  public BiSystemTestSource {
    rounds = rounds == null ? List.of() : List.copyOf(rounds);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  /** 产品版本目录中的稳定测试轮次。 */
  public record RoundDefinition(String roundId, String roundName, int roundOrder) {}

  /** BI 版本化原因规则产出的稳定大类和子类。 */
  public record CauseRef(
      String categoryId,
      String categoryName,
      String subcategoryId,
      String subcategoryName) {}

  /** 已按 ST-01 至 ST-04 排除无效项后的单条系统测试缺陷。 */
  public record IssueRecord(
      long issueId,
      String roundId,
      String severity,
      String priority,
      boolean fixed,
      List<BiSourceDimension> modules,
      BiSourceDimension assignee,
      List<CauseRef> causes,
      boolean delayed,
      List<BiSourceDimension> delayCauses) {
    public IssueRecord {
      modules = modules == null ? List.of() : List.copyOf(modules);
      causes = causes == null ? List.of() : List.copyOf(causes);
      delayCauses = delayCauses == null ? List.of() : List.copyOf(delayCauses);
    }
  }
}
