package com.data.collection.platform.bi.domain.model;

import java.time.LocalDate;
import java.util.List;

/** BI 客户问题页的完整计算结果；各集合是完整来源范围，不受图表可视窗口裁切。 */
public record BiCustomerIssuePageData(
    List<RangeOption> milestones,
    String selectedMilestoneBusinessKey,
    String selectedMilestoneDisplayName,
    LocalDate businessDate,
    List<MemberOption> customers,
    List<MemberOption> modules,
    List<MemberOption> functions,
    List<Metric> defectMetrics,
    List<Metric> requirementMetrics,
    List<ModuleDefect> moduleDefects,
    List<SeverityCount> severityDistribution,
    List<ModuleSeverityCount> moduleSeverity,
    List<CauseCount> causeDistribution,
    long unclassifiedCauseCount,
    List<DelayCount> delayAnalysis,
    List<AssigneeWorkload> assigneeWorkload,
    List<ModuleDemand> moduleDemand,
    List<DailyTrend> dailyTrend,
    long filteredFactCount) {

  public BiCustomerIssuePageData {
    milestones = milestones == null ? List.of() : List.copyOf(milestones);
    customers = customers == null ? List.of() : List.copyOf(customers);
    modules = modules == null ? List.of() : List.copyOf(modules);
    functions = functions == null ? List.of() : List.copyOf(functions);
    defectMetrics = defectMetrics == null ? List.of() : List.copyOf(defectMetrics);
    requirementMetrics = requirementMetrics == null ? List.of() : List.copyOf(requirementMetrics);
    moduleDefects = moduleDefects == null ? List.of() : List.copyOf(moduleDefects);
    severityDistribution = severityDistribution == null ? List.of() : List.copyOf(severityDistribution);
    moduleSeverity = moduleSeverity == null ? List.of() : List.copyOf(moduleSeverity);
    causeDistribution = causeDistribution == null ? List.of() : List.copyOf(causeDistribution);
    delayAnalysis = delayAnalysis == null ? List.of() : List.copyOf(delayAnalysis);
    assigneeWorkload = assigneeWorkload == null ? List.of() : List.copyOf(assigneeWorkload);
    moduleDemand = moduleDemand == null ? List.of() : List.copyOf(moduleDemand);
    dailyTrend = dailyTrend == null ? List.of() : List.copyOf(dailyTrend);
  }

  public record RangeOption(String businessKey, String displayName) {}

  /** 候选身份保留成员类型；缺失项的 value 为空，真实同名成员仍保留原始名称。 */
  public record MemberOption(String kind, String value, String displayName) {}

  /** 百分率使用0—100量纲；零分母时 percentage、value 均为 null。 */
  public record Metric(
      String key,
      String label,
      Long value,
      Long numerator,
      Long denominator,
      Double percentage,
      String unit) {}

  public record ModuleDefect(String module, long fixedCount, long unfixedCount, long totalCount) {}

  public record SeverityCount(String severity, long count) {}

  public record ModuleSeverityCount(String module, String severity, long count) {}

  public record CauseCount(String groupId, String groupName, String causeId, String causeName, long count) {}

  public record DelayCount(String reason, String severity, long count) {}

  public record AssigneeWorkload(String assignee, long fixedCount, long unfixedCount, long totalCount) {}

  public record ModuleDemand(String module, long resolvedCount, long unresolvedCount, long totalCount) {}

  /** 系列存在未知时间贡献时，该整列用 null 表示不可精确统计，不能把未知日期填成零。 */
  public record DailyTrend(LocalDate date, Long createdCount, Long fixedCount) {}
}
