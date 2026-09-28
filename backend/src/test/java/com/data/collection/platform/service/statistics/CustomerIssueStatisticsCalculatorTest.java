package com.data.collection.platform.service.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.statistics.CustomerIssueStatisticsCalculator.FactFlags;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 客户问题统计计算与指标目录特征测试（S01/S05）。
 *
 * <p>锁定 D/S/N/L/E 资格、F/P/A 差异、比率零分母、周期有效样本与 50 个指标顺序与分组，
 * 供实现演进时对照。
 */
class CustomerIssueStatisticsCalculatorTest {
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 22);

  @Test
  void catalog_hasFiftyMetricsInAttachmentOrder() {
    List<String> keys = CustomerIssueStatisticMetricCatalog.metricKeys();
    assertEquals(50, keys.size());
    assertEquals("defect_total", keys.get(0));
    assertEquals("defect_delay_ratio", keys.get(5));
    assertEquals("level1_back", keys.get(6));
    assertEquals("suggestion_total", keys.get(24));
    assertEquals("p1_total", keys.get(25));
    assertEquals("p3_fix_rate", keys.get(39));
    assertEquals("resp_delay_total", keys.get(43));
    assertEquals("fix_delay_total", keys.get(47));
    assertEquals("response_cycle_hours", keys.get(48));
    assertEquals("resolution_cycle_days", keys.get(49));
    assertEquals(
        "延期缺陷占比", CustomerIssueStatisticMetricCatalog.require("defect_delay_ratio").label());
    assertTrue(CustomerIssueStatisticMetricCatalog.isKnown("defect_delay_ratio"));
    assertFalse(CustomerIssueStatisticMetricCatalog.isKnown("defect_delay_ratio_v2"));
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
