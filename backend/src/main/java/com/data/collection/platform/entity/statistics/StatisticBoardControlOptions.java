package com.data.collection.platform.entity.statistics;

import java.util.List;

/**
 * 统计表控制参数的成员候选。
 *
 * <p>候选由统计板同一份窄事实读取在"完整基础范围"（同一生效里程碑与来源，未施加成员选择）上求得，
 * 因此不随当前已选成员或当前结果行收缩；它只是同一事实源的一次查询结果，不构成第二套成员权威源。
 *
 * @param scopeKey 生效范围业务键；空串表示当前没有启用范围
 * @param sourceVersion 候选所属的来源代际，与主表同源；前端据此判断候选是否仍然适用
 * @param scopeReadable 基础范围是否可判定；{@code false} 时 {@code reason} 说明为何给不出候选
 * @param reason 范围不可判定或目录缺失时的可读原因
 * @param groups 各控制参数的候选组
 */
public record StatisticBoardControlOptions(
    String scopeKey,
    String sourceVersion,
    boolean scopeReadable,
    String reason,
    List<StatisticBoardControlOptionGroup> groups) {

  public StatisticBoardControlOptions {
    scopeKey = scopeKey == null ? "" : scopeKey;
    sourceVersion = sourceVersion == null ? "" : sourceVersion;
    reason = reason == null ? "" : reason;
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  /** 不支持成员控制的统计板使用的空候选。 */
  public static StatisticBoardControlOptions empty() {
    return new StatisticBoardControlOptions("", "", false, "当前统计页面没有成员控制项", List.of());
  }

  /** 来源不可判定时的候选：明确报告原因，不返回空列表冒充"确实没有成员"。 */
  public static StatisticBoardControlOptions unavailable(String scopeKey, String reason) {
    return new StatisticBoardControlOptions(scopeKey, "", false, reason, List.of());
  }
}
