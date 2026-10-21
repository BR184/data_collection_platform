package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.service.OptionItemResponseFactory;
import java.util.List;

/**
 * 统计板人员快捷入口的候选集合。
 *
 * <p>候选必须由调用方在同一读取身份、来源与公共业务范围的基础集合上求解后传入；本类只负责按平台
 * 既有人员候选规则（数据源首次出现顺序、去空去重）转成下拉取值，标签与取值都使用事实人员名。
 * 范围内确实没有人员时保持空列表，不制造“未知人员”占位候选。
 */
public record StatisticPersonCandidates(
    List<StatisticFilterOption> authorNames, List<StatisticFilterOption> assigneeNames) {

  public StatisticPersonCandidates {
    authorNames = authorNames == null ? List.of() : List.copyOf(authorNames);
    assigneeNames = assigneeNames == null ? List.of() : List.copyOf(assigneeNames);
  }

  /** 以事实人员列构造候选。 */
  public static StatisticPersonCandidates of(List<String> rawAuthorNames, List<String> rawAssigneeNames) {
    return new StatisticPersonCandidates(toOptions(rawAuthorNames), toOptions(rawAssigneeNames));
  }

  /** 无候选模板定义，用于尚未持有事实的 definition 解析入口。 */
  public static StatisticPersonCandidates empty() {
    return new StatisticPersonCandidates(List.of(), List.of());
  }

  private static List<StatisticFilterOption> toOptions(List<String> rawNames) {
    return OptionItemResponseFactory.fromLegacyBusinessValues(
            rawNames == null ? List.of() : rawNames).stream()
        .map(option -> new StatisticFilterOption(option.label(), option.value()))
        .toList();
  }
}
