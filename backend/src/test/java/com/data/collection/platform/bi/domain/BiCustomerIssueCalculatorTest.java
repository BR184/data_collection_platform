package com.data.collection.platform.bi.domain;

import static com.data.collection.platform.service.CustomerIssueFactQueryService.CUSTOMER_ISSUE_START_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiCustomerIssueCalculatorTest {
  private final BiCustomerIssueCalculator calculator = new BiCustomerIssueCalculator();

  @Test
  void deduplicatesCompleteIssueIdentityAcrossCustomersAndModulesAndAlignsTodayWithTrend() {
    LocalDate businessDate = LocalDate.of(2028, 3, 1);
    // 存储值为 UTC naive：北京 2/29 上午对应 UTC 2/28 晚间之后同日内，北京 3/1 零点对应 UTC 2/29 16:10。
    CustomerIssueFact completed = fact(
        10L,
        "LEVEL1",
        "P1",
        "已修复/完成，申请延期",
        LocalDateTime.of(2028, 2, 29, 3, 50),
        LocalDateTime.of(2028, 3, 1, 0, 10),
        false,
        List.of("客户甲", "客户乙"),
        List.of("模块A", "模块B"),
        "新增理解偏差");
    CustomerIssueFact pending = fact(
        11L,
        "LEVEL2",
        "P2",
        "待合并",
        LocalDateTime.of(2028, 2, 29, 4, 0),
        null,
        false,
        List.of("客户甲"),
        List.of("模块A"),
        "功能设计遗漏");
    CustomerIssueFact demand = fact(
        12L,
        "SUGGESTION",
        "",
        "待处理",
        LocalDateTime.of(2028, 1, 1, 9, 0),
        null,
        true,
        List.of("客户乙"),
        List.of("模块C", "模块D"),
        "需求问题");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(
            List.of(completed, completed, pending, demand), businessDate, CUSTOMER_ISSUE_START_DATE);

    assertEquals(2L, metric(result.data().defectMetrics(), "defect_total").value());
    assertEquals(2L, metric(result.data().defectMetrics(), "defect_fixed").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_applied_delay").value());
    assertEquals(100.0, metric(result.data().defectMetrics(), "defect_fix_rate").percentage());
    assertEquals(1L, metric(result.data().defectMetrics(), "today_resolved").value());
    assertEquals(1L, metric(result.data().requirementMetrics(), "requirement_total").value());
    assertEquals(2, result.data().moduleDefects().size());
    assertEquals(2L, result.data().moduleDefects().stream()
        .filter(row -> row.module().equals("模块A"))
        .findFirst().orElseThrow().totalCount());
    assertEquals(1L, result.data().moduleDefects().stream()
        .filter(row -> row.module().equals("模块B"))
        .findFirst().orElseThrow().totalCount());
    assertEquals(2, result.data().moduleDemand().size());
    assertEquals(1L, result.data().moduleDemand().stream()
        .filter(row -> row.module().equals("模块C"))
        .findFirst().orElseThrow().totalCount());

    var february29 = result.data().dailyTrend().stream()
        .filter(day -> day.date().equals(LocalDate.of(2028, 2, 29)))
        .findFirst().orElseThrow();
    var march1 = result.data().dailyTrend().stream()
        .filter(day -> day.date().equals(businessDate))
        .findFirst().orElseThrow();
    assertEquals(2L, february29.cumulativeCreatedCount());
    assertEquals(0L, february29.cumulativeFixedCount());
    // 累计序列的逐日增量与今日指标同源，不按位置猜测。
    assertEquals(metric(result.data().defectMetrics(), "today_new").value(),
        march1.cumulativeCreatedCount() - february29.cumulativeCreatedCount());
    assertEquals(metric(result.data().defectMetrics(), "today_resolved").value(),
        march1.cumulativeFixedCount() - february29.cumulativeFixedCount());
    assertEquals(2L, march1.cumulativeCreatedCount());
    assertEquals(1L, march1.cumulativeFixedCount());
    assertEquals(LocalDate.of(2026, 1, 1), result.data().dailyTrend().getFirst().date());
    assertEquals(businessDate, result.data().dailyTrend().getLast().date());
  }

  @Test
  void unknownRequirementIdentityAndCompletionTimeAreDisclosedInsteadOfCountedAsZero() {
    CustomerIssueFact unknown = fact(
        20L,
        "LEVEL1",
        "P1",
        "已修复/完成",
        LocalDateTime.of(2026, 9, 24, 10, 0),
        null,
        null,
        List.of(),
        List.of(),
        "");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(
            List.of(unknown), LocalDate.of(2026, 9, 24), CUSTOMER_ISSUE_START_DATE);

    assertEquals(1, result.unknownRequirementIdentityCount());
    assertEquals(1, result.unknownFixedTimeCount());
    assertNull(metric(result.data().requirementMetrics(), "requirement_total").value());
    assertNull(metric(result.data().defectMetrics(), "today_resolved").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "today_new").value());
    assertEquals(1L, result.data().dailyTrend().getLast().cumulativeCreatedCount());
    assertNull(result.data().dailyTrend().getLast().cumulativeFixedCount());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_total").value());
  }

  @Test
  void missingCreationTimeKeepsTodayAndDailyCreationUnknownInsteadOfZero() {
    CustomerIssueFact unknownCreateTime = fact(
        21L, "LEVEL2", "P2", "待处理", null, null, false, List.of(), List.of(), "");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(
            List.of(unknownCreateTime), LocalDate.of(2026, 9, 24), CUSTOMER_ISSUE_START_DATE);

    assertEquals(1, result.unknownCreatedTimeCount());
    assertNull(metric(result.data().defectMetrics(), "today_new").value());
    assertNull(result.data().dailyTrend().getLast().cumulativeCreatedCount());
    assertEquals(0L, result.data().dailyTrend().getLast().cumulativeFixedCount());
  }

  @Test
  void naturalDayCountsCrossTheYearBoundaryWithoutChangingTheBusinessDay() {
    LocalDate businessDate = LocalDate.of(2027, 1, 1);
    // 存储值为 UTC naive：北京 12-31 23:59:59 对应 UTC 12-31 15:59:59，仍属上一年最后一日。
    CustomerIssueFact priorDay = fact(
        22L,
        "LEVEL2",
        "P2",
        "待处理",
        LocalDateTime.of(2026, 12, 31, 15, 59, 59),
        null,
        false,
        List.of("客户甲"),
        List.of("模块A"),
        "");
    CustomerIssueFact businessDay = fact(
        23L,
        "LEVEL1",
        "P1",
        "已修复/完成",
        LocalDateTime.of(2027, 1, 1, 0, 0),
        LocalDateTime.of(2027, 1, 1, 0, 0),
        false,
        List.of("客户乙"),
        List.of("模块B"),
        "");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(
            List.of(priorDay, businessDay), businessDate, CUSTOMER_ISSUE_START_DATE);

    var december31 = result.data().dailyTrend().stream()
        .filter(day -> day.date().equals(LocalDate.of(2026, 12, 31)))
        .findFirst().orElseThrow();
    var january1 = result.data().dailyTrend().getLast();
    // 跨年后累计继续累加，不按日轴重置。
    assertEquals(1L, december31.cumulativeCreatedCount());
    assertEquals(0L, december31.cumulativeFixedCount());
    assertEquals(2L, january1.cumulativeCreatedCount());
    assertEquals(1L, january1.cumulativeFixedCount());
    assertEquals(1L, metric(result.data().defectMetrics(), "today_new").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "today_resolved").value());
  }

  @Test
  void zeroDenominatorProducesNullPercentagesAndNoSyntheticTrendForEmptyDefects() {
    CustomerIssueFact suggestion = fact(
        30L,
        "SUGGESTION",
        "",
        "待处理",
        LocalDateTime.of(2026, 9, 1, 10, 0),
        null,
        false,
        List.of(),
        List.of(),
        "");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(
            List.of(suggestion), LocalDate.of(2026, 9, 24), CUSTOMER_ISSUE_START_DATE);

    assertNull(metric(result.data().defectMetrics(), "defect_fix_rate").percentage());
    assertEquals(List.of(), result.data().dailyTrend());
  }

  @Test
  void trendAxisStartsAtSuppliedScopeStartDateInsteadOfAHardCodedLiteral() {
    LocalDate scopeStart = LocalDate.of(2026, 9, 1);
    CustomerIssueFact created = fact(
        40L,
        "LEVEL2",
        "P2",
        "待处理",
        LocalDateTime.of(2026, 9, 2, 10, 0),
        null,
        false,
        List.of("客户甲"),
        List.of("模块A"),
        "");

    BiCustomerIssueCalculator.Calculation result =
        calculator.calculate(List.of(created), LocalDate.of(2026, 9, 3), scopeStart);

    assertEquals(
        List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)),
        result.data().dailyTrend().stream()
            .map(BiCustomerIssuePageData.DailyTrend::date)
            .toList());
    assertEquals(0L, result.data().dailyTrend().getFirst().cumulativeCreatedCount());
    assertEquals(1L, result.data().dailyTrend().get(1).cumulativeCreatedCount());
    assertEquals(1L, result.data().dailyTrend().getLast().cumulativeCreatedCount());
  }

  @Test
  void rejectsMissingScopeStartDateInsteadOfFallingBackToALiteral() {
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.calculate(List.of(), LocalDate.of(2026, 9, 24), null));
  }

  /** 十七项缺陷指标的稳定 key 顺序：概览 6 项、修复率 7 项、今日/本周 4 项。 */
  private static final List<String> DEFECT_METRIC_KEYS = List.of(
      "defect_total", "defect_fixed", "defect_unfixed", "defect_applied_delay",
      "defect_data_exception", "defect_not_reproduced",
      "defect_fix_rate", "defect_calibrated_fix_rate", "level1_fix_rate",
      "p1_fix_rate", "p1_calibrated_fix_rate", "p2_fix_rate", "p2_calibrated_fix_rate",
      "today_new", "today_resolved", "week_new", "week_resolved");

  @Test
  void defectMetricsExposeSeventeenStableKeysWithoutDuplicatingOverallRate() {
    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(fact(50L, "LEVEL1", "P1", "已修复/完成",
            LocalDateTime.of(2026, 10, 14, 9, 0), LocalDateTime.of(2026, 10, 14, 9, 30),
            false, List.of("客户甲"), List.of("模块A"), "")),
        LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertEquals(DEFECT_METRIC_KEYS, result.data().defectMetrics().stream()
        .map(BiCustomerIssuePageData.Metric::key).toList());
    assertEquals(4, result.data().requirementMetrics().size());
  }

  @Test
  void calibratedRates_removeBothStatesFromBothSidesAndKeepPriorityCriteria() {
    CustomerIssueFact fixedWithException = fact(
        60L, "LEVEL1", "P1", "已修复/完成、数据异常",
        LocalDateTime.of(2026, 10, 13, 10, 0), LocalDateTime.of(2026, 10, 13, 10, 0),
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact notReproduced = fact(
        61L, "LEVEL1", "P1", "未复现",
        LocalDateTime.of(2026, 10, 13, 11, 0), null,
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact appliedDelay = fact(
        62L, "LEVEL1", "P1", "申请延期",
        LocalDateTime.of(2026, 10, 13, 12, 0), null,
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact plainFixed = fact(
        63L, "LEVEL1", "P1", "已修复/完成",
        LocalDateTime.of(2026, 10, 13, 13, 0), LocalDateTime.of(2026, 10, 13, 13, 0),
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(fixedWithException, notReproduced, appliedDelay, plainFixed),
        LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertEquals(4L, metric(result.data().defectMetrics(), "defect_total").value());
    // 普通率不受校准改写：F 命中两条，P 命中三条（未复现也是 P 成员）。
    assertEquals(50.0, metric(result.data().defectMetrics(), "defect_fix_rate").percentage());
    assertEquals(75.0, metric(result.data().defectMetrics(), "p1_fix_rate").percentage());
    // 校准分母只剩 {申请延期, 普通已修复}：两类状态双侧剔除，申请延期保留。
    assertEquals(2L, metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").denominator());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").numerator());
    assertEquals(50.0, metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").percentage());
    assertEquals(2L, metric(result.data().defectMetrics(), "p1_calibrated_fix_rate").denominator());
    assertEquals(1L, metric(result.data().defectMetrics(), "p1_calibrated_fix_rate").numerator());
    // 状态计数允许重叠命中，不要求各状态相加等于总数。
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_data_exception").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_not_reproduced").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_applied_delay").value());
  }

  @Test
  void overlappingExclusionStatesRemoveOneIssueOnlyOnce() {
    CustomerIssueFact bothStates = fact(
        70L, "LEVEL2", "P2", "数据异常、未复现",
        LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact plain = fact(
        71L, "LEVEL2", "P2", "待处理",
        LocalDateTime.of(2026, 10, 13, 9, 30), null,
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(bothStates, plain), LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertEquals(1L, metric(result.data().defectMetrics(), "defect_data_exception").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_not_reproduced").value());
    // 同议题命中两类只剔除一次，分母为 1 而不是 0。
    assertEquals(1L, metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").denominator());
  }

  @Test
  void calibratedRate_isNullWhenWholeGroupIsExcluded() {
    CustomerIssueFact onlyException = fact(
        72L, "LEVEL3", "P3", "数据异常",
        LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(onlyException), LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    // V 为空：零分母为不可计算，不补 0%。
    assertNull(metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").percentage());
    assertEquals(0L, metric(result.data().defectMetrics(), "defect_calibrated_fix_rate").denominator());
  }

  @Test
  void weekMetrics_countFromBeijingMondayOfTheFixedBusinessDateWeek() {
    // 事实层存储值是 UTC naive（来源规范化契约）：北京周日 23:00 存为 10-11 15:00，北京周一零点存为 10-11 16:00。
    CustomerIssueFact lastWeek = fact(
        80L, "LEVEL2", "P2", "已修复/完成",
        LocalDateTime.of(2026, 10, 11, 15, 0), LocalDateTime.of(2026, 10, 11, 15, 30),
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact thisWeek = fact(
        81L, "LEVEL2", "P2", "已修复/完成",
        LocalDateTime.of(2026, 10, 11, 16, 0), LocalDateTime.of(2026, 10, 13, 0, 0),
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(lastWeek, thisWeek), LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    // 2026-10-14 是周三，本自然周起点为北京 10-12 周一；上海周一零点的贡献必须入周，周日 23:00 不入。
    assertEquals(0L, metric(result.data().defectMetrics(), "today_new").value());
    assertEquals(0L, metric(result.data().defectMetrics(), "today_resolved").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "week_new").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "week_resolved").value());
  }

  @Test
  void todayMetrics_useBeijingNaturalDayOfTheFixedBusinessDate() {
    // 北京周三 10:00 存为 UTC 02:00；同一事实的北京周四零点贡献不得计入周三。
    CustomerIssueFact inToday = fact(
        84L, "LEVEL2", "P2", "已修复/完成",
        LocalDateTime.of(2026, 10, 14, 2, 0), LocalDateTime.of(2026, 10, 14, 10, 0),
        false, List.of("客户甲"), List.of("模块A"), "");
    CustomerIssueFact nextDay = fact(
        85L, "LEVEL2", "P2", "已修复/完成",
        LocalDateTime.of(2026, 10, 14, 16, 0), LocalDateTime.of(2026, 10, 15, 0, 30),
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(inToday, nextDay), LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertEquals(1L, metric(result.data().defectMetrics(), "today_new").value());
    assertEquals(1L, metric(result.data().defectMetrics(), "today_resolved").value());
    // 北京周四零点的贡献已越过周三业务日的本周终点，既不属今日也不入本周。
    assertEquals(1L, metric(result.data().defectMetrics(), "week_new").value());
  }

  @Test
  void weekMetrics_stayUnknownWhenTimeContributionsAreMissing() {
    CustomerIssueFact missingCreated = fact(
        82L, "LEVEL2", "P2", "待处理", null, null, false, List.of(), List.of(), "");
    CustomerIssueFact missingFixedTime = fact(
        83L, "LEVEL2", "P2", "已修复/完成", LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of(), List.of(), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(missingCreated, missingFixedTime), LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertNull(metric(result.data().defectMetrics(), "week_new").value());
    assertNull(metric(result.data().defectMetrics(), "week_resolved").value());
    assertNull(metric(result.data().defectMetrics(), "today_new").value());
  }

  @Test
  void moduleSeverity_ordersModulesByTotalAndKeepsSeverityBusinessOrder() {
    CustomerIssueFact thirdLevel = fact(
        90L, "LEVEL3", "P3", "待处理", LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块B"), "");
    CustomerIssueFact secondLevel = fact(
        91L, "LEVEL2", "P2", "待处理", LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块B"), "");
    CustomerIssueFact anotherThirdLevel = fact(
        92L, "LEVEL3", "P3", "待处理", LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块B"), "");
    CustomerIssueFact firstLevel = fact(
        93L, "LEVEL1", "P1", "待处理", LocalDateTime.of(2026, 10, 13, 9, 0), null,
        false, List.of("客户甲"), List.of("模块A"), "");

    BiCustomerIssueCalculator.Calculation result = calculator.calculate(
        List.of(thirdLevel, secondLevel, anotherThirdLevel, firstLevel),
        LocalDate.of(2026, 10, 14), CUSTOMER_ISSUE_START_DATE);

    assertEquals(
        List.of("模块B|二级", "模块B|三级", "模块A|一级"),
        result.data().moduleSeverity().stream()
            .map(row -> row.module() + "|" + row.severity()).toList());
  }

  private static BiCustomerIssuePageData.Metric metric(
      List<BiCustomerIssuePageData.Metric> metrics, String key) {
    return metrics.stream().filter(metric -> metric.key().equals(key)).findFirst().orElseThrow();
  }

  private static CustomerIssueFact fact(
      long issueId,
      String severity,
      String priority,
      String status,
      LocalDateTime createdAt,
      LocalDateTime fixedAt,
      Boolean requirement,
      List<String> customers,
      List<String> modules,
      String reason) {
    return new CustomerIssueFact(
        "gitlab",
        "default",
        325L,
        issueId,
        issueId + 1000,
        "客户项目",
        "议题 " + issueId,
        "opened",
        "2026客户问题",
        "创建人",
        "",
        severity,
        priority,
        status,
        "",
        reason,
        modules,
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        createdAt,
        null,
        null,
        null,
        fixedAt,
        requirement,
        customers);
  }
}
