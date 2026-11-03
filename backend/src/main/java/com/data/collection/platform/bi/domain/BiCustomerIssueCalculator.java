package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.AssigneeWorkload;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.CauseCount;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.DailyTrend;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.DelayCount;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.Metric;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.ModuleDefect;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.ModuleDemand;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.ModuleSeverityCount;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.SeverityCount;
import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueCauseRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueDelayRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueMetricRules;
import com.data.collection.platform.domain.issue.DefectCauseMetricCatalog;
import com.data.collection.platform.domain.issue.SuggestionMetricRules;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.IssueStatusMembers;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** BI 客户问题页纯计算器；只消费同一来源读取中的窄事实快照。 */
public final class BiCustomerIssueCalculator {

  /**
   * 按完整议题身份去重后计算指标和八张图的完整数据集。
   *
   * @param sourceFacts 同一只读来源读取内的窄事实快照
   * @param businessDate 请求固定的业务日，不能为空；今日与本自然周窗口都由它推导，不取当前墙钟
   * @param scopeStartDate 客户问题范围的创建下限，作为趋势日轴起点；不能为空
   */
  public Calculation calculate(
      List<CustomerIssueFact> sourceFacts, LocalDate businessDate, LocalDate scopeStartDate) {
    if (businessDate == null) {
      throw new IllegalArgumentException("客户问题 BI 必须固定业务日期");
    }
    if (scopeStartDate == null) {
      throw new IllegalArgumentException("客户问题 BI 必须固定范围创建下限");
    }
    List<CustomerIssueFact> facts = deduplicate(sourceFacts);
    List<CustomerIssueFact> defectFacts = facts.stream().filter(BiCustomerIssueCalculator::isRegularDefect).toList();
    // D 内每议题一次解析状态成员并派生 F/P/A、两类剔除状态与校准资格，供指标与周窗口共用。
    List<Defect> defects = defectFacts.stream().map(fact -> toDefect(fact, businessDate)).toList();
    List<CustomerIssueFact> requirements = facts.stream()
        .filter(fact -> !fact.excluded() && Boolean.TRUE.equals(fact.customerRequirement()))
        .toList();
    int unknownRequirements = (int) facts.stream()
        .filter(fact -> !fact.excluded() && fact.customerRequirement() == null)
        .count();
    int unknownCreated = (int) defects.stream().filter(defect -> defect.fact().createdAtSource() == null).count();
    int unknownFixed = (int) defects.stream()
        .filter(Defect::exactCompletion)
        .filter(defect -> defect.fact().fixedLabelTime() == null)
        .count();

    List<Metric> defectMetrics = defectMetrics(defects, unknownCreated, unknownFixed);
    List<Metric> requirementMetrics = unknownRequirements == 0
        ? requirementMetrics(requirements)
        : unknownRequirementMetrics();
    List<CustomerIssueFact> defectScope = defectFacts;
    List<ModuleDefect> moduleDefects = moduleDefects(defectScope);
    List<SeverityCount> severity = severityDistribution(defectScope);
    List<ModuleSeverityCount> moduleSeverity = moduleSeverity(defectScope);
    CauseResult cause = causeDistribution(defectScope);
    List<DelayCount> delay = delayAnalysis(defectScope);
    List<AssigneeWorkload> assignees = assigneeWorkload(defectScope);
    List<ModuleDemand> moduleDemand = unknownRequirements == 0 ? moduleDemand(requirements) : List.of();
    List<DailyTrend> trend = dailyTrend(defectScope, businessDate, scopeStartDate, unknownCreated, unknownFixed);

    var data = new BiCustomerIssuePageData(
        List.of(), "", "", businessDate,
        List.of(), List.of(), List.of(),
        defectMetrics, requirementMetrics, moduleDefects, severity, moduleSeverity,
        cause.rows(), cause.unclassifiedCount(), delay, assignees, moduleDemand, trend, facts.size());
    return new Calculation(data, facts.size(), unknownRequirements, unknownCreated, unknownFixed);
  }

  /**
   * 单条常规缺陷在本页的去重视图：一次解析的状态成员与其派生资格。
   *
   * @param fact 来源事实
   * @param fixed 整体修复谓词 F
   * @param priorityFixed 优先级修复谓词 P
   * @param appliedDelay 申请延期状态谓词 A
   * @param dataException 命中“数据异常”精确成员（CI-47）
   * @param notReproduced 命中“未复现”精确成员（CI-48）
   * @param calibratable 是否留在校准集合 V 内（两类剔除状态的并集之外）
   * @param exactCompletion 当前状态是否含精确完成成员（今日/本周解决资格）
   * @param severityBucket 归一化严重级别名
   * @param priorityBucket 归一化优先级桶
   * @param createdToday 创建时间落入固定业务日的半开自然日窗口
   * @param resolvedToday 精确完成且最近有效完成时间落入该业务日
   * @param createdThisWeek 创建时间落入本自然周半开窗口
   * @param resolvedThisWeek 精确完成且最近有效完成时间落入本周窗口
   */
  private record Defect(
      CustomerIssueFact fact,
      boolean fixed,
      boolean priorityFixed,
      boolean appliedDelay,
      boolean dataException,
      boolean notReproduced,
      boolean calibratable,
      boolean exactCompletion,
      String severityBucket,
      String priorityBucket,
      boolean createdToday,
      boolean resolvedToday,
      boolean createdThisWeek,
      boolean resolvedThisWeek) {}

  private static Defect toDefect(CustomerIssueFact fact, LocalDate businessDate) {
    List<String> members = IssueStatusMembers.parse(fact.bugStatus());
    boolean exactCompletion = CustomerIssueActivityTimeRules.hasExactCompletionMember(members);
    return new Defect(
        fact,
        CustomerIssueMetricRules.isFixedBySummary(fact.bugStatus()),
        CustomerIssueMetricRules.isPriorityFixed(fact.bugStatus(), fact.closed()),
        CustomerIssueMetricRules.hasAppliedDelay(fact.bugStatus()),
        CustomerIssueMetricRules.hasDataException(members),
        CustomerIssueMetricRules.hasNotReproduced(members),
        CustomerIssueMetricRules.keepsCalibrationPopulation(members),
        exactCompletion,
        severity(fact),
        priorityBucket(fact),
        CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(fact.createdAtSource(), businessDate),
        exactCompletion && CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
            fact.fixedLabelTime(), businessDate),
        CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(fact.createdAtSource(), businessDate),
        exactCompletion && CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
            fact.fixedLabelTime(), businessDate));
  }

  private static List<CustomerIssueFact> deduplicate(List<CustomerIssueFact> sourceFacts) {
    Map<String, CustomerIssueFact> byIdentity = new LinkedHashMap<>();
    for (CustomerIssueFact fact : sourceFacts == null ? List.<CustomerIssueFact>of() : sourceFacts) {
      CustomerIssueFact previous = byIdentity.putIfAbsent(fact.identityKey(), fact);
      if (previous != null && !previous.equals(fact)) {
        throw new IllegalStateException("同一完整议题身份的 BI 来源字段冲突：" + fact.identityKey());
      }
    }
    return List.copyOf(byIdentity.values());
  }

  /**
   * 十七项缺陷指标：概览 6 项、修复率 7 项、今日/本周 4 项，按稳定 key 供前端分组。
   *
   * <p>校准率的分子与分母同时使用 V（D 去除“数据异常/未复现”并集）；整体沿 F、优先级沿 P，
   * 两套判据不统一。分母为零时比率不可计算。
   */
  private static List<Metric> defectMetrics(List<Defect> defects, int unknownCreated, int unknownFixed) {
    long total = defects.size();
    long fixed = defects.stream().filter(Defect::fixed).count();
    List<Defect> calibrated = defects.stream().filter(Defect::calibratable).toList();
    List<Defect> levelOne = defects.stream().filter(defect -> "一级".equals(defect.severityBucket())).toList();
    List<Defect> p1 = defects.stream().filter(defect -> "P1".equals(defect.priorityBucket())).toList();
    List<Defect> p2 = defects.stream().filter(defect -> "P2".equals(defect.priorityBucket())).toList();
    List<Defect> calibratedP1 = calibrated.stream()
        .filter(defect -> "P1".equals(defect.priorityBucket())).toList();
    List<Defect> calibratedP2 = calibrated.stream()
        .filter(defect -> "P2".equals(defect.priorityBucket())).toList();
    long todayNew = defects.stream().filter(Defect::createdToday).count();
    long todayResolved = defects.stream().filter(Defect::resolvedToday).count();
    return List.of(
        count("defect_total", "总问题数", total),
        count("defect_fixed", "已修复", fixed),
        count("defect_unfixed", "未修复", total - fixed),
        count("defect_applied_delay", "申请延期", defects.stream().filter(Defect::appliedDelay).count()),
        count("defect_data_exception", "数据异常", defects.stream().filter(Defect::dataException).count()),
        count("defect_not_reproduced", "未复现", defects.stream().filter(Defect::notReproduced).count()),
        ratio("defect_fix_rate", "整体缺陷修复率", fixed, total),
        ratio("defect_calibrated_fix_rate", "校准后整体缺陷修复率",
            calibrated.stream().filter(Defect::fixed).count(), calibrated.size()),
        ratio("level1_fix_rate", "一级缺陷修复率",
            levelOne.stream().filter(Defect::fixed).count(), levelOne.size()),
        ratio("p1_fix_rate", "P1修复率", p1.stream().filter(Defect::priorityFixed).count(), p1.size()),
        ratio("p1_calibrated_fix_rate", "P1校准后修复率",
            calibratedP1.stream().filter(Defect::priorityFixed).count(), calibratedP1.size()),
        ratio("p2_fix_rate", "P2修复率", p2.stream().filter(Defect::priorityFixed).count(), p2.size()),
        ratio("p2_calibrated_fix_rate", "P2校准后修复率",
            calibratedP2.stream().filter(Defect::priorityFixed).count(), calibratedP2.size()),
        unknownCreated > 0
            ? new Metric("today_new", "今日新增问题数", null, todayNew, null, null, "个")
            : count("today_new", "今日新增问题数", todayNew),
        unknownFixed > 0
            ? new Metric("today_resolved", "今日解决问题数", null, todayResolved, null, null, "个")
            : count("today_resolved", "今日解决问题数", todayResolved),
        unknownCreated > 0
            ? new Metric("week_new", "本周新增问题数", null,
                defects.stream().filter(Defect::createdThisWeek).count(), null, null, "个")
            : count("week_new", "本周新增问题数", defects.stream().filter(Defect::createdThisWeek).count()),
        unknownFixed > 0
            ? new Metric("week_resolved", "本周解决问题数", null,
                defects.stream().filter(Defect::resolvedThisWeek).count(), null, null, "个")
            : count("week_resolved", "本周解决问题数", defects.stream().filter(Defect::resolvedThisWeek).count()));
  }

  private static List<Metric> requirementMetrics(List<CustomerIssueFact> requirements) {
    long total = requirements.size();
    long fixed = requirements.stream().filter(BiCustomerIssueCalculator::isFixed).count();
    long delayed = requirements.stream().filter(fact -> CustomerIssueMetricRules.hasAppliedDelay(fact.bugStatus())).count();
    return List.of(
        count("requirement_total", "总需求数", total),
        count("requirement_resolved", "需求已解决", fixed),
        count("requirement_unresolved", "需求未解决", total - fixed),
        count("requirement_applied_delay", "需求申请延期", delayed));
  }

  private static List<Metric> unknownRequirementMetrics() {
    return List.of(
        unknownCount("requirement_total", "总需求数"),
        unknownCount("requirement_resolved", "需求已解决"),
        unknownCount("requirement_unresolved", "需求未解决"),
        unknownCount("requirement_applied_delay", "需求申请延期"));
  }

  private static Metric count(String key, String label, long value) {
    return new Metric(key, label, value, value, null, null, "个");
  }

  private static Metric unknownCount(String key, String label) {
    return new Metric(key, label, null, null, null, null, "个");
  }

  private static Metric ratio(String key, String label, long numerator, long denominator) {
    Double percentage = denominator == 0 ? null : numerator * 100.0d / denominator;
    Double rounded = percentage == null ? null : Math.round(percentage * 100.0d) / 100.0d;
    return new Metric(key, label, null, numerator, denominator, rounded, "%");
  }

  private static List<ModuleDefect> moduleDefects(List<CustomerIssueFact> defects) {
    Map<String, Counts> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (CustomerIssueFact fact : defects) {
      for (String module : modules(fact)) {
        Counts value = counts.computeIfAbsent(module, ignored -> new Counts());
        value.total++;
        if (isFixed(fact)) value.fixed++;
        else value.unfixed++;
      }
    }
    return counts.entrySet().stream()
        .map(entry -> new ModuleDefect(entry.getKey(), entry.getValue().fixed, entry.getValue().unfixed, entry.getValue().total))
        .sorted(Comparator.comparingLong(ModuleDefect::totalCount).reversed().thenComparing(ModuleDefect::module, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private static List<SeverityCount> severityDistribution(List<CustomerIssueFact> defects) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (CustomerIssueFact fact : defects) counts.merge(severity(fact), 1L, Long::sum);
    return counts.entrySet().stream()
        .map(entry -> new SeverityCount(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(item -> severityOrder(item.severity())))
        .toList();
  }

  /** 模块×严重级别：模块先按级别总数倒序，同总数用稳定模块名；模块内维持严重级别业务顺序。 */
  private static List<ModuleSeverityCount> moduleSeverity(List<CustomerIssueFact> defects) {
    Map<String, Map<String, Long>> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (CustomerIssueFact fact : defects) {
      for (String module : modules(fact)) counts.computeIfAbsent(module, ignored -> new LinkedHashMap<>())
          .merge(severity(fact), 1L, Long::sum);
    }
    return counts.entrySet().stream()
        .sorted(Comparator
            .comparingLong((Map.Entry<String, Map<String, Long>> entry) ->
                entry.getValue().values().stream().mapToLong(Long::longValue).sum()).reversed()
            .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
        .flatMap(module -> module.getValue().entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, Long> severity) -> severityOrder(severity.getKey()))
                .thenComparing(Map.Entry::getKey))
            .map(severity -> new ModuleSeverityCount(module.getKey(), severity.getKey(), severity.getValue())))
        .toList();
  }

  private static CauseResult causeDistribution(List<CustomerIssueFact> defects) {
    List<CauseCount> rows = new ArrayList<>();
    long unclassified = 0;
    for (CustomerIssueFact fact : defects) {
      boolean classified = false;
      for (DefectCauseMetricCatalog.Metric metric : DefectCauseMetricCatalog.METRICS) {
        if (DefectCauseMetricCatalog.containsAny(fact.reasonCategory(), CustomerIssueCauseRules.tokensFor(metric))) {
          rows.add(new CauseCount(metric.groupLabel(), metric.groupLabel(), metric.key(), metric.label(), 1));
          classified = true;
        }
      }
      if (!classified) unclassified++;
    }
    Map<String, CauseCount> aggregated = new LinkedHashMap<>();
    for (CauseCount row : rows) {
      String key = row.groupId() + "|" + row.causeId();
      CauseCount previous = aggregated.get(key);
      aggregated.put(key, previous == null ? row : new CauseCount(
          row.groupId(), row.groupName(), row.causeId(), row.causeName(), previous.count() + row.count()));
    }
    return new CauseResult(List.copyOf(aggregated.values()), unclassified);
  }

  private static List<DelayCount> delayAnalysis(List<CustomerIssueFact> defects) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (CustomerIssueFact fact : defects) {
      if (!CustomerIssueMetricRules.hasAppliedDelay(fact.bugStatus())) continue;
      String reason = firstText(fact.delayCause(), fact.delayReason(), "未标注延期原因");
      counts.merge(reason + "\u0000" + severity(fact), 1L, Long::sum);
    }
    return counts.entrySet().stream().map(entry -> {
      int separator = entry.getKey().indexOf('\u0000');
      return new DelayCount(entry.getKey().substring(0, separator), entry.getKey().substring(separator + 1), entry.getValue());
    }).toList();
  }

  private static List<AssigneeWorkload> assigneeWorkload(List<CustomerIssueFact> defects) {
    Map<String, Counts> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (CustomerIssueFact fact : defects) {
      String name = firstText(fact.assigneeName(), "未指派");
      Counts value = counts.computeIfAbsent(name, ignored -> new Counts());
      value.total++;
      if (isFixed(fact)) value.fixed++;
      else value.unfixed++;
    }
    return counts.entrySet().stream()
        .map(entry -> new AssigneeWorkload(entry.getKey(), entry.getValue().fixed, entry.getValue().unfixed, entry.getValue().total))
        .sorted(Comparator.comparingLong(AssigneeWorkload::totalCount).reversed().thenComparing(AssigneeWorkload::assignee, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private static List<ModuleDemand> moduleDemand(List<CustomerIssueFact> requirements) {
    Map<String, Counts> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (CustomerIssueFact fact : requirements) {
      for (String module : modules(fact)) {
        Counts value = counts.computeIfAbsent(module, ignored -> new Counts());
        value.total++;
        if (isFixed(fact)) value.fixed++;
        else value.unfixed++;
      }
    }
    return counts.entrySet().stream()
        .map(entry -> new ModuleDemand(entry.getKey(), entry.getValue().fixed, entry.getValue().unfixed, entry.getValue().total))
        .sorted(Comparator.comparingLong(ModuleDemand::totalCount).reversed().thenComparing(ModuleDemand::module, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * 累计新增与累计修复：对同一来源版本内的日期贡献作前缀累加，序列非下降。
   *
   * <p>任一必要时间贡献未知时整条累计序列不可计算，不按已知日期补点或按零累计；
   * 最后一点的累计修复等于日轴内精确完成事件贡献总数，不要求等于顶部广义 F 的已修复数。
   */
  private static List<DailyTrend> dailyTrend(
      List<CustomerIssueFact> defects,
      LocalDate businessDate,
      LocalDate scopeStartDate,
      int unknownCreated,
      int unknownFixed) {
    if (defects.isEmpty()) return List.of();
    Map<LocalDate, Counts> counts = new LinkedHashMap<>();
    for (LocalDate day = scopeStartDate; !day.isAfter(businessDate); day = day.plusDays(1)) {
      counts.put(day, new Counts());
    }
    for (CustomerIssueFact fact : defects) {
      LocalDate created = CustomerIssueActivityTimeRules.businessDateFromSourceUtc(fact.createdAtSource());
      if (created != null && counts.containsKey(created)) counts.get(created).created++;
      if (hasExactCompletionMember(fact)) {
        LocalDate fixed = CustomerIssueActivityTimeRules.businessDateFromBusinessClock(fact.fixedLabelTime());
        if (fixed != null && counts.containsKey(fixed)) counts.get(fixed).fixed++;
      }
    }
    List<DailyTrend> trend = new ArrayList<>(counts.size());
    long cumulativeCreated = 0L;
    long cumulativeFixed = 0L;
    for (Map.Entry<LocalDate, Counts> entry : counts.entrySet()) {
      cumulativeCreated += entry.getValue().created;
      cumulativeFixed += entry.getValue().fixed;
      trend.add(new DailyTrend(
          entry.getKey(),
          unknownCreated > 0 ? null : cumulativeCreated,
          unknownFixed > 0 ? null : cumulativeFixed));
    }
    return List.copyOf(trend);
  }

  private static List<String> modules(CustomerIssueFact fact) {
    return fact.moduleNames().isEmpty() ? List.of("未标注模块") : fact.moduleNames();
  }

  private static String severity(CustomerIssueFact fact) {
    String value = fact.severityLevel() == null ? "" : fact.severityLevel().trim();
    return switch (value.toUpperCase(java.util.Locale.ROOT)) {
      case "LEVEL1" -> "一级";
      case "LEVEL2" -> "二级";
      case "LEVEL3" -> "三级";
      case "" -> "未标注级别";
      default -> value;
    };
  }

  private static int severityOrder(String value) {
    return switch (value) {
      case "一级" -> 0;
      case "二级" -> 1;
      case "三级" -> 2;
      case "未标注级别" -> 3;
      default -> 4;
    };
  }

  private static String priorityBucket(CustomerIssueFact fact) {
    return CustomerIssueDelayRules.priorityBucket(fact.priorityLevel());
  }

  private static boolean isRegularDefect(CustomerIssueFact fact) {
    return SuggestionMetricRules.isRegularMetricIssue(
        fact.excluded(), fact.exclusionReason(), fact.severityLevel(), fact.category());
  }

  private static boolean isFixed(CustomerIssueFact fact) {
    return CustomerIssueMetricRules.isFixedBySummary(fact.bugStatus());
  }

  /** 精确完成成员：沿两页共用的活动时间纯规则，不在本页复制状态名。 */
  private static boolean hasExactCompletionMember(CustomerIssueFact fact) {
    return CustomerIssueActivityTimeRules.hasExactCompletionMember(IssueStatusMembers.parse(fact.bugStatus()));
  }

  private static String firstText(String first, String second) {
    return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
  }

  private static String firstText(String first, String second, String fallback) {
    String value = firstText(first, second);
    return value.isBlank() ? fallback : value;
  }

  /** 页面状态判断所需的缺失来源数量；结果数据本身不把缺失映射为业务零值。 */
  public record Calculation(
      BiCustomerIssuePageData data,
      int filteredFactCount,
      int unknownRequirementIdentityCount,
      int unknownCreatedTimeCount,
      int unknownFixedTimeCount) {}

  private record CauseResult(List<CauseCount> rows, long unclassifiedCount) {}

  private static final class Counts {
    long created;
    long fixed;
    long unfixed;
    long total;
  }
}
