package com.data.collection.platform.service.statistics;

import com.data.collection.platform.domain.customerissue.CustomerIssueDelayRules;
import java.util.List;
import java.util.function.Predicate;

/**
 * 客户问题统计板的固定指标目录与列分组（51 列：1 个行维度 + 50 个指标）。
 *
 * <p>每个指标登记稳定 columnKey、显示名称、指标类型、基础集合、分子/分母选择、下钻默认集合与说明。
 * 表头文案不作为指标 ID，也不做动态公式语言；附加的模块/功能维度列由指标目录之外的维度列提供。
 */
final class CustomerIssueStatisticMetricCatalog {
  /** 指标基础集合，用于表头与规则说明披露样本范围。 */
  enum BaseSet {
    D("常规缺陷（沿客户缺陷汇总排除后的非建议类议题）"),
    S("现有建议类议题"),
    L("延期范围（常规缺陷且无接口异常、open、命中延期）"),
    N("客户需求（当前标签命中“需求”或“类别：建议”）"),
    E("效率样本范围（沿现有客户响应效率页）");

    private final String description;

    BaseSet(String description) {
      this.description = description;
    }

    String description() {
      return description;
    }
  }

  enum MetricType {
    COUNT,
    RATIO,
    DURATION_HOURS,
    DURATION_DAYS,
    TEXT
  }

  /** 下钻默认集合；前端不得按列名猜测。 */
  enum Population {
    COUNTED,
    NUMERATOR,
    DENOMINATOR,
    SAMPLE
  }

  /**
   * 指标规格。
   *
   * @param baseSet 基础集合
   * @param denominator 基础集合之上的分母/成员选择
   * @param numerator 比率分子或在分母内进一步限定；COUNT 指标为 {@code null}
   */
  record MetricSpec(
      String key,
      String label,
      MetricType type,
      BaseSet baseSet,
      Population population,
      Predicate<CustomerIssueStatisticsCalculator.FactFlags> denominator,
      Predicate<CustomerIssueStatisticsCalculator.FactFlags> numerator) {}

  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> ALWAYS = flags -> true;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_FIXED =
      CustomerIssueStatisticsCalculator.FactFlags::fixed;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_PRIORITY_FIXED =
      CustomerIssueStatisticsCalculator.FactFlags::priorityFixed;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> HAS_APPLIED_DELAY =
      CustomerIssueStatisticsCalculator.FactFlags::appliedDelay;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> HAS_DELAY_ISSUE =
      CustomerIssueStatisticsCalculator.FactFlags::delayIssue;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_RESPONSE_DELAYED =
      CustomerIssueStatisticsCalculator.FactFlags::responseDelayed;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_RESOLVE_DELAYED =
      CustomerIssueStatisticsCalculator.FactFlags::resolveDelayed;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL1 =
      CustomerIssueStatisticsCalculator.FactFlags::level1;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL2 =
      CustomerIssueStatisticsCalculator.FactFlags::level2;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL3 =
      CustomerIssueStatisticsCalculator.FactFlags::level3;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL1_BACK =
      CustomerIssueStatisticsCalculator.FactFlags::level1Back;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL1_HANG =
      CustomerIssueStatisticsCalculator.FactFlags::level1Hang;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_LEVEL1_OTHER =
      CustomerIssueStatisticsCalculator.FactFlags::level1Other;
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_P1 =
      flags -> CustomerIssueDelayRules.P1.equals(flags.priorityBucket());
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_P2 =
      flags -> CustomerIssueDelayRules.P2.equals(flags.priorityBucket());
  static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> IS_P3 =
      flags -> CustomerIssueDelayRules.P3.equals(flags.priorityBucket());

  static final String DIMENSION_COLUMN_MODULE = "moduleName";
  static final String DIMENSION_COLUMN_FUNCTION = "functionName";

  private static final Predicate<CustomerIssueStatisticsCalculator.FactFlags> D_LEVEL1 = IS_LEVEL1;

  static final List<MetricSpec> METRICS =
      List.of(
          new MetricSpec("defect_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, ALWAYS, null),
          new MetricSpec("defect_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_FIXED, null),
          new MetricSpec("defect_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_FIXED.negate(), null),
          new MetricSpec("defect_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, HAS_APPLIED_DELAY, null),
          new MetricSpec("defect_fix_rate", "整体修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, ALWAYS, IS_FIXED),
          new MetricSpec("defect_delay_ratio", "延期缺陷占比", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, ALWAYS, HAS_DELAY_ISSUE),
          new MetricSpec("level1_back", "一级-回退", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1_BACK, null),
          new MetricSpec("level1_hang", "一级-挂机", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1_HANG, null),
          new MetricSpec("level1_other", "一级-其他", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1_OTHER, null),
          new MetricSpec("level1_total", "缺陷总数", MetricType.COUNT, BaseSet.D, Population.COUNTED, D_LEVEL1, null),
          new MetricSpec("level1_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1.and(IS_FIXED), null),
          new MetricSpec("level1_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1.and(IS_FIXED.negate()), null),
          new MetricSpec("level1_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL1.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("level1_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_LEVEL1, IS_FIXED),
          new MetricSpec("level2_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL2, null),
          new MetricSpec("level2_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL2.and(IS_FIXED), null),
          new MetricSpec("level2_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL2.and(IS_FIXED.negate()), null),
          new MetricSpec("level2_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL2.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("level2_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_LEVEL2, IS_FIXED),
          new MetricSpec("level3_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL3, null),
          new MetricSpec("level3_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL3.and(IS_FIXED), null),
          new MetricSpec("level3_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL3.and(IS_FIXED.negate()), null),
          new MetricSpec("level3_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_LEVEL3.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("level3_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_LEVEL3, IS_FIXED),
          new MetricSpec("suggestion_total", "建议类缺陷数", MetricType.COUNT, BaseSet.S, Population.COUNTED, ALWAYS, null),
          new MetricSpec("p1_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P1, null),
          new MetricSpec("p1_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P1.and(IS_PRIORITY_FIXED), null),
          new MetricSpec("p1_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P1.and(IS_PRIORITY_FIXED.negate()), null),
          new MetricSpec("p1_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P1.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("p1_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_P1, IS_PRIORITY_FIXED),
          new MetricSpec("p2_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P2, null),
          new MetricSpec("p2_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P2.and(IS_PRIORITY_FIXED), null),
          new MetricSpec("p2_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P2.and(IS_PRIORITY_FIXED.negate()), null),
          new MetricSpec("p2_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P2.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("p2_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_P2, IS_PRIORITY_FIXED),
          new MetricSpec("p3_total", "缺陷数", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P3, null),
          new MetricSpec("p3_fixed", "已修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P3.and(IS_PRIORITY_FIXED), null),
          new MetricSpec("p3_unfixed", "未修复", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P3.and(IS_PRIORITY_FIXED.negate()), null),
          new MetricSpec("p3_applied_delay", "申请延期", MetricType.COUNT, BaseSet.D, Population.COUNTED, IS_P3.and(HAS_APPLIED_DELAY), null),
          new MetricSpec("p3_fix_rate", "缺陷修复率", MetricType.RATIO, BaseSet.D, Population.NUMERATOR, IS_P3, IS_PRIORITY_FIXED),
          new MetricSpec("resp_delay_p1", "P1", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESPONSE_DELAYED.and(IS_P1), null),
          new MetricSpec("resp_delay_p2", "P2", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESPONSE_DELAYED.and(IS_P2), null),
          new MetricSpec("resp_delay_p3", "P3", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESPONSE_DELAYED.and(IS_P3), null),
          new MetricSpec("resp_delay_total", "总计", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESPONSE_DELAYED.and(CustomerIssueStatisticsCalculator.FactFlags::hasPriorityBucket), null),
          new MetricSpec("fix_delay_p1", "P1", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESOLVE_DELAYED.and(IS_P1), null),
          new MetricSpec("fix_delay_p2", "P2", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESOLVE_DELAYED.and(IS_P2), null),
          new MetricSpec("fix_delay_p3", "P3", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESOLVE_DELAYED.and(IS_P3), null),
          new MetricSpec("fix_delay_total", "总计", MetricType.COUNT, BaseSet.L, Population.COUNTED, IS_RESOLVE_DELAYED.and(CustomerIssueStatisticsCalculator.FactFlags::hasPriorityBucket), null),
          new MetricSpec("response_cycle_hours", "响应周期(小时)", MetricType.DURATION_HOURS, BaseSet.E, Population.SAMPLE, ALWAYS, null),
          new MetricSpec("resolution_cycle_days", "解决周期(天)", MetricType.DURATION_DAYS, BaseSet.E, Population.SAMPLE, ALWAYS, null));

  static final String SUGGESTION_TOOLTIP = SuggestionMetricSupport.SUGGESTION_HEADER_TOOLTIP;
  static final String ALWAYS_BASE_TOOLTIP =
      "总问题数沿现有非建议类缺陷范围（常规缺陷 D）；标题保留附件原文。";
  static final String DELAY_RATIO_TOOLTIP =
      "延期缺陷占比 = 缺陷数中 delay_issue 命中的议题数 ÷ 缺陷数，不是申请延期状态数 ÷ 缺陷数。";
  static final String RATIO_TOOLTIP = "零分母不可计算时显示“/”，排序置底，与真实 0% 区分。";
  static final String EFFICIENCY_TOOLTIP =
      "有效样本为 0 时显示 0 并同时展示有效样本数 0；跨客户总计按去重原始样本计算，不平均客户均值。";
  static final String DELAY_TOOLTIP =
      "只统计常规缺陷中无 GitLab 接口异常、open 且命中延期的议题；缺失优先级不进入 P 桶及总计。";

  private CustomerIssueStatisticMetricCatalog() {}

  static MetricSpec require(String key) {
    return METRICS.stream()
        .filter(metric -> metric.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知的客户问题统计指标: " + key));
  }

  static boolean isKnown(String key) {
    return METRICS.stream().anyMatch(metric -> metric.key().equals(key));
  }

  /** 一级、二级、三级与 P1/P2/P3 分组共享相同的分组标题集合。 */
  static List<String> metricKeys() {
    return METRICS.stream().map(MetricSpec::key).toList();
  }
}
