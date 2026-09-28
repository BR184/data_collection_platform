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
    CustomerIssueFact completed = fact(
        10L,
        "LEVEL1",
        "P1",
        "已修复/完成，申请延期",
        LocalDateTime.of(2028, 2, 29, 23, 50),
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
        LocalDateTime.of(2028, 2, 29, 12, 0),
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
    assertEquals(2L, february29.createdCount());
    assertEquals(0L, february29.fixedCount());
    assertEquals(metric(result.data().defectMetrics(), "today_new").value(), march1.createdCount());
    assertEquals(metric(result.data().defectMetrics(), "today_resolved").value(), march1.fixedCount());
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
    assertEquals(1L, result.data().dailyTrend().getLast().createdCount());
    assertNull(result.data().dailyTrend().getLast().fixedCount());
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
    assertNull(result.data().dailyTrend().getLast().createdCount());
    assertEquals(0L, result.data().dailyTrend().getLast().fixedCount());
  }

  @Test
  void naturalDayCountsCrossTheYearBoundaryWithoutChangingTheBusinessDay() {
    LocalDate businessDate = LocalDate.of(2027, 1, 1);
    CustomerIssueFact priorDay = fact(
        22L,
        "LEVEL2",
        "P2",
        "待处理",
        LocalDateTime.of(2026, 12, 31, 23, 59, 59),
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
    assertEquals(1L, december31.createdCount());
    assertEquals(0L, december31.fixedCount());
    assertEquals(1L, january1.createdCount());
    assertEquals(1L, january1.fixedCount());
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
    assertEquals(1L, result.data().dailyTrend().get(1).createdCount());
  }

  @Test
  void rejectsMissingScopeStartDateInsteadOfFallingBackToALiteral() {
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.calculate(List.of(), LocalDate.of(2026, 9, 24), null));
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
