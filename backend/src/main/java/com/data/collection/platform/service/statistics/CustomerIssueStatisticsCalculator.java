package com.data.collection.platform.service.statistics;

import com.data.collection.platform.domain.customerissue.CustomerIssueDelayRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueEfficiencyRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueMetricRules;
import com.data.collection.platform.domain.issue.SuggestionMetricRules;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.IssueStatusMembers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 客户问题统计纯计算器：把一次窄事实读取结果编译为 D/S/N/L/E 资格、每议题状态与 50 项指标单元格。
 *
 * <p>同一议题的资格、分类与单条周期只计算一次；分组行与总计行都从同一批 {@link FactFlags} 派生，
 * 总计在未展开的去重集合上计算，不累加分组行，也不平均分组均值。
 */
final class CustomerIssueStatisticsCalculator {
  /** 效率精确完成成员（与现有客户响应效率页一致）。 */
  private static final String FIXED_STATUS_MEMBER = "已修复/完成";
  static final String DETAIL_PARAM_METRIC = "metric";
  static final String DETAIL_PARAM_POPULATION = "population";
  static final String DETAIL_PARAM_BASE_SET = "baseSet";
  static final String DETAIL_PARAM_ROW_KEY = "rowKey";

  private CustomerIssueStatisticsCalculator() {}

  /** 单条周期样本：资格与贡献值。 */
  record CycleSample(boolean eligible, long hours, BigDecimal days) {
    static CycleSample ofResponse(CustomerIssueFact fact) {
      return new CycleSample(
          CustomerIssueEfficiencyRules.hasResponseCycle(
              fact.createdAtSource(), fact.researchTemplateTime()),
          CustomerIssueEfficiencyRules.responseCycleHours(
              fact.createdAtSource(), fact.researchTemplateTime()),
          BigDecimal.ZERO);
    }

    static CycleSample ofResolution(CustomerIssueFact fact) {
      boolean eligible =
          CustomerIssueEfficiencyRules.hasResolutionCycle(
              fact.createdAtSource(),
              fact.fixedLabelTime(),
              IssueStatusMembers.matchesSelection(fact.bugStatus(), FIXED_STATUS_MEMBER));
      return new CycleSample(
          eligible,
          0L,
          eligible
              ? CustomerIssueEfficiencyRules.resolutionCycleDays(
                  fact.createdAtSource(), fact.fixedLabelTime())
              : BigDecimal.ZERO);
    }
  }

  /**
   * 每议题只计算一次的资格与状态快照。
   *
   * <p>各谓词分别消费领域纯规则的唯一实现，不在此处复制第二套判断。
   */
  record FactFlags(
      CustomerIssueFact fact,
      boolean regular,
      boolean suggestion,
      boolean requirement,
      boolean efficiencyScope,
      boolean delayEligible,
      boolean level1,
      boolean level2,
      boolean level3,
      boolean level1Back,
      boolean level1Hang,
      boolean level1Other,
      boolean fixed,
      boolean priorityFixed,
      boolean appliedDelay,
      boolean delayIssue,
      boolean responseDelayed,
      boolean resolveDelayed,
      String priorityBucket,
      boolean createdToday,
      boolean resolvedToday,
      CycleSample responseSample,
      CycleSample resolutionSample) {

    boolean hasPriorityBucket() {
      return !priorityBucket.isEmpty();
    }
  }

  /**
   * 编译单条事实的资格与状态。
   *
   * @param businessDate 本次请求固定的业务日，用于“今日新增/今日解决”
   */
  static FactFlags toFlags(CustomerIssueFact fact, LocalDate businessDate) {
    boolean excluded = fact.excluded();
    boolean regular =
        SuggestionMetricRules.isRegularMetricIssue(
            excluded,
            fact.exclusionReason(),
            fact.severityLevel(),
            fact.category());
    boolean suggestion =
        SuggestionMetricRules.isSuggestionColumnIssue(
            excluded,
            fact.exclusionReason(),
            fact.severityLevel(),
            fact.category());
    // R4：需求集合在公共关闭排除之上取标签身份，不应用建议类排除，也不从 D 扣除。
    boolean requirement = !excluded && Boolean.TRUE.equals(fact.customerRequirement());
    boolean efficiencyScope =
        !CustomerIssueEfficiencyRules.isExcludedFromEfficiencyScope(
            fact.closed(), fact.labelNames(), fact.bugStatus());
    boolean delayEligible =
        CustomerIssueDelayRules.isDelayEligible(
            regular,
            CustomerIssueDelayRules.hasGitLabApiError(
                fact.illegalReason(), splitIllegalReasons(fact.illegalReasons())),
            fact.open(),
            fact.responseDelayed(),
            fact.resolveDelayed());
    String severity = fact.severityLevel() == null ? "" : fact.severityLevel();
    boolean level1 = "LEVEL1".equalsIgnoreCase(severity);
    boolean level2 = "LEVEL2".equalsIgnoreCase(severity);
    boolean level3 = "LEVEL3".equalsIgnoreCase(severity);
    return new FactFlags(
        fact,
        regular,
        suggestion,
        requirement,
        efficiencyScope,
        delayEligible,
        level1,
        level2,
        level3,
        level1 && fact.regression(),
        level1 && fact.crash(),
        level1 && fact.level1Other(),
        CustomerIssueMetricRules.isFixedBySummary(fact.bugStatus()),
        CustomerIssueMetricRules.isPriorityFixed(fact.bugStatus(), fact.closed()),
        CustomerIssueMetricRules.hasAppliedDelay(fact.bugStatus()),
        fact.delayIssue(),
        fact.responseDelayed(),
        fact.resolveDelayed(),
        CustomerIssueDelayRules.priorityBucket(fact.priorityLevel()),
        isWithinBusinessDay(fact.createdAtSource(), businessDate),
        isResolvedToday(fact, businessDate),
        CycleSample.ofResponse(fact),
        CycleSample.ofResolution(fact));
  }

  private static List<String> splitIllegalReasons(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : rawValue.split(",")) {
      if (StringUtils.hasText(part)) {
        values.add(part.trim());
      }
    }
    return List.copyOf(values);
  }

  private static boolean isWithinBusinessDay(LocalDateTime time, LocalDate businessDate) {
    if (time == null || businessDate == null) {
      // 缺失创建时间沿用现行范围保留，但不进入“今日新增”。
      return false;
    }
    return !time.isBefore(businessDate.atStartOfDay())
        && time.isBefore(businessDate.plusDays(1).atStartOfDay());
  }

  private static boolean isResolvedToday(CustomerIssueFact fact, LocalDate businessDate) {
    if (businessDate == null || !StringUtils.hasText(fact.bugStatus())) {
      return false;
    }
    boolean exactlyCompleted =
        IssueStatusMembers.matchesSelection(fact.bugStatus(), FIXED_STATUS_MEMBER);
    return exactlyCompleted && isWithinBusinessDay(fact.fixedLabelTime(), businessDate);
  }

  /** 计算一个分组范围的全部指标单元格。 */
  static Map<String, StatisticCellData> computeCells(List<FactFlags> facts) {
    Map<String, StatisticCellData> cells = new LinkedHashMap<>();
    for (CustomerIssueStatisticMetricCatalog.MetricSpec spec :
        CustomerIssueStatisticMetricCatalog.METRICS) {
      cells.put(spec.key(), toCell(spec, facts));
    }
    return cells;
  }

  private static StatisticCellData toCell(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, List<FactFlags> facts) {
    List<FactFlags> base =
        facts.stream().filter(flags -> inBaseSet(spec.baseSet(), flags)).toList();
    List<FactFlags> denominator = base.stream().filter(spec.denominator()).toList();
    return switch (spec.type()) {
      case COUNT -> countCell(spec, denominator);
      case RATIO -> ratioCell(spec, denominator);
      case DURATION_HOURS -> responseCycleCell(spec, denominator);
      case DURATION_DAYS -> resolutionCycleCell(spec, denominator);
      case TEXT -> throw new IllegalStateException("指标目录不包含文本指标: " + spec.key());
    };
  }

  private static boolean inBaseSet(
      CustomerIssueStatisticMetricCatalog.BaseSet baseSet, FactFlags flags) {
    return switch (baseSet) {
      case D -> flags.regular();
      case S -> flags.suggestion();
      case N -> flags.requirement();
      case L -> flags.delayEligible();
      case E -> flags.efficiencyScope();
    };
  }

  private static StatisticCellData countCell(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, List<FactFlags> denominator) {
    long count = denominator.size();
    return new StatisticCellData(
        spec.key(), count, StatisticMetricCalculator.count(count), true, null, detailParams(spec));
  }

  private static StatisticCellData ratioCell(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, List<FactFlags> denominator) {
    long denominatorCount = denominator.size();
    long numeratorCount = denominator.stream().filter(spec.numerator()).count();
    String display = StatisticMetricCalculator.rate(numeratorCount, denominatorCount);
    Long numericValue = StatisticMetricCalculator.ratioSortValue(numeratorCount, denominatorCount);
    return new StatisticCellData(
        spec.key(), numericValue, display, true, null, detailParams(spec));
  }

  private static StatisticCellData responseCycleCell(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, List<FactFlags> denominator) {
    List<Long> samples =
        denominator.stream()
            .map(FactFlags::responseSample)
            .filter(CycleSample::eligible)
            .map(CycleSample::hours)
            .toList();
    long average = CustomerIssueEfficiencyRules.averageResponseHours(samples);
    Map<String, String> params = detailParams(spec);
    params.put("sampleCount", String.valueOf(samples.size()));
    return new StatisticCellData(
        spec.key(), average, samples.isEmpty() ? "0" : String.valueOf(average), true, null, params);
  }

  private static StatisticCellData resolutionCycleCell(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, List<FactFlags> denominator) {
    List<BigDecimal> samples =
        denominator.stream()
            .map(FactFlags::resolutionSample)
            .filter(CycleSample::eligible)
            .map(CycleSample::days)
            .toList();
    BigDecimal average = CustomerIssueEfficiencyRules.averageResolutionDays(samples);
    Map<String, String> params = detailParams(spec);
    params.put("sampleCount", String.valueOf(samples.size()));
    return new StatisticCellData(
        spec.key(),
        samples.isEmpty() ? 0L : average.multiply(BigDecimal.TEN).longValue(),
        samples.isEmpty() ? "0" : average.toPlainString(),
        true,
        null,
        params);
  }

  /** 下钻参数：指标键、默认集合与基础集合，供后端解析下钻集合。 */
  private static Map<String, String> detailParams(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(DETAIL_PARAM_METRIC, spec.key());
    params.put(DETAIL_PARAM_POPULATION, spec.population().name());
    params.put(DETAIL_PARAM_BASE_SET, spec.baseSet().name());
    return params;
  }
}
