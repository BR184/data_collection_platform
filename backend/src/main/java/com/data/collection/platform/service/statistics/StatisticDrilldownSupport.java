package com.data.collection.platform.service.statistics;

/**
 * 旧统计板的最终下钻能力。
 *
 * <p>历史上前端在 {@code cell.drilldown} 之外还要求 {@code numericValue > 0} 才可点击。
 * 契约演进后下钻能力由生产端唯一决定，因此旧看板必须在生产位置把这一历史有效条件显式写入
 * {@code drilldown}：既有单元格点击行为逐项不变，前端只消费该布尔值。
 *
 * <p>新看板不适用本规则：新表所有指标即便 0 或 null 也可下钻，生产端直接声明 {@code true}。
 */
final class StatisticDrilldownSupport {
  private StatisticDrilldownSupport() {}

  /**
   * 计算旧看板单元格的最终下钻能力。
   *
   * @param declared 生产端声明的下钻意图
   * @param numericValue 单元格数值；{@code null} 表示无数据
   * @return 生产端声明可下钻且数值存在且大于 0 时为 {@code true}
   */
  static boolean legacyCellDrilldown(boolean declared, Long numericValue) {
    return declared && numericValue != null && numericValue > 0L;
  }
}
