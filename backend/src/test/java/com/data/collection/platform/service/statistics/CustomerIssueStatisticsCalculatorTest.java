package com.data.collection.platform.service.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.IssueFactNormalizationRules;
import com.data.collection.platform.service.statistics.CustomerIssueStatisticsCalculator.FactFlags;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 客户问题统计计算与指标目录特征测试（S01/S05）。
 *
 * <p>锁定 D/S/N/L/E 资格、F/P/A 差异、比率零分母、周期有效样本与 71 个指标顺序与分组，
 * 供实现演进时对照。
 */
class CustomerIssueStatisticsCalculatorTest {
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 22);

  @Test
  void catalog_hasSeventyOneMetricsInAttachmentOrder() {
    List<String> keys = CustomerIssueStatisticMetricCatalog.metricKeys();
    assertEquals(71, keys.size());
    assertEquals(List.of(
        "defect_total", "defect_fixed", "defect_unfixed", "defect_applied_delay",
        "defect_data_exception", "defect_not_reproduced", "defect_fix_rate",
        "defect_calibrated_fix_rate", "defect_delay_ratio"), keys.subList(0, 9));
    // 七组共同的示例排列：申请延期之后为数据异常、未复现；常规修复率之后紧跟校准后修复率。
    for (String group : List.of("defect", "level1", "level2", "level3", "p1", "p2", "p3")) {
      int appliedDelay = keys.indexOf(group + "_applied_delay");
      assertEquals(
          List.of(group + "_data_exception", group + "_not_reproduced"),
          keys.subList(appliedDelay + 1, appliedDelay + 3),
          group + " 组的两类状态计数必须紧随申请延期");
      int fixRate = keys.indexOf(group + "_fix_rate");
      assertEquals(
          group + "_calibrated_fix_rate", keys.get(fixRate + 1),
          group + " 组的校准率必须紧跟常规修复率");
    }
    assertEquals("suggestion_total", keys.get(36));
    assertEquals("resp_delay_total", keys.get(64));
    assertEquals("fix_delay_total", keys.get(68));
    assertEquals("resolution_cycle_days", keys.get(70));
    assertEquals(
        "延期缺陷占比", CustomerIssueStatisticMetricCatalog.require("defect_delay_ratio").label());
    assertTrue(CustomerIssueStatisticMetricCatalog.isKnown("p3_calibrated_fix_rate"));
    assertFalse(CustomerIssueStatisticMetricCatalog.isKnown("p3_calibrated_fix_rate_v2"));
  }

  @Test
  void test_all_seventy_one_metrics_have_explicit_expected_output_for_a_fixed_fact_set() {
    LocalDateTime day = LocalDateTime.of(2026, 9, 22, 0, 30);
    LocalDateTime older = LocalDateTime.of(2026, 9, 20, 0, 0);
    Map<String, StatisticCellData> cells = compute(
        base("数据异常 & 已修复 & 申请延期", "LEVEL1", "P1")
            .issueId(101L)
            .labels(List.of("数据异常", "已修复", "申请延期"))
            .createdAt(day)
            .researchedAt(day.plusHours(10))
            .delayIssue(true)
            .responseDelayed(true)
            .resolveDelayed(true)
            .regression(true)
            .build(),
        base("未复现 & 已修复/完成", "LEVEL2", "P2")
            .issueId(102L)
            .labels(List.of("未复现", "已修复/完成"))
            .createdAt(older)
            .researchedAt(older.plusHours(20))
            .fixedLabelTime(older.plusDays(1).plusHours(8))
            .delayIssue(true)
            .build(),
        base("处理中", "LEVEL3", "P3")
            .issueId(103L)
            .createdAt(day.plusMinutes(30))
            .researchedAt(day.plusMinutes(30).plusHours(5))
            .responseDelayed(true)
            .build(),
        base("处理中", "SUGGESTION", null)
            .issueId(104L)
            .labels(List.of("建议"))
            .createdAt(older)
            .build());

    Map<String, String> expected = new java.util.LinkedHashMap<>();
    expected.put("defect_total", "3");
    expected.put("defect_fixed", "2");
    expected.put("defect_unfixed", "1");
    expected.put("defect_applied_delay", "1");
    expected.put("defect_data_exception", "1");
    expected.put("defect_not_reproduced", "1");
    expected.put("defect_fix_rate", "66.67%");
    expected.put("defect_calibrated_fix_rate", "0.00%");
    expected.put("defect_delay_ratio", "66.67%");
    expected.put("level1_back", "1");
    expected.put("level1_hang", "0");
    expected.put("level1_other", "0");
    expected.put("level1_total", "1");
    expected.put("level1_fixed", "1");
    expected.put("level1_unfixed", "0");
    expected.put("level1_applied_delay", "1");
    expected.put("level1_data_exception", "1");
    expected.put("level1_not_reproduced", "0");
    expected.put("level1_fix_rate", "100.00%");
    expected.put("level1_calibrated_fix_rate", "/");
    expected.put("level2_total", "1");
    expected.put("level2_fixed", "1");
    expected.put("level2_unfixed", "0");
    expected.put("level2_applied_delay", "0");
    expected.put("level2_data_exception", "0");
    expected.put("level2_not_reproduced", "1");
    expected.put("level2_fix_rate", "100.00%");
    expected.put("level2_calibrated_fix_rate", "/");
    expected.put("level3_total", "1");
    expected.put("level3_fixed", "0");
    expected.put("level3_unfixed", "1");
    expected.put("level3_applied_delay", "0");
    expected.put("level3_data_exception", "0");
    expected.put("level3_not_reproduced", "0");
    expected.put("level3_fix_rate", "0.00%");
    expected.put("level3_calibrated_fix_rate", "0.00%");
    expected.put("suggestion_total", "1");
    expected.put("p1_total", "1");
    expected.put("p1_fixed", "0");
    expected.put("p1_unfixed", "1");
    expected.put("p1_applied_delay", "1");
    expected.put("p1_data_exception", "1");
    expected.put("p1_not_reproduced", "0");
    expected.put("p1_fix_rate", "0.00%");
    expected.put("p1_calibrated_fix_rate", "/");
    expected.put("p2_total", "1");
    expected.put("p2_fixed", "1");
    expected.put("p2_unfixed", "0");
    expected.put("p2_applied_delay", "0");
    expected.put("p2_data_exception", "0");
    expected.put("p2_not_reproduced", "1");
    expected.put("p2_fix_rate", "100.00%");
    expected.put("p2_calibrated_fix_rate", "/");
    expected.put("p3_total", "1");
    expected.put("p3_fixed", "0");
    expected.put("p3_unfixed", "1");
    expected.put("p3_applied_delay", "0");
    expected.put("p3_data_exception", "0");
    expected.put("p3_not_reproduced", "0");
    expected.put("p3_fix_rate", "0.00%");
    expected.put("p3_calibrated_fix_rate", "0.00%");
    expected.put("resp_delay_p1", "1");
    expected.put("resp_delay_p2", "0");
    expected.put("resp_delay_p3", "1");
    expected.put("resp_delay_total", "2");
    expected.put("fix_delay_p1", "1");
    expected.put("fix_delay_p2", "0");
    expected.put("fix_delay_p3", "0");
    expected.put("fix_delay_total", "1");
    expected.put("response_cycle_hours", "12");
    expected.put("resolution_cycle_days", "1.0");

    assertEquals(71, expected.size());
    assertEquals(expected.keySet(), cells.keySet());
    expected.forEach(
        (key, value) -> assertEquals(value, cells.get(key).displayValue(), key));
    assertEquals("3", cells.get("response_cycle_hours").detailParams().get("sampleCount"));
    assertEquals("1", cells.get("resolution_cycle_days").detailParams().get("sampleCount"));
  }

  @Test
  void calibratedMetrics_removeBothExclusionStatesFromBothSides() {
    Map<String, StatisticCellData> cells = compute(
        base("已修复/完成、数据异常", "LEVEL1", "P1").build(),
        base("未复现", "LEVEL1", "P1").build(),
        base("申请延期", "LEVEL1", "P1").build(),
        base("已修复/完成", "LEVEL1", "P1").build());

    assertEquals("4", cells.get("defect_total").displayValue());
    // 普通判据不被校准改写：整体 F 命中 2 条，优先级 P 命中 3 条（未复现本身就是 P 成员）。
    assertEquals("2", cells.get("defect_fixed").displayValue());
    assertEquals("3", cells.get("p1_fixed").displayValue());
    // 两类状态各自命中，不互斥。
    assertEquals("1", cells.get("defect_data_exception").displayValue());
    assertEquals("1", cells.get("defect_not_reproduced").displayValue());
    // 校准分子与分母同时使用 V = {申请延期, 普通已修复}；申请延期不额外剔除。
    assertEquals("50.00%", cells.get("defect_calibrated_fix_rate").displayValue());
    assertEquals("50.00%", cells.get("level1_calibrated_fix_rate").displayValue());
    assertEquals("50.00%", cells.get("p1_calibrated_fix_rate").displayValue());
    assertEquals(5000L, cells.get("defect_calibrated_fix_rate").numericValue());

    assertTrue(flags(base("申请延期", "LEVEL1", "P1").build()).calibratable());
    assertFalse(flags(base("数据异常", "LEVEL1", "P1").build()).calibratable());
    assertFalse(flags(base("未复现", "LEVEL1", "P1").build()).calibratable());
  }

  @Test
  void calibratedRate_isUncomputableWhenGroupIsFullyExcluded() {
    Map<String, StatisticCellData> cells = compute(
        base("数据异常、未复现", "LEVEL2", "P2").build());

    assertEquals("1", cells.get("defect_total").displayValue());
    assertEquals("1", cells.get("defect_data_exception").displayValue());
    assertEquals("1", cells.get("defect_not_reproduced").displayValue());
    // 同议题命中两类只剔除一次：分母归零而不是负数，零分母为不可计算而非 0%。
    assertEquals("/", cells.get("defect_calibrated_fix_rate").displayValue());
    assertNull(cells.get("defect_calibrated_fix_rate").numericValue());
    assertEquals("100.00%", cells.get("p2_fix_rate").displayValue());
  }

  @Test
  void fixedAndPriorityFixed_areIndependentPredicates() {
    Map<String, StatisticCellData> cells =
        compute(base("待合并", "LEVEL1", "P1").build());
    assertTrue(flags(base("待合并", "LEVEL1", "P1").build()).fixed());
    assertFalse(flags(base("待合并", "LEVEL1", "P1").build()).priorityFixed());
    assertEquals("1", cells.get("defect_fixed").displayValue());
    assertEquals("0", cells.get("p1_fixed").displayValue());
    assertEquals("1", cells.get("p1_unfixed").displayValue());

    FactFlags notReproduced = flags(base("未复现", "LEVEL1", "P1").build());
    assertFalse(notReproduced.fixed());
    assertTrue(notReproduced.priorityFixed());

    FactFlags closed = flags(base("处理中", "LEVEL2", "P2").closed().build());
    assertTrue(closed.priorityFixed());
  }

  @Test
  void appliedDelay_isSeparateStateNotRepairBucket() {
    Map<String, StatisticCellData> cells =
        compute(base("申请延期", "LEVEL2", "P1").build(), base("已修复", "LEVEL2", "P1").build());
    assertEquals("1", cells.get("defect_applied_delay").displayValue());
    assertEquals("2", cells.get("defect_total").displayValue());
    assertEquals("1", cells.get("defect_fixed").displayValue());
    assertEquals("1", cells.get("defect_unfixed").displayValue());
    // 延期缺陷占比使用 delay_issue 分子，与申请延期状态数无关。
    assertEquals("0.00%", cells.get("defect_delay_ratio").displayValue());
    assertEquals(0L, cells.get("defect_delay_ratio").numericValue());
  }

  @Test
  void ratioWithoutDenominator_isNullNumericAndSlashDisplay() {
    Map<String, StatisticCellData> cells = compute(base("已修复", "SUGGESTION", null).build());
    assertEquals("/", cells.get("defect_fix_rate").displayValue());
    assertNull(cells.get("defect_fix_rate").numericValue());
    assertTrue(cells.get("defect_fix_rate").drilldown());
    // 建议类只进入建议类列，不污染常规缺陷指标。
    assertEquals("0", cells.get("defect_total").displayValue());
    assertEquals("1", cells.get("suggestion_total").displayValue());
  }

  @Test
  void zeroPercent_remainsDistinctFromNoData() {
    Map<String, StatisticCellData> cells = compute(base("处理中", "LEVEL2", "P2").build());
    assertEquals("0.00%", cells.get("defect_fix_rate").displayValue());
    assertEquals(0L, cells.get("defect_fix_rate").numericValue());
  }

  @Test
  void missingPriorityAndSeverity_doNotFallIntoLowestBucket() {
    Map<String, StatisticCellData> cells =
        compute(base("已修复", "LEVEL2", null).build(), base("已修复", null, "P1").build());
    assertEquals("2", cells.get("defect_total").displayValue());
    assertEquals("0", cells.get("p3_total").displayValue());
    assertEquals("0", cells.get("level3_total").displayValue());
    assertEquals("0", cells.get("level3_fixed").displayValue());
  }

  @Test
  void level1RegressionAndCrash_areMultiMembership() {
    Map<String, StatisticCellData> cells =
        compute(base("已修复", "LEVEL1", "P1").regression(true).crash(true).build());
    assertEquals("1", cells.get("level1_back").displayValue());
    assertEquals("1", cells.get("level1_hang").displayValue());
    assertEquals("0", cells.get("level1_other").displayValue());
    assertEquals("1", cells.get("level1_total").displayValue());
  }

  @Test
  void delayMetrics_excludeMissingPriorityAndKeepTotalsSeparate() {
    FactFlags responseP1 = flags(base("处理中", "LEVEL2", "P1").responseDelayed(true).build());
    FactFlags resolveNoPriority = flags(base("处理中", "LEVEL2", null).resolveDelayed(true).build());
    assertTrue(responseP1.delayEligible());
    assertTrue(resolveNoPriority.delayEligible());
    Map<String, StatisticCellData> cells = compute(responseP1, resolveNoPriority);
    assertEquals("1", cells.get("resp_delay_p1").displayValue());
    assertEquals("1", cells.get("resp_delay_total").displayValue());
    // 缺少优先级不进入 P 桶，也不擅自补入总计。
    assertEquals("0", cells.get("fix_delay_total").displayValue());

    // 接口异常与已关闭的延期议题沿旧延期页排除，不进入 L。
    assertFalse(flags(base("处理中", "LEVEL2", "P1").responseDelayed(true).apiError().build())
        .delayEligible());
    assertFalse(flags(base("处理中", "LEVEL2", "P1").responseDelayed(true).closed().build())
        .delayEligible());
  }

  @Test
  void test_confirmed_example_one_bare_statuses_remove_same_identities_from_both_calibration_sides() {
    Map<String, StatisticCellData> cells = compute(
        customer("已修复", 1L),
        customer("已修复", 2L),
        customer("待合并", 3L),
        customer("未更新", 4L),
        customer("数据异常、已修复", 5L),
        customer("未复现、已修复/完成", 6L),
        customer("申请延期", 7L),
        customer("处理中", 8L),
        customer("处理中", 9L),
        customer("处理中", 10L));

    assertEquals("10", cells.get("defect_total").displayValue());
    assertEquals("6", cells.get("defect_fixed").displayValue());
    assertEquals("60.00%", cells.get("defect_fix_rate").displayValue());
    assertEquals("1", cells.get("defect_data_exception").displayValue());
    assertEquals("1", cells.get("defect_not_reproduced").displayValue());
    // 异常议题即使同时命中已修复，校准分子也必须扣除；分母从 10 降到 8。
    assertEquals("50.00%", cells.get("defect_calibrated_fix_rate").displayValue());
  }

  @Test
  void test_confirmed_example_two_bare_data_exception_keeps_true_zero_and_single_denominator() {
    Map<String, StatisticCellData> cells = compute(
        customer("数据异常", 1L),
        customer("数据异常", 2L),
        customer("数据异常", 3L),
        customer("数据异常", 4L),
        customer("数据异常", 5L),
        customer("处理中", 6L));

    assertEquals("6", cells.get("defect_total").displayValue());
    assertEquals("0", cells.get("defect_fixed").displayValue());
    assertEquals("6", cells.get("defect_unfixed").displayValue());
    assertEquals("5", cells.get("defect_data_exception").displayValue());
    assertEquals("0.00%", cells.get("defect_fix_rate").displayValue());
    assertEquals("0.00%", cells.get("defect_calibrated_fix_rate").displayValue());
    assertEquals(0L, cells.get("defect_calibrated_fix_rate").numericValue());
  }

  @Test
  void test_confirmed_example_three_fully_excluded_population_is_uncomputable() {
    Map<String, StatisticCellData> cells = compute(
        customer("数据异常", 1L),
        customer("数据异常", 2L),
        customer("数据异常", 3L),
        customer("未复现", 4L),
        customer("未复现", 5L),
        customer("数据异常、未复现", 6L));

    assertEquals("6", cells.get("defect_total").displayValue());
    // 异常与未复现可重叠计数，但校准并集只剔除一次；两类计数相加不等于 D。
    assertEquals("4", cells.get("defect_data_exception").displayValue());
    assertEquals("3", cells.get("defect_not_reproduced").displayValue());
    assertEquals("/", cells.get("defect_calibrated_fix_rate").displayValue());
    assertNull(cells.get("defect_calibrated_fix_rate").numericValue());
  }

  @Test
  void test_bare_full_and_half_width_customer_statuses_deduplicate_to_one_member_each() {
    Map<String, StatisticCellData> cells = compute(
        customerLabels(List.of("数据异常", "状态：数据异常", "状态:数据异常", "数据异常 "), 1L),
        customerLabels(List.of("状态:未复现"), 2L),
        customerLabels(List.of("状态 ： 申请延期"), 3L));

    assertEquals("1", cells.get("defect_data_exception").displayValue());
    assertEquals("1", cells.get("defect_not_reproduced").displayValue());
    assertEquals("1", cells.get("defect_applied_delay").displayValue());
  }

  @Test
  void efficiency_noSample_showsZeroWithZeroSampleCount() {
    Map<String, StatisticCellData> cells = compute(base("处理中", "LEVEL2", "P1").build());
    assertEquals("0", cells.get("response_cycle_hours").displayValue());
    assertEquals("0", cells.get("response_cycle_hours").detailParams().get("sampleCount"));
    assertTrue(cells.get("response_cycle_hours").drilldown());
    assertEquals("0", cells.get("resolution_cycle_days").displayValue());
  }

  @Test
  void efficiency_averagesAllValidSamplesAcrossGroups() {
    LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
    FactFlags first =
        flags(base("处理中", "LEVEL2", "P1").createdAt(created).researchedAt(created.plusHours(10)).build());
    FactFlags second =
        flags(base("处理中", "LEVEL2", "P1").createdAt(created).researchedAt(created.plusHours(21)).build());
    Map<String, StatisticCellData> cells = compute(first, second);
    assertEquals("16", cells.get("response_cycle_hours").displayValue());
    assertEquals("2", cells.get("response_cycle_hours").detailParams().get("sampleCount"));
  }

  @Test
  void requirementIdentity_usesLabelsNotReasonOrTitle() {
    assertTrue(flags(base("处理中", "LEVEL2", "P1").labels(List.of("需求")).build()).requirement());
    assertTrue(
        flags(base("处理中", "LEVEL2", "P1").labels(List.of("类别：建议")).build()).requirement());
    assertTrue(
        flags(base("处理中", "LEVEL2", "P1").labels(List.of("需求", "类别：建议")).build())
            .requirement());
    // “需求如此”是排除原因，不是标签身份；关闭后被公共业务排除，不进入 N，也不进入 D。
    FactFlags excluded =
        flags(
            base("处理中", "LEVEL2", "P1")
                .closed()
                .exclusionReason("需求如此")
                .build());
    assertFalse(excluded.requirement());
    assertFalse(excluded.regular());
    // 仅原因文本含“需求”不产生需求身份。
    assertFalse(
        flags(base("处理中", "LEVEL2", "P1").labels(List.of("新增需求问题")).build()).requirement());
  }

  @Test
  void todayMetrics_requireExactCompletionAndHalfOpenBusinessDay() {
    LocalDateTime insideDay = LocalDateTime.of(2026, 9, 22, 9, 30);
    LocalDateTime dayStart = LocalDateTime.of(2026, 9, 22, 0, 0);
    LocalDateTime nextDayStart = LocalDateTime.of(2026, 9, 23, 0, 0);

    FactFlags createdToday = flags(base("处理中", "LEVEL2", "P1").createdAt(insideDay).build());
    assertTrue(createdToday.createdToday());
    assertFalse(createdToday.resolvedToday());

    FactFlags atDayStart = flags(base("处理中", "LEVEL2", "P1").createdAt(dayStart).build());
    assertTrue(atDayStart.createdToday());

    FactFlags atNextDayStart =
        flags(base("处理中", "LEVEL2", "P1").createdAt(nextDayStart).build());
    assertFalse(atNextDayStart.createdToday());

    FactFlags resolvedToday =
        flags(base("已修复/完成", "LEVEL2", "P1").fixedLabelTime(insideDay).build());
    assertTrue(resolvedToday.resolvedToday());

    FactFlags resolvedAtBoundary =
        flags(base("已修复/完成", "LEVEL2", "P1").fixedLabelTime(nextDayStart).build());
    assertFalse(resolvedAtBoundary.resolvedToday());

    // 仅“已修复”不含精确完成成员，不计入今日解决。
    FactFlags legacyFixed =
        flags(base("已修复", "LEVEL2", "P1").fixedLabelTime(insideDay).build());
    assertFalse(legacyFixed.resolvedToday());

    // 缺少创建时间不进入今日新增。
    assertFalse(flags(base("处理中", "LEVEL2", "P1").build()).createdToday());
  }

  @Test
  void todayMetrics_areExposedByDedicatedMetricsNotByOverallFixed() {
    LocalDateTime insideDay = LocalDateTime.of(2026, 9, 22, 9, 30);
    FactFlags resolvedToday =
        flags(base("已修复/完成", "LEVEL2", "P1").fixedLabelTime(insideDay).build());
    // 整体已修复与“今日解决”不是同一口径：待合并同样计入整体已修复，却不计入今日解决。
    FactFlags pending =
        flags(base("待合并", "LEVEL2", "P1").fixedLabelTime(insideDay).build());
    assertTrue(pending.fixed());
    assertFalse(pending.resolvedToday());
    assertTrue(resolvedToday.resolvedToday());
  }

  private static Map<String, StatisticCellData> compute(FactFlags... facts) {
    return CustomerIssueStatisticsCalculator.computeCells(List.of(facts));
  }

  private static Map<String, StatisticCellData> compute(CustomerIssueFact... facts) {
    return CustomerIssueStatisticsCalculator.computeCells(
        java.util.Arrays.stream(facts).map(CustomerIssueStatisticsCalculatorTest::flags).toList());
  }

  private static FactFlags flags(CustomerIssueFact fact) {
    return CustomerIssueStatisticsCalculator.toFlags(fact, BUSINESS_DATE);
  }

  private static FactBuilder base(String bugStatus, String severityLevel, String priorityLevel) {
    return new FactBuilder(bugStatus, severityLevel, priorityLevel);
  }

  private static CustomerIssueFact customer(String labels, long issueId) {
    return customerLabels(List.of(labels.split("、")), issueId);
  }

  private static CustomerIssueFact customerLabels(List<String> labels, long issueId) {
    return base(
            IssueFactNormalizationRules.normalizeBugStatus(labels, 325L), "LEVEL2", "P2")
        .labels(labels)
        .issueId(issueId)
        .build();
  }

  /** 测试事实构造器：只为覆盖规则边界，不引入生产默认值。 */
  private static final class FactBuilder {
    private final String bugStatus;
    private final String severityLevel;
    private final String priorityLevel;
    private long issueId = 31L;
    private String issueState = "opened";
    private String exclusionReason = "";
    private String illegalReason = "";
    private List<String> labels = List.of();
    private boolean delayIssue;
    private boolean responseDelayed;
    private boolean resolveDelayed;
    private boolean regression;
    private boolean crash;
    private boolean level1Other;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime fixedLabelTime;
    private LocalDateTime researchTemplateTime;
    private List<String> customerNames = List.of("客户甲");

    private FactBuilder(String bugStatus, String severityLevel, String priorityLevel) {
      this.bugStatus = bugStatus;
      this.severityLevel = severityLevel;
      this.priorityLevel = priorityLevel;
    }

    FactBuilder closed() {
      this.issueState = "closed";
      return this;
    }

    FactBuilder exclusionReason(String value) {
      this.exclusionReason = value;
      return this;
    }

    FactBuilder apiError() {
      this.illegalReason = "GitLab接口报错";
      return this;
    }

    FactBuilder labels(List<String> value) {
      this.labels = value;
      return this;
    }

    FactBuilder issueId(long value) {
      this.issueId = value;
      return this;
    }

    FactBuilder delayIssue(boolean value) {
      this.delayIssue = value;
      return this;
    }

    FactBuilder responseDelayed(boolean value) {
      this.responseDelayed = value;
      return this;
    }

    FactBuilder resolveDelayed(boolean value) {
      this.resolveDelayed = value;
      return this;
    }

    FactBuilder regression(boolean value) {
      this.regression = value;
      return this;
    }

    FactBuilder crash(boolean value) {
      this.crash = value;
      return this;
    }

    FactBuilder createdAt(LocalDateTime value) {
      this.createdAt = value;
      return this;
    }

    FactBuilder researchedAt(LocalDateTime value) {
      this.researchTemplateTime = value;
      return this;
    }

    FactBuilder fixedLabelTime(LocalDateTime value) {
      this.fixedLabelTime = value;
      return this;
    }

    CustomerIssueFact build() {
      return new CustomerIssueFact(
          "GITLAB",
          "default",
          325L,
          issueId,
          issueId,
          "项目",
          "标题",
          issueState,
          "里程碑",
          "作者",
          "处理人",
          severityLevel,
          priorityLevel,
          bugStatus,
          "功能",
          null,
          List.of("模块A"),
          "功能A",
          null,
          null,
          exclusionReason,
          labels.isEmpty() ? "" : String.join(",", labels),
          illegalReason,
          null,
          !exclusionReason.isEmpty(),
          delayIssue,
          responseDelayed,
          resolveDelayed,
          regression,
          crash,
          level1Other,
          createdAt,
          updatedAt,
          null,
          researchTemplateTime,
          fixedLabelTime,
          labels.contains("需求") || labels.contains("类别：建议"),
          customerNames);
    }
  }
}
