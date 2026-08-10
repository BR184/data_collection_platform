package com.data.collection.platform.bi.domain;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiMetricTrace;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiPageSection;
import com.data.collection.platform.bi.domain.model.BiSystemTestPageData;
import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import com.data.collection.platform.bi.domain.source.BiSystemTestSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 严格实现 ST-01 至 ST-63 人工确认口径的系统测试计算器。 */
public final class BiSystemTestCalculator {
  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
  private static final BigDecimal LEVEL_ONE_TARGET = new BigDecimal("100.00");
  private static final BigDecimal P1_TARGET = new BigDecimal("90.00");
  private static final BigDecimal P2_TARGET = new BigDecimal("80.00");
  private static final String RULE_VERSION = "bi-system-test-v3";

  /**
   * 计算系统测试页面；缺失来源维度进入显式未知组，缺少不可替代度量时才标记不完整。
   *
   * @param source 已冻结来源版本的有效缺陷和轮次目录
   * @return 系统测试页面强类型响应
   */
  public BiPageResponse<BiSystemTestPageData> calculate(BiSystemTestSource source) {
    Objects.requireNonNull(source, "source");
    // 阶段一：按 issue ID 去重。冲突记录不能任选一条，否则会破坏缺陷总数守恒。
    DistinctIssues distinct = distinctIssues(source.issues());
    List<BiSystemTestSource.IssueRecord> issues = distinct.issues();
    if (source.issues().isEmpty()) {
      return empty(source);
    }
    boolean sourceConsistent = !distinct.conflict()
        && issues.stream().allMatch(issue -> issue.issueId() > 0);
    if (!sourceConsistent) {
      return incompleteSource(source, "来源中存在无效缺陷 ID 或同一缺陷 ID 的冲突记录");
    }

    // 阶段二：集中校验轮次、级别、优先级、模块和原因维度，形成各图表的可用性边界。
    RoundValidation roundValidation = validateRounds(source.rounds(), issues);
    boolean roundsComplete = roundValidation.complete();
    boolean severityComplete = issues.stream().allMatch(issue -> severityIndex(issue.severity()) > 0);
    boolean roundQualityComplete = roundsComplete
        && severityComplete
        && roundConservationComplete(source.rounds(), issues);
    boolean modulesComplete = completeModules(issues);
    boolean causesComplete = completeCauses(issues);
    boolean delaysComplete = issues.stream().filter(BiSystemTestSource.IssueRecord::delayed)
        .allMatch(issue -> !issue.delayCauses().isEmpty()
            && issue.delayCauses().stream().allMatch(Objects::nonNull)
            && severityIndex(issue.severity()) > 0);
    boolean developersComplete = issues.stream().allMatch(issue -> issue.assignee() != null);
    boolean qualityTargetsComplete = severityComplete;
    boolean moduleSectionComplete = modulesComplete && severityComplete;
    boolean developerSectionComplete = developersComplete;
    boolean hasDelayRecords = issues.stream().anyMatch(BiSystemTestSource.IssueRecord::delayed);

    List<BiPageSection> sections = List.of(
        section("source-consistency", "来源一致性", true, ""),
        section("quality-targets", "质量目标及达标情况", qualityTargetsComplete,
            "部分有效缺陷缺少合法严重级别，一级缺陷目标不可完整计算"),
        section("round-quality", "各轮系统测试质量", roundQualityComplete,
            roundsComplete
                ? "部分有效缺陷缺少合法严重级别，轮次缺陷数无法满足守恒关系"
                : "轮次 ID、名称、顺序或缺陷归属不完整"),
        section("severity-distribution", "缺陷级别分布", severityComplete, "部分有效缺陷缺少合法严重级别"),
        section("module-quality", "模块累计发现与当前未修复", moduleSectionComplete,
            "部分缺陷缺少可分组的模块维度或合法严重级别"),
        section("module-repair-targets", "模块一级/P1/P2修复率",
            modulesComplete && severityComplete,
            "部分缺陷缺少模块维度或合法严重级别"),
        section("cause-distribution", "缺陷原因分布", causesComplete,
            "部分缺陷未形成可分组的 BI 版本化原因分类"),
        hasDelayRecords
            ? section("delay-analysis", "申请延期缺陷情况", delaysComplete,
                "部分延期缺陷缺少可分组的延期原因或合法严重级别")
            : new BiPageSection("delay-analysis", "申请延期缺陷情况",
                BiDataStatus.EMPTY, "当前范围没有延期申请缺陷"),
        section("developer-workload", "按指派人统计缺陷数", developerSectionComplete,
            "部分有效缺陷缺少可分组的指派人维度"));

    // 阶段三：按核对表公式计算概览、轮次、原因、延期和人员等图表数据。
    long fixed = issues.stream().filter(BiSystemTestSource.IssueRecord::fixed).count();
    long total = issues.size();
    BiSystemTestPageData data = new BiSystemTestPageData(
        new BiSystemTestPageData.Overview(total, fixed, total - fixed, percent(fixed, total)),
        qualityTargets(issues, severityComplete),
        roundQualityComplete ? rounds(source.rounds(), issues) : List.of(),
        severityComplete ? severity(issues) : null,
        modulesComplete ? modules(issues, severityComplete) : List.of(),
        causesComplete ? causeCategories(issues) : List.of(),
        causesComplete ? causeSubcategories(issues) : List.of(),
        delaysComplete && hasDelayRecords ? delays(issues) : List.of(),
        developerSectionComplete ? developers(issues) : List.of());
    // 阶段四：统一附带来源版本、快照和分区状态，供前端展示和问题追溯使用。
    return BiPageResponse.create(
        "system-test",
        pageStatus(sections),
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        sections,
        traces(),
        data);
  }

  private BiPageResponse<BiSystemTestPageData> empty(BiSystemTestSource source) {
    BiSystemTestPageData data = new BiSystemTestPageData(
        new BiSystemTestPageData.Overview(0, 0, 0, null),
        List.of(),
        List.of(),
        new BiSystemTestPageData.SeverityDistribution(0, 0, 0),
        List.of(), List.of(), List.of(), List.of(), List.of());
    return BiPageResponse.create(
        "system-test",
        BiDataStatus.EMPTY,
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        emptySections(),
        traces(),
        data);
  }

  private BiPageResponse<BiSystemTestPageData> incompleteSource(
      BiSystemTestSource source,
      String message) {
    return BiPageResponse.create(
        "system-test",
        BiDataStatus.INCOMPLETE,
        source.sourceVersion(),
        source.snapshotId(),
        RULE_VERSION,
        incompleteSections(message),
        traces(),
        null);
  }

  private List<BiPageSection> incompleteSections(String sourceMessage) {
    String message = "来源一致性不足，无法计算该区块";
    return List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.INCOMPLETE, sourceMessage),
        new BiPageSection("quality-targets", "质量目标及达标情况", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("round-quality", "各轮系统测试质量", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("severity-distribution", "缺陷级别分布", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("module-quality", "模块累计发现与当前未修复", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("module-repair-targets", "模块一级/P1/P2修复率", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("cause-distribution", "缺陷原因分布", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("delay-analysis", "申请延期缺陷情况", BiDataStatus.INCOMPLETE, message),
        new BiPageSection("developer-workload", "按指派人统计缺陷数", BiDataStatus.INCOMPLETE, message));
  }

  private List<BiPageSection> emptySections() {
    String message = "当前产品版本没有有效系统测试缺陷";
    return List.of(
        new BiPageSection("source-consistency", "来源一致性", BiDataStatus.READY, ""),
        new BiPageSection("quality-targets", "质量目标及达标情况", BiDataStatus.EMPTY, message),
        new BiPageSection("round-quality", "各轮系统测试质量", BiDataStatus.EMPTY, message),
        new BiPageSection("severity-distribution", "缺陷级别分布", BiDataStatus.EMPTY, message),
        new BiPageSection("module-quality", "模块累计发现与当前未修复", BiDataStatus.EMPTY, message),
        new BiPageSection("module-repair-targets", "模块一级/P1/P2修复率", BiDataStatus.EMPTY, message),
        new BiPageSection("cause-distribution", "缺陷原因分布", BiDataStatus.EMPTY, message),
        new BiPageSection("delay-analysis", "申请延期缺陷情况", BiDataStatus.EMPTY, message),
        new BiPageSection("developer-workload", "按指派人统计缺陷数", BiDataStatus.EMPTY, message));
  }

  private List<BiSystemTestPageData.QualityTarget> qualityTargets(
      List<BiSystemTestSource.IssueRecord> issues,
      boolean severityComplete) {
    return List.of(
        target("level-one", "一级缺陷", issues, issue -> "LEVEL1".equals(issue.severity()),
            LEVEL_ONE_TARGET, true, severityComplete),
        target("p1", "P1", issues, issue -> "P1".equals(issue.priority()),
            P1_TARGET, false, true),
        target("p2", "P2", issues, issue -> "P2".equals(issue.priority()),
            P2_TARGET, false, true));
  }

  private BiSystemTestPageData.QualityTarget target(
      String key,
      String label,
      List<BiSystemTestSource.IssueRecord> issues,
      java.util.function.Predicate<BiSystemTestSource.IssueRecord> predicate,
      BigDecimal target,
      boolean exact,
      boolean inputsComplete) {
    if (!inputsComplete) {
      return new BiSystemTestPageData.QualityTarget(
          key, label, null, null, null, target, BiDataStatus.INCOMPLETE, null);
    }
    long total = issues.stream().filter(predicate).count();
    long fixed = issues.stream().filter(predicate).filter(BiSystemTestSource.IssueRecord::fixed).count();
    BigDecimal rate = percent(fixed, total);
    BiDataStatus status = total == 0 ? BiDataStatus.NOT_APPLICABLE : BiDataStatus.READY;
    Boolean achieved = rate == null ? null
        : exact ? rate.compareTo(target) == 0 : rate.compareTo(target) >= 0;
    return new BiSystemTestPageData.QualityTarget(
        key, label, total, fixed, rate, target, status, achieved);
  }

  private List<BiSystemTestPageData.RoundQuality> rounds(
      List<BiSystemTestSource.RoundDefinition> roundDefinitions,
      List<BiSystemTestSource.IssueRecord> issues) {
    return roundDefinitions.stream()
        .sorted(Comparator.comparingInt(BiSystemTestSource.RoundDefinition::roundOrder))
        .map(round -> {
          List<BiSystemTestSource.IssueRecord> values = issues.stream()
              .filter(issue -> round.roundId().equals(issue.roundId())).toList();
          long closed = values.stream().filter(BiSystemTestSource.IssueRecord::fixed).count();
          long open = values.size() - closed;
          return new BiSystemTestPageData.RoundQuality(
              round.roundId(),
              round.roundName(),
              round.roundOrder(),
              severityCount(values, "LEVEL1"),
              severityCount(values, "LEVEL2"),
              severityCount(values, "LEVEL3"),
              (long) values.size(),
              closed,
              open,
              percent(closed, closed + open));
        })
        .filter(round -> round.submittedCount() > 0)
        .toList();
  }

  private BiSystemTestPageData.SeverityDistribution severity(
      List<BiSystemTestSource.IssueRecord> issues) {
    return new BiSystemTestPageData.SeverityDistribution(
        severityCount(issues, "LEVEL1"),
        severityCount(issues, "LEVEL2"),
        severityCount(issues, "LEVEL3"));
  }

  private List<BiSystemTestPageData.ModuleQuality> modules(
      List<BiSystemTestSource.IssueRecord> issues,
      boolean severityComplete) {
    Map<BiSourceDimension, ModuleAccumulator> modules = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      Set<BiSourceDimension> seen = new LinkedHashSet<>();
      for (BiSourceDimension module : issue.modules()) {
        if (seen.add(module)) {
          modules.computeIfAbsent(module, ModuleAccumulator::new)
              .add(issue);
        }
      }
    }
    return modules.values().stream()
        .map(module -> module.toData(severityComplete))
        .sorted(Comparator.comparingLong(BiSystemTestPageData.ModuleQuality::totalCount).reversed())
        .toList();
  }

  private List<BiSystemTestPageData.CauseCategory> causeCategories(
      List<BiSystemTestSource.IssueRecord> issues) {
    Map<String, CauseAccumulator> categories = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      Set<String> seen = new LinkedHashSet<>();
      for (BiSystemTestSource.CauseRef cause : issue.causes()) {
        if (seen.add(cause.categoryId())) {
          categories.computeIfAbsent(cause.categoryId(), ignored -> new CauseAccumulator(
              cause.categoryId(), cause.categoryName())).increment();
        }
      }
    }
    return categories.values().stream()
        .map(value -> new BiSystemTestPageData.CauseCategory(
            value.id, value.name, value.count, percent(value.count, issues.size())))
        .sorted(Comparator.comparingLong(BiSystemTestPageData.CauseCategory::count).reversed())
        .toList();
  }

  private List<BiSystemTestPageData.CauseSubcategory> causeSubcategories(
      List<BiSystemTestSource.IssueRecord> issues) {
    Map<String, SubcauseAccumulator> categories = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      Set<String> seen = new LinkedHashSet<>();
      for (BiSystemTestSource.CauseRef cause : issue.causes()) {
        String key = cause.categoryId() + "\u0000" + cause.subcategoryId();
        if (seen.add(key)) {
          categories.computeIfAbsent(key, ignored -> new SubcauseAccumulator(cause)).increment();
        }
      }
    }
    return categories.values().stream()
        .map(value -> new BiSystemTestPageData.CauseSubcategory(
            value.categoryId,
            value.categoryName,
            value.subcategoryId,
            value.subcategoryName,
            value.count,
            percent(value.count, issues.size())))
        .sorted(Comparator.comparingLong(BiSystemTestPageData.CauseSubcategory::count).reversed())
        .toList();
  }

  private List<BiSystemTestPageData.DelayCell> delays(
      List<BiSystemTestSource.IssueRecord> issues) {
    Map<DelayKey, Long> values = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      if (!issue.delayed()) {
        continue;
      }
      Set<BiSourceDimension> seen = new LinkedHashSet<>(issue.delayCauses());
      for (BiSourceDimension cause : seen) {
        DelayKey key = new DelayKey(cause.displayName(), issue.severity());
        values.merge(key, 1L, Long::sum);
      }
    }
    return values.entrySet().stream()
        .map(entry -> new BiSystemTestPageData.DelayCell(
            entry.getKey().reason(), entry.getKey().severity(), entry.getValue()))
        .toList();
  }

  private List<BiSystemTestPageData.DeveloperWorkload> developers(
      List<BiSystemTestSource.IssueRecord> issues) {
    Map<BiSourceDimension, DeveloperAccumulator> developers = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      developers.computeIfAbsent(issue.assignee(), DeveloperAccumulator::new).add(issue);
    }
    return developers.values().stream()
        .map(DeveloperAccumulator::toData)
        .sorted(Comparator.comparingLong(BiSystemTestPageData.DeveloperWorkload::openCount).reversed()
            .thenComparing(Comparator.comparingLong(BiSystemTestPageData.DeveloperWorkload::totalCount).reversed()))
        .toList();
  }

  private long severityCount(List<BiSystemTestSource.IssueRecord> issues, String severity) {
    return issues.stream().filter(issue -> severity.equals(issue.severity())).count();
  }

  private int severityIndex(String severity) {
    return switch (severity == null ? "" : severity) {
      case "LEVEL1" -> 1;
      case "LEVEL2" -> 2;
      case "LEVEL3" -> 3;
      default -> 0;
    };
  }

  private RoundValidation validateRounds(
      List<BiSystemTestSource.RoundDefinition> rounds,
      List<BiSystemTestSource.IssueRecord> issues) {
    Set<String> ids = new LinkedHashSet<>();
    Set<Integer> orders = new LinkedHashSet<>();
    boolean catalogComplete = !rounds.isEmpty();
    for (BiSystemTestSource.RoundDefinition round : rounds) {
      catalogComplete &= hasText(round.roundId())
          && hasText(round.roundName())
          && round.roundOrder() >= 0
          && ids.add(round.roundId())
          && orders.add(round.roundOrder());
    }
    boolean assignmentsComplete = catalogComplete && issues.stream()
        .allMatch(issue -> hasText(issue.roundId()) && ids.contains(issue.roundId()));
    return new RoundValidation(assignmentsComplete);
  }

  private boolean roundConservationComplete(
      List<BiSystemTestSource.RoundDefinition> rounds,
      List<BiSystemTestSource.IssueRecord> issues) {
    for (BiSystemTestSource.RoundDefinition round : rounds) {
      List<BiSystemTestSource.IssueRecord> values = issues.stream()
          .filter(issue -> round.roundId().equals(issue.roundId()))
          .toList();
      long submitted = values.size();
      long severityTotal = severityCount(values, "LEVEL1")
          + severityCount(values, "LEVEL2")
          + severityCount(values, "LEVEL3");
      long closed = values.stream().filter(BiSystemTestSource.IssueRecord::fixed).count();
      long open = submitted - closed;
      if (submitted != severityTotal || submitted != closed + open) {
        return false;
      }
    }
    return true;
  }

  private boolean completeModules(List<BiSystemTestSource.IssueRecord> issues) {
    for (BiSystemTestSource.IssueRecord issue : issues) {
      if (issue.modules().isEmpty() || issue.modules().stream().anyMatch(Objects::isNull)) {
        return false;
      }
    }
    return true;
  }

  private boolean completeCauses(List<BiSystemTestSource.IssueRecord> issues) {
    Map<String, String> categoryNames = new LinkedHashMap<>();
    Map<String, String> subcategoryNames = new LinkedHashMap<>();
    Map<String, String> categoryBySubcategory = new LinkedHashMap<>();
    for (BiSystemTestSource.IssueRecord issue : issues) {
      if (issue.causes().isEmpty()) {
        return false;
      }
      for (BiSystemTestSource.CauseRef cause : issue.causes()) {
        if (!completeCause(cause)
            || hasConflictingName(categoryNames, cause.categoryId(), cause.categoryName())
            || hasConflictingName(subcategoryNames, cause.subcategoryId(), cause.subcategoryName())) {
          return false;
        }
        String previousCategory = categoryBySubcategory.putIfAbsent(
            cause.subcategoryId(), cause.categoryId());
        if (previousCategory != null && !previousCategory.equals(cause.categoryId())) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean hasConflictingName(
      Map<String, String> namesById,
      String id,
      String name) {
    String previous = namesById.putIfAbsent(id, name);
    return previous != null && !previous.equals(name);
  }

  private boolean completeCause(BiSystemTestSource.CauseRef cause) {
    return cause != null && hasText(cause.categoryId()) && hasText(cause.categoryName())
        && hasText(cause.subcategoryId()) && hasText(cause.subcategoryName());
  }

  private BigDecimal percent(long value, long total) {
    return total <= 0 ? null : BigDecimal.valueOf(value).multiply(ONE_HUNDRED)
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
  }

  private BigDecimal fixRate(
      List<BiSystemTestSource.IssueRecord> issues,
      java.util.function.Predicate<BiSystemTestSource.IssueRecord> predicate) {
    long total = issues.stream().filter(predicate).count();
    long fixed = issues.stream().filter(predicate).filter(BiSystemTestSource.IssueRecord::fixed).count();
    return percent(fixed, total);
  }

  private BiPageSection section(String key, String label, boolean ready, String incompleteMessage) {
    return new BiPageSection(
        key, label, ready ? BiDataStatus.READY : BiDataStatus.INCOMPLETE,
        ready ? "" : incompleteMessage);
  }

  private BiDataStatus pageStatus(List<BiPageSection> sections) {
    return sections.stream().anyMatch(section -> section.status() == BiDataStatus.INCOMPLETE)
        ? BiDataStatus.INCOMPLETE : BiDataStatus.READY;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private DistinctIssues distinctIssues(List<BiSystemTestSource.IssueRecord> issues) {
    Map<Long, BiSystemTestSource.IssueRecord> values = new LinkedHashMap<>();
    boolean conflict = false;
    for (BiSystemTestSource.IssueRecord issue : issues) {
      BiSystemTestSource.IssueRecord previous = values.putIfAbsent(issue.issueId(), issue);
      conflict |= previous != null && !previous.equals(issue);
    }
    return new DistinctIssues(List.copyOf(values.values()), conflict);
  }

  private List<BiMetricTrace> traces() {
    return List.of(
        new BiMetricTrace(
            List.of("ST-01", "ST-04", "ST-05", "ST-06", "ST-07", "ST-08"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField("缺陷 ID", "issue_fact.issue_id"),
                new BiMetricTrace.SourceField("当前修复状态", "issue_fact.is_fixed")),
            "按有效缺陷 ID 去重；未修复数=总数-已修复数；修复率=已修复数/总数",
            "BI服务端"),
        new BiMetricTrace(
            List.of("ST-09", "ST-10", "ST-12", "ST-13", "ST-14", "ST-21", "ST-22", "ST-24", "ST-25", "ST-26", "ST-27", "ST-28", "ST-30", "ST-31", "ST-32"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField("缺陷严重级别", "issue_fact.severity_level"),
                new BiMetricTrace.SourceField("缺陷优先级", "issue_fact.priority_level"),
                new BiMetricTrace.SourceField("当前修复状态", "issue_fact.is_fixed")),
            "一级=100%；P1>=90%；P2>=80%，各指标独立判定",
            "BI服务端"),
        new BiMetricTrace(
            List.of("ST-33", "ST-34", "ST-35", "ST-36", "ST-37", "ST-38", "ST-39", "ST-40", "ST-41", "ST-42"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField("测试轮次 ID", "issue_scope_members.id"),
                new BiMetricTrace.SourceField("测试轮次事实值", "issue_scope_members.source_value / issue_fact.testing_phase"),
                new BiMetricTrace.SourceField("轮次顺序", "issue_scope_members.sort_order"),
                new BiMetricTrace.SourceField("缺陷 ID", "issue_fact.issue_id"),
                new BiMetricTrace.SourceField("缺陷严重级别", "issue_fact.severity_level"),
                new BiMetricTrace.SourceField("当前修复状态", "issue_fact.is_fixed")),
            "轮次提交总数=有效缺陷 ID 去重数=三级严重度之和=已关闭+未关闭；关闭率=已关闭/提交总数",
            "BI服务端"),
        new BiMetricTrace(
            List.of("ST-43", "ST-44", "ST-45", "ST-46", "ST-47", "ST-48", "ST-49",
                "ST-49A", "ST-49B", "ST-49C", "ST-50", "ST-51", "ST-52", "ST-53"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField("模块名称集合", "issue_fact.module_names"),
                new BiMetricTrace.SourceField("当前指派人", "issue_fact.assignee_name")),
            "模块按来源快照内名称维度聚合并允许一缺陷多归属；人员负荷按当前指派人聚合，缺失值进入显式未知组",
            "BI服务端"),
        new BiMetricTrace(
            List.of("ST-54", "ST-55", "ST-56", "ST-57", "ST-58", "ST-59", "ST-60", "ST-61", "ST-62", "ST-63"),
            "数据采集平台",
            List.of(
                new BiMetricTrace.SourceField("原因分类", "issue_fact.reason_category"),
                new BiMetricTrace.SourceField("议题标签", "issue_fact.label_names"),
                new BiMetricTrace.SourceField("是否延期", "issue_fact.delay_issue"),
                new BiMetricTrace.SourceField("延期原因", "issue_fact.delay_cause / delay_reason")),
            "原因按 BI 版本化大类/子类词典匹配并保留未归类；延期仅统计 delay_issue=true，按原因和严重级别交叉聚合",
            "BI服务端"));
  }

  private record DistinctIssues(List<BiSystemTestSource.IssueRecord> issues, boolean conflict) {}

  private record RoundValidation(boolean complete) {}

  private record DelayKey(String reason, String severity) {}

  private static final class CauseAccumulator {
    private final String id;
    private final String name;
    private long count;

    private CauseAccumulator(String id, String name) {
      this.id = id;
      this.name = name;
    }

    private void increment() {
      count++;
    }
  }

  private static final class SubcauseAccumulator {
    private final String categoryId;
    private final String categoryName;
    private final String subcategoryId;
    private final String subcategoryName;
    private long count;

    private SubcauseAccumulator(BiSystemTestSource.CauseRef cause) {
      categoryId = cause.categoryId();
      categoryName = cause.categoryName();
      subcategoryId = cause.subcategoryId();
      subcategoryName = cause.subcategoryName();
    }

    private void increment() {
      count++;
    }
  }

  private final class ModuleAccumulator {
    private final BiSourceDimension module;
    private final List<BiSystemTestSource.IssueRecord> issues = new ArrayList<>();

    private ModuleAccumulator(BiSourceDimension module) {
      this.module = module;
    }

    private void add(BiSystemTestSource.IssueRecord issue) {
      issues.add(issue);
    }

    private BiSystemTestPageData.ModuleQuality toData(boolean severityComplete) {
      long fixed = issues.stream().filter(BiSystemTestSource.IssueRecord::fixed).count();
      return new BiSystemTestPageData.ModuleQuality(
          module,
          issues.size(),
          fixed,
          issues.size() - fixed,
          percent(fixed, issues.size()),
          severityCount(issues, "LEVEL1"),
          severityCount(issues, "LEVEL2"),
          severityCount(issues, "LEVEL3"),
          severityComplete ? fixRate(issues, issue -> "LEVEL1".equals(issue.severity())) : null,
          fixRate(issues, issue -> "P1".equals(issue.priority())),
          fixRate(issues, issue -> "P2".equals(issue.priority())));
    }
  }

  private final class DeveloperAccumulator {
    private final BiSourceDimension assignee;
    private final List<BiSystemTestSource.IssueRecord> issues = new ArrayList<>();

    private DeveloperAccumulator(BiSourceDimension assignee) {
      this.assignee = assignee;
    }

    private void add(BiSystemTestSource.IssueRecord issue) {
      issues.add(issue);
    }

    private BiSystemTestPageData.DeveloperWorkload toData() {
      long fixed = issues.stream().filter(BiSystemTestSource.IssueRecord::fixed).count();
      return new BiSystemTestPageData.DeveloperWorkload(
          assignee,
          issues.size(),
          fixed,
          issues.size() - fixed);
    }
  }
}
