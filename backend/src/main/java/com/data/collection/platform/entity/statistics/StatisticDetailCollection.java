package com.data.collection.platform.entity.statistics;

/**
 * 下钻可选的集合定义。
 *
 * <p>集合是下钻请求的固定枚举维度：数量指标只有计数集合，比率指标区分分子与分母，周期指标只有有效样本集合。
 * 前端只能在这些选项之间切换，不能按列名猜测集合语义。
 *
 * @param key 稳定集合键；新看板使用 {@code CustomerIssueStatisticMetricCatalog.Population} 名称
 * @param label 展示标签
 * @param description 该集合包含哪些议题、以及空集合时的解释
 */
public record StatisticDetailCollection(String key, String label, String description) {
  /** 旧看板的单一明细集合键。 */
  public static final String DETAIL_KEY = "DETAIL";

  /**
   * 旧看板的单一明细集合。
   *
   * <p>旧下钻只有一套记录集合，显式声明一项即保持原点击行为；集合数不大于 1 时前端不渲染切换控件。
   */
  public static StatisticDetailCollection detailList() {
    return new StatisticDetailCollection(DETAIL_KEY, "明细", "当前指标对应的明细记录。");
  }
}
