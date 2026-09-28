package com.data.collection.platform.entity.statistics;

import java.util.List;

/**
 * 一个控制参数的候选组。
 *
 * <p>缺失成员与真实同名成员由 {@link StatisticBoardControlOption#kind()} 区分，候选组因此不再需要
 * 哨兵值或"歧义上报"：基础范围内名为 {@code __missing__} 的真实成员与其它成员一样是可筛选候选。
 *
 * @param key 控制参数名（与前后端约定的 {@code customer}、{@code module}、{@code function} 等一致）
 * @param options 候选成员，按标签排序；空列表表示基础范围内确实没有成员
 */
public record StatisticBoardControlOptionGroup(
    String key, List<StatisticBoardControlOption> options) {

  public StatisticBoardControlOptionGroup {
    options = options == null ? List.of() : List.copyOf(options);
  }
}
