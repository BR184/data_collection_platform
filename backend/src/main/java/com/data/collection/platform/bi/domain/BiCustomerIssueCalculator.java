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
import com.data.collection.platform.domain.customerissue.CustomerIssueCauseRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueDelayRules;
import com.data.collection.platform.domain.customerissue.CustomerIssueMetricRules;
import com.data.collection.platform.domain.issue.DefectCauseMetricCatalog;
import com.data.collection.platform.domain.issue.SuggestionMetricRules;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.IssueStatusMembers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** BI 客户问题页纯计算器；只消费同一来源读取中的窄事实快照。 */
public final class BiCustomerIssueCalculator {
  private static final String EXACT_FIXED_MEMBER = "已修复/完成";

  /**
   * 按完整议题身份去重后计算指标和八张图的完整数据集。
   *
   * @param sourceFacts 同一只读来源读取内的窄事实快照
   * @param businessDate 请求固定的业务日，不能为空
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
    List<CustomerIssueFact> defects = facts.stream().filter(BiCustomerIssueCalculator::isRegularDefect).toList();
    List<CustomerIssueFact> requirements = facts.stream()
        .filter(fact -> !fact.excluded() && Boolean.TRUE.equals(fact.customerRequirement()))
        .toList();
    int unknownRequirements = (int) facts.stream()
        .filter(fact -> !fact.excluded() && fact.customerRequirement() == null)
        .count();
    int unknownCreated = (int) defects.stream().filter(fact -> fact.createdAtSource() == null).count();
    int unknownFixed = (int) defects.stream()
        .filter(BiCustomerIssueCalculator::hasExactFixedMember)
        .filter(fact -> fact.fixedLabelTime() == null)
        .count();

    List<Metric> defectMetrics = defectMetrics(defects, businessDate, unknownCreated, unknownFixed);
    List<Metric> requirementMetrics = unknownRequirements == 0
        ? requirementMetrics(requirements)
        : unknownRequirementMetrics();
    List<ModuleDefect> moduleDefects = moduleDefects(defects);
    List<SeverityCount> severity = severityDistribution(defects);
    List<ModuleSeverityCount> moduleSeverity = moduleSeverity(defects);
    CauseResult cause = causeDistribution(defects);
    List<DelayCount> delay = delayAnalysis(defects);
    List<AssigneeWorkload> assignees = assigneeWorkload(defects);
    List<ModuleDemand> moduleDemand = unknownRequirements == 0 ? moduleDemand(requirements) : List.of();
    List<DailyTrend> trend = dailyTrend(defects, businessDate, scopeStartDate, unknownCreated, unknownFixed);

    var data = new BiCustomerIssuePageData(
        List.of(), "", "", businessDate,
        List.of(), List.of(), List.of(),
        defectMetrics, requirementMetrics, moduleDefects, severity, moduleSeverity,
        cause.rows(), cause.unclassifiedCount(), delay, assignees, moduleDemand, trend, facts.size());
    return new Calculation(data, facts.size(), unknownRequirements, unknownCreated, unknownFixed);
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

  private static List<Metric> defectMetrics(
      List<CustomerIssueFact> defects, LocalDate businessDate, int unknownCreated, int unknownFixed) {
    long total = defects.size();
    long fixed = defects.stream().filter(BiCustomerIssueCalculator::isFixed).count();
    long appliedDelay = defects.stream().filter(fact -> CustomerIssueMetricRules.hasAppliedDelay(fact.bugStatus())).count();
    List<CustomerIssueFact> levelOne = defects.stream().filter(fact -> severity(fact).equals("一级")).toList();
    List<CustomerIssueFact> p1 = defects.stream().filter(fact -> priorityBucket(fact).equals("P1")).toList();
    List<CustomerIssueFact> p2 = defects.stream().filter(fact -> priorityBucket(fact).equals("P2")).toList();
    long todayNew = defects.stream().filter(fact -> sameDay(fact.createdAtSource(), businessDate)).count();
    long todayFixed = defects.stream()
        .filter(BiCustomerIssueCalculator::hasExactFixedMember)
        .filter(fact -> sameDay(fact.fixedLabelTime(), businessDate))
        .count();
    return List.of(
        count("defect_total", "总问题数", total),
        count("defect_fixed", "已修复", fixed),
        count("defect_unfixed", "未修复", total - fixed),
        count("defect_applied_delay", "申请延期", appliedDelay),
        ratio("defect_fix_rate", "整体缺陷修复率", fixed, total),
        ratio("level1_fix_rate", "一级缺陷修复率",
            levelOne.stream().filter(BiCustomerIssueCalculator::isFixed).count(), levelOne.size()),
        ratio("p1_fix_rate", "P1修复率",
            p1.stream().filter(BiCustomerIssueCalculator::isPriorityFixed).count(), p1.size()),
        ratio("p2_fix_rate", "P2修复率",
            p2.stream().filter(BiCustomerIssueCalculator::isPriorityFixed).count(), p2.size()),
        unknownCreated > 0
            ? new Metric("today_new", "今日新增问题数", null, todayNew, null, null, "个")
            : count("today_new", "今日新增问题数", todayNew),
        unknownFixed > 0
            ? new Metric("today_resolved", "今日解决问题数", null, todayFixed, null, null, "个")
            : count("today_resolved", "今日解决问题数", todayFixed));
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

  private static List<ModuleSeverityCount> moduleSeverity(List<CustomerIssueFact> defects) {
    Map<String, Map<String, Long>> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (CustomerIssueFact fact : defects) {
      for (String module : modules(fact)) counts.computeIfAbsent(module, ignored -> new LinkedHashMap<>())
          .merge(severity(fact), 1L, Long::sum);
    }
    return counts.entrySet().stream()
        .flatMap(module -> module.getValue().entrySet().stream()
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
      LocalDate created = date(fact.createdAtSource());
      if (created != null && counts.containsKey(created)) counts.get(created).created++;
      if (hasExactFixedMember(fact)) {
        LocalDate fixed = date(fact.fixedLabelTime());
        if (fixed != null && counts.containsKey(fixed)) counts.get(fixed).fixed++;
      }
    }
    return counts.entrySet().stream()
        .map(entry -> new DailyTrend(
            entry.getKey(),
            unknownCreated > 0 ? null : entry.getValue().created,
            unknownFixed > 0 ? null : entry.getValue().fixed))
        .toList();
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

  private static boolean isPriorityFixed(CustomerIssueFact fact) {
    return CustomerIssueMetricRules.isPriorityFixed(fact.bugStatus(), fact.closed());
  }

  private static boolean hasExactFixedMember(CustomerIssueFact fact) {
    return IssueStatusMembers.matchesSelection(fact.bugStatus(), EXACT_FIXED_MEMBER);
  }

  private static boolean sameDay(LocalDateTime time, LocalDate date) {
    return time != null && time.toLocalDate().equals(date);
  }

  private static LocalDate date(LocalDateTime time) {
    return time == null ? null : time.toLocalDate();
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
