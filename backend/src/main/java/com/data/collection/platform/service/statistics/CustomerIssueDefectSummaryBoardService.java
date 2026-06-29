package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticDetailColumn;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDefectSummaryBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-defect-summary";
  private static final String RULE_VERSION = "customer-issue-defect-summary@2026-04-22-v1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final IssueFactBoardRuntimeSupport runtimeSupport;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueDefectSummaryBoardService(
      JsonUtils jsonUtils,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      IssueFactBoardRuntimeSupport runtimeSupport,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.runtimeSupport = runtimeSupport;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseScopeResolver = phaseScopeResolver;
    this.milestoneCatalogService = milestoneCatalogService;
    this.snapshotService = snapshotService;
    this.snapshotRequestFactory = snapshotRequestFactory;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题缺陷汇总",
        "基于 issue_fact 的模块维度客户问题缺陷汇总。",
        "",
        "",
        "模块名",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, "测试阶段", 200),
            StatisticFilterFieldFactory.text("milestoneTitle", "里程碑", 180),
            StatisticFilterFieldFactory.text("moduleName", "模块名", 180),
            StatisticFilterFieldFactory.select(
                "severityLevel",
                "严重程度",
                180,
                IssueDisplayValueSupport.severityFilterOptions(true)),
            StatisticFilterFieldFactory.select(
                "priorityLevel",
                "优先级",
                160,
                List.of(
                    new StatisticFilterOption("P1", "P1"),
                    new StatisticFilterOption("P2", "P2"),
                    new StatisticFilterOption("P3", "P3")))),
        List.of(
            StatisticColumnGroup.withChildren(
                "level1",
                "一级缺陷",
                List.of(
                    new StatisticColumnGroup(
                        "level1-classification",
                        "分类",
                        List.of(
                            leaf("level1_back", "回退(个)", true, "count"),
                            leaf("level1_hang", "挂机(个)", true, "count"),
                            leaf("level1_other", "其他(个)", true, "count"))),
                    new StatisticColumnGroup(
                        "level1-status",
                        "状态统计",
                        List.of(
                            leaf("level1_fixed", "一级缺陷已修复数量", true, "count"),
                            leaf("level1_total", "一级缺陷数量(个)", true, "count"),
                            leaf("level1_rate", "一级缺陷修复率%", false, "ratio"))))),
            new StatisticColumnGroup(
                "level2",
                "二级缺陷",
                List.of(
                    leaf("level2_fixed", "二级缺陷已修复数量", true, "count"),
                    leaf("level2_total", "二级缺陷(个)", true, "count"),
                    leaf("level2_rate", "二级缺陷修复率", false, "ratio"))),
            new StatisticColumnGroup(
                "level3",
                "三级缺陷",
                List.of(
                    leaf("level3_fixed", "三级缺陷已修复数量", true, "count"),
                    leaf("level3_total", "三级缺陷(个)", true, "count"),
                    leaf("level3_rate", "三级缺陷修复率", false, "ratio"))),
            new StatisticColumnGroup(
                "suggestion",
                "建议类缺陷",
                List.of(leaf("suggestion_total", "建议类缺陷(个)", true, "count"))),
            StatisticColumnGroup.withChildren(
                "priority-summary",
                "缺陷级别汇总",
                List.of(
                    new StatisticColumnGroup(
                        "p1",
                        "P1",
                        List.of(
                            leaf("p1_count", "P1级别缺陷", true, "count"),
                            leaf("p1_fix_rate", "P1缺陷修复率(%)", false, "ratio"),
                            leaf("p1_close_rate", "P1缺陷关闭率(%)", false, "ratio"))),
                    new StatisticColumnGroup(
                        "p2",
                        "P2",
                        List.of(
                            leaf("p2_count", "P2级别缺陷", true, "count"),
                            leaf("p2_fix_rate", "P2缺陷修复率(%)", false, "ratio"))),
                    new StatisticColumnGroup(
                        "p3",
                        "P3",
                        List.of(
                            leaf("p3_count", "P3级别缺陷", true, "count"),
                            leaf("p3_fix_rate", "P3缺陷修复率(%)", false, "ratio"))),
                    new StatisticColumnGroup(
                        "summary",
                        "综合汇总",
                        List.of(
                            leaf("module_total", "模块总缺陷数(个)", true, "count"),
                            leaf("defect_ratio", "缺陷占比(%)", false, "ratio"),
                            leaf("delay_defect_ratio", "延期缺陷占比(%)", false, "ratio"),
                            leaf("solved_count", "已修复/未更新", true, "count"),
                            leaf("fix_rate", "修复率(%)", false, "ratio"),
                            leaf("close_rate", "关闭率(%)", false, "ratio"),
                            leaf("open_count", "未关闭缺陷数(个)", true, "count"),
                            leaf("extension_count", "申请延期(个)", true, "count"),
                            leaf("retest_failed_count", "复测未通过缺陷数(个)", true, "count"))))),
            new StatisticColumnGroup(
                "new-issue",
                "新发议题",
                List.of(
                    leaf("new_issue_fixed", "新发议题修复数量", true, "count"),
                    leaf("new_issue_total", "新发议题数量", true, "count"),
                    leaf("new_issue_fix_rate", "新发议题修复率(%)", false, "ratio"),
                    leaf("new_issue_close_rate", "新发议题关闭率(%)", false, "ratio"))),
            new StatisticColumnGroup(
                "legacy",
                "遗留率",
                List.of(
                    leaf("level1_legacy_rate", "一级缺陷遗留率(%)", false, "ratio"),
                    leaf("level2_legacy_count", "二级缺陷遗留数量", true, "count"),
                    leaf("level3_legacy_count", "三级缺陷遗留数量", true, "count"),
                    leaf("level23_legacy_rate", "二三级缺陷遗留率(%)", false, "ratio")))),
        StatisticIssueDetailColumns.customerIssue(
            "议题标题",
            "模块名",
            List.of(
                StatisticIssueDetailColumns.state("议题状态"),
                StatisticIssueDetailColumns.severity("severityLabel", "严重程度", 140),
                StatisticIssueDetailColumns.bugStatus(),
                StatisticIssueDetailColumns.delayCause()),
            List.of(
                StatisticIssueDetailColumns.author("议题提交人"),
                StatisticIssueDetailColumns.assignee("议题处理人", 160)),
            List.of(StatisticIssueDetailColumns.createdAt())),
        10,
        "当前没有可展示的客户问题缺陷统计数据。");
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    Map<String, String> snapshotFilters = customerSnapshotFilters(filters, effectiveFilterGroup);
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, effectiveFilterGroup, buildDefinition()),
        () -> buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    long startedAt = System.currentTimeMillis();
    List<IssueSource> sources = loadBoardScopedSources(filters, effectiveFilterGroup);
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : sources) {
      for (String moduleName : issue.moduleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }
    List<StatisticRowData> rows =
        new ArrayList<>(
            buckets.values().stream()
                .map(bucket -> bucket.toRowData(sources.size()))
                .sorted(Comparator.comparing(StatisticRowData::rowLabel, String.CASE_INSENSITIVE_ORDER))
                .toList());
    rows.add(new AggregateBucket(TOTAL_ROW_LABEL).acceptAll(sources).toRowData(sources.size(), TOTAL_ROW_KEY));
    StatisticBoardDefinition definition = buildDefinition();
    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount =
        definition.columnGroups().stream()
            .flatMap(group -> group.leafColumns().stream())
            .mapToInt(column -> column.drilldown() ? 1 : 0)
            .sum();
    return new StatisticBoardResponse(
        definition,
        withoutReservedFilters(filters),
        effectiveFilterGroup,
        rows,
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount));
  }

  @Override
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(emptyFilterGroup());
    Map<String, String> filters = customerSnapshotFilters(Map.of(), effectiveFilterGroup);
    snapshotService.save(
        snapshotRequest(filters, effectiveFilterGroup, buildDefinition()),
        buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    String selectedMilestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;milestone=" + (StringUtils.hasText(selectedMilestone) ? selectedMilestone : "none"),
        filters,
        definition,
        effectiveFilterGroup);
  }

  private Map<String, String> customerSnapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    return CustomerIssueMilestoneFilterSupport
        .snapshotFilters(withoutReservedFilters(filters), effectiveFilterGroup, 325L)
        .filters();
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    List<IssueSource> scoped =
        loadBoardScopedSources(request.filters(), effectiveFilterGroup).stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题缺陷明细",
        "展示当前模块与指标命中的 issue_fact 明细。",
        buildDefinition().detailColumns(),
        pageSlice.records().stream().map(this::toDetailRecord).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending");
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return runtimeSupport.getRealtimeStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return runtimeSupport.requestRealtimeRefresh(BOARD_KEY, REALTIME_REFRESH_TABLES);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题缺陷汇总规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact 的归一化事实字段，先排除系统测试或回归测试范围，再按模块展开。",
        "同一条议题如果关联多个模块，会分别计入对应模块；总计行仍按议题本身统计。",
        snapshot.flowSteps(),
        buildMetricDefinitions(),
        null);
  }

  private List<IssueSource> loadBoardScopedSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    return buildRuleFlowSnapshot(loadSources(filters), filterGroup).finalSources();
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> valid = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> filtered = valid.stream().filter(issue -> matchesFilterGroup(issue, filterGroup)).toList();
    return new RuleFlowSnapshot(
        filtered,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题事实",
                "从 issue_fact 读取已经归一化的议题事实。",
                initial.size(),
                initial,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "按客户问题 scope profile 收口 issue_fact：优先识别 CC_Product、里程碑与创建时间边界。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "exclude-invalid-issues",
                "排除无效数据",
                "剔除 issue_fact.is_excluded = true 的议题，避免异常样本干扰汇总结果。",
                scoped.size(),
                valid,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "condition-filter",
                "应用页面筛选",
                "应用当前页面条件筛选和顶部测试阶段切换。",
                valid.size(),
                filtered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "module-expand",
                "按模块展开",
                "同一条议题可能属于多个模块，模块行会分别计入；总计行仍按议题本身统计。",
                filtered.size(),
                filtered.stream().mapToLong(issue -> issue.moduleNames().size()).sum(),
                filtered,
                this::toRuleFlowSample
            )));
  }

  private boolean matchesFilterGroup(IssueSource issue, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesFilterCondition(issue, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private boolean matchesFilterCondition(IssueSource issue, StatisticFilterCondition condition) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if (CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
      return CustomerIssueMilestoneFilterSupport.matches(
          issue.milestoneTitle(),
          issue.testingPhase(),
          CustomerIssueMilestoneFilterSupport.normalizeLegacyTestingPhase(
              new StatisticFilterGroup("AND", List.of(condition)), phaseScopeResolver));
    }
    List<String> actualValues = valuesForFilterField(issue, condition.fieldKey());
    if (condition.usesLabelGroup()) {
      return matchesSetOperator(actualValues, condition.values(), condition.operator());
    }
    return switch (Objects.toString(condition.operator(), "")) {
      case "isEmpty" -> actualValues.stream().allMatch(value -> trimToNull(value) == null);
      case "isNotEmpty" -> actualValues.stream().anyMatch(value -> trimToNull(value) != null);
      case "ne" -> actualValues.stream().noneMatch(value -> equalsIgnoreCase(value, condition.value()));
      case "contains" -> actualValues.stream().anyMatch(value -> containsIgnoreCase(value, condition.value()));
      case "notContains" -> actualValues.stream().noneMatch(value -> containsIgnoreCase(value, condition.value()));
      default -> actualValues.stream().anyMatch(value -> equalsIgnoreCase(value, condition.value()));
    };
  }

  private List<String> valuesForFilterField(IssueSource issue, String fieldKey) {
    return switch (fieldKey) {
      case "projectName" -> List.of(Objects.toString(issue.projectName(), ""));
      case CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD -> List.of(Objects.toString(issue.milestoneTitle(), ""));
      case "milestoneTitle" -> List.of(Objects.toString(issue.milestoneTitle(), ""));
      case "moduleName" -> issue.moduleNames();
      case "severityLevel" -> List.of(Objects.toString(issue.severityLevel(), ""));
      case "priorityLevel" -> List.of(Objects.toString(issue.priorityLevel(), ""));
      default -> List.of();
    };
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService.listMilestones(), phaseScopeResolver);
  }

  private boolean matchesSetOperator(List<String> actualValues, List<String> expectedValues, String operator) {
    List<String> safeActual = actualValues == null ? List.of() : actualValues;
    List<String> safeExpected = expectedValues == null ? List.of() : expectedValues;
    if (safeExpected.stream().noneMatch(value -> trimToNull(value) != null)) {
      return false;
    }
    if ("partialContainsAny".equals(operator)) {
      return safeActual.stream()
          .filter(value -> trimToNull(value) != null)
          .anyMatch(
              actual ->
                  safeExpected.stream()
                      .filter(value -> trimToNull(value) != null)
                      .anyMatch(expected -> containsIgnoreCase(actual, expected)));
    }
    boolean intersects =
        safeActual.stream()
            .filter(value -> trimToNull(value) != null)
            .anyMatch(
                actual -> safeExpected.stream().anyMatch(expected -> equalsIgnoreCase(actual, expected)));
    boolean containsAll =
        safeExpected.stream()
            .filter(value -> trimToNull(value) != null)
            .allMatch(
                expected -> safeActual.stream().anyMatch(actual -> equalsIgnoreCase(actual, expected)));
    return switch (normalizeSetOperator(operator)) {
      case "notIntersects" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> intersects;
    };
  }

  private String normalizeSetOperator(String operator) {
    if ("ne".equals(operator)) {
      return "notIntersects";
    }
    if ("eq".equals(operator)) {
      return "intersects";
    }
    return operator;
  }

  private boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = trimToNull(left);
    String safeRight = trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private boolean containsIgnoreCase(String left, String right) {
    String safeLeft = trimToNull(left);
    String safeRight = trimToNull(right);
    return safeLeft != null
        && safeRight != null
        && safeLeft.toLowerCase(Locale.ROOT).contains(safeRight.toLowerCase(Locale.ROOT));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title()
                        + (issue.moduleNames().isEmpty() ? "" : " | 模块: " + String.join("、", issue.moduleNames())));
  }
  private List<StatisticRuleMetricDefinition> buildMetricDefinitions() {
    return List.of(
        new StatisticRuleMetricDefinition("level1", "一级缺陷", "一级缺陷基于 severity_level = LEVEL1，再拆分回退、挂机、其他一级。", "一级缺陷修复率 = 一级缺陷已修复数量 / 一级缺陷总数", null),
        new StatisticRuleMetricDefinition("priority-summary", "缺陷级别汇总", "P1/P2/P3 与一级/二级/三级缺陷是两套独立统计体系，直接按 priority_level 聚合。", "Pn 修复率 = 已修复 Pn 数量 / Pn 总数；Pn 关闭率 = 已关闭 Pn 数量 / Pn 总数", null),
        new StatisticRuleMetricDefinition("summary", "综合汇总", "综合区展示模块总缺陷、缺陷占比、延期占比、已修复/未更新、修复率、关闭率、未关闭数量、申请延期和复测未通过。", "修复率 = 已修复/未更新数量 / 模块总缺陷数；缺陷占比 = 当前模块缺陷数 / 当前范围全部缺陷数", null),
        new StatisticRuleMetricDefinition("new-issue", "新发议题", "新发议题按“排除历史遗留”后的议题统计。", "新发议题修复率 = 已修复/未更新的新发议题数量 / 新发议题总数", null),
        new StatisticRuleMetricDefinition("legacy", "遗留率", "严格按老平台 ModuleTableRow 的遗留率公式计算。", "一级缺陷遗留率 = (一级缺陷总数 - 一级缺陷已修复数量) / 一级缺陷总数；二级/三级缺陷遗留数量使用未修复口径；二三级缺陷遗留率 = 已修复的二三级缺陷数 / 模块总缺陷数", null));
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    try {
      return runtimeSupport
          .loadFacts(
              withoutReservedFilters(filters),
              source -> customerIssueScopeProfile.matches(source.scopeContext()))
          .stream()
          .map(this::toIssueSource)
          .toList();
    } catch (Exception e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private IssueSource toIssueSource(StatisticIssueFactSource source) {
    return new IssueSource(
        source.id(),
        source.iid(),
        source.sourceInstance(),
        source.title(),
        source.projectId(),
        source.projectName(),
        source.milestoneTitle(),
        source.authorName(),
        source.createdAt(),
        source.updatedAt(),
        source.closedAt(),
        source.issueState(),
        source.testingPhase(),
        source.systemTestLabel(),
        source.severityLevel(),
        source.priorityLevel(),
        source.excluded(),
        "",
        source.bugStatus(),
        source.category(),
        source.delayCause(),
        source.assigneeName(),
        source.fixed(),
        source.delayIssue(),
        source.regression(),
        source.crash(),
        source.level1Other(),
        false,
        "",
        source.legacy(),
        source.moduleNames(),
        source.labels());
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName(), issue.id(), issue.labels());
    record.put("title", issue.title());
    record.put("moduleNames", String.join("、", issue.moduleNames()));
    record.put("severityLabel", issue.severityLabel());
    record.put("bugStatus", issue.bugStatus());
    record.put("delayCause", issue.delayCause());
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    return record;
  }

  private Predicate<IssueSource> matchesMetric(String key) {
    return switch (key) {
      case "level1_back" -> IssueSource::isLevel1Back;
      case "level1_hang" -> IssueSource::isLevel1Hang;
      case "level1_other" -> IssueSource::isLevel1Other;
      case "level1_fixed" -> issue -> issue.isLevel1() && issue.isFixedByLegacySummary();
      case "level1_total" -> IssueSource::isLevel1;
      case "level2_fixed" -> issue -> issue.isLevel2() && issue.isFixedByLegacySummary();
      case "level2_total" -> IssueSource::isLevel2;
      case "level3_fixed" -> issue -> issue.isLevel3() && issue.isFixedByLegacySummary();
      case "level3_total" -> IssueSource::isLevel3;
      case "suggestion_total" -> IssueSource::isSuggestion;
      case "p1_count" -> issue -> issue.isPriority("P1");
      case "p2_count" -> issue -> issue.isPriority("P2");
      case "p3_count" -> issue -> issue.isPriority("P3");
      case "solved_count" -> IssueSource::isFixedByLegacySummary;
      case "open_count" -> issue -> !issue.isClosed();
      case "extension_count" -> IssueSource::hasExtensionLabel;
      case "retest_failed_count" -> IssueSource::isRetestFailed;
      case "new_issue_fixed" -> issue -> issue.isNewIssueByLegacySummary() && issue.isFixedByLegacySummary();
      case "new_issue_total" -> IssueSource::isNewIssue;
      case "level2_legacy_count" -> issue -> issue.isLevel2() && issue.isUnfixedByLegacySummary();
      case "level3_legacy_count" -> issue -> issue.isLevel3() && issue.isUnfixedByLegacySummary();
      default -> issue -> true;
    };
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey) || TOTAL_ROW_KEY.equals(rowKey) || issue.moduleNames().contains(rowKey);
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.moduleNames()));
          case "severityLabel" -> SortSupport.nullableString(IssueSource::severityLabel);
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
          case "delayCause" -> SortSupport.nullableString(IssueSource::delayCause);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueSource::assigneeName);
          case "state" -> SortSupport.nullableComparable(issue -> issue.isClosed() ? 1 : 0);
          case "createdAt" -> SortSupport.nullableComparable(IssueSource::createdAt);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private static String rate(long numerator, long denominator) {
    return StatisticMetricCalculator.rate(numerator, denominator);
  }

  private static String percent(double value) {
    return StatisticMetricCalculator.percent(value);
  }

  private static long rateSort(long numerator, long denominator) {
    return StatisticMetricCalculator.ratioSortValue(numerator, denominator);
  }

  private static long percentSort(double value) {
    return StatisticMetricCalculator.percentSortValue(value);
  }

  private record AggregateBucket(String rowLabel, List<IssueSource> issues) {
    AggregateBucket(String rowLabel) {
      this(rowLabel, new ArrayList<>());
    }

    AggregateBucket acceptAll(List<IssueSource> sourceIssues) {
      issues.addAll(sourceIssues);
      return this;
    }

    void accept(IssueSource issue) {
      issues.add(issue);
    }

    StatisticRowData toRowData(long overall) {
      return toRowData(overall, rowLabel);
    }

    StatisticRowData toRowData(long overall, String rowKey) {
      long total = issues.size();
      long solved = issues.stream().filter(IssueSource::isFixedByLegacySummary).count();
      long closed = issues.stream().filter(IssueSource::isClosed).count();
      long open = total - closed;
      long delayed = issues.stream().filter(IssueSource::delayIssue).count();
      long extension = issues.stream().filter(IssueSource::hasExtensionLabel).count();
      long retest = issues.stream().filter(IssueSource::isRetestFailed).count();
      long level1Back = issues.stream().filter(IssueSource::isLevel1Back).count();
      long level1Hang = issues.stream().filter(IssueSource::isLevel1Hang).count();
      long level1Other = issues.stream().filter(IssueSource::isLevel1Other).count();
      long level1Total = issues.stream().filter(IssueSource::isLevel1).count();
      long level1Fixed = issues.stream().filter(issue -> issue.isLevel1() && issue.isFixedByLegacySummary()).count();
      long level1Unfixed = issues.stream().filter(issue -> issue.isLevel1() && issue.isUnfixedByLegacySummary()).count();
      long level2Total = issues.stream().filter(IssueSource::isLevel2).count();
      long level2Fixed = issues.stream().filter(issue -> issue.isLevel2() && issue.isFixedByLegacySummary()).count();
      long level2Unfixed = issues.stream().filter(issue -> issue.isLevel2() && issue.isUnfixedByLegacySummary()).count();
      long level3Total = issues.stream().filter(IssueSource::isLevel3).count();
      long level3Fixed = issues.stream().filter(issue -> issue.isLevel3() && issue.isFixedByLegacySummary()).count();
      long level3Unfixed = issues.stream().filter(issue -> issue.isLevel3() && issue.isUnfixedByLegacySummary()).count();
      long suggestion = issues.stream().filter(IssueSource::isSuggestion).count();
      long p1 = issues.stream().filter(issue -> issue.isPriority("P1")).count();
      long p1Fixed = issues.stream().filter(issue -> issue.isPriority("P1") && issue.isPriorityFixedByLegacySummary()).count();
      long p1Closed = issues.stream().filter(issue -> issue.isPriority("P1") && issue.isClosed()).count();
      long p2 = issues.stream().filter(issue -> issue.isPriority("P2")).count();
      long p2Fixed = issues.stream().filter(issue -> issue.isPriority("P2") && issue.isPriorityFixedByLegacySummary()).count();
      long p3 = issues.stream().filter(issue -> issue.isPriority("P3")).count();
      long p3Fixed = issues.stream().filter(issue -> issue.isPriority("P3") && issue.isPriorityFixedByLegacySummary()).count();
      long newTotal = issues.stream().filter(IssueSource::isNewIssueByLegacySummary).count();
      long newFixed = issues.stream().filter(issue -> issue.isNewIssueByLegacySummary() && issue.isFixedByLegacySummary()).count();
      long newClosed = issues.stream().filter(issue -> issue.isNewIssueByLegacySummary() && issue.isPriorityClosedByLegacySummary()).count();
      long level23Fixed = level2Fixed + level3Fixed;
      double defectRatio = StatisticMetricCalculator.percentageOf(total, overall);
      double delayRatio = StatisticMetricCalculator.percentageOf(delayed, total);
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              cell("level1_back", level1Back, count(level1Back), true, rowKey),
              cell("level1_hang", level1Hang, count(level1Hang), true, rowKey),
              cell("level1_other", level1Other, count(level1Other), true, rowKey),
              cell("level1_fixed", level1Fixed, count(level1Fixed), true, rowKey),
              cell("level1_total", level1Total, count(level1Total), true, rowKey),
              cell("level1_rate", rateSort(level1Fixed, level1Total), rate(level1Fixed, level1Total), false, rowKey),
              cell("level2_fixed", level2Fixed, count(level2Fixed), true, rowKey),
              cell("level2_total", level2Total, count(level2Total), true, rowKey),
              cell("level2_rate", rateSort(level2Fixed, level2Total), rate(level2Fixed, level2Total), false, rowKey),
              cell("level3_fixed", level3Fixed, count(level3Fixed), true, rowKey),
              cell("level3_total", level3Total, count(level3Total), true, rowKey),
              cell("level3_rate", rateSort(level3Fixed, level3Total), rate(level3Fixed, level3Total), false, rowKey),
              cell("suggestion_total", suggestion, count(suggestion), true, rowKey),
              cell("p1_count", p1, count(p1), true, rowKey),
              cell("p1_fix_rate", rateSort(p1Fixed, p1), rate(p1Fixed, p1), false, rowKey),
              cell("p1_close_rate", rateSort(p1Closed, p1), rate(p1Closed, p1), false, rowKey),
              cell("p2_count", p2, count(p2), true, rowKey),
              cell("p2_fix_rate", rateSort(p2Fixed, p2), rate(p2Fixed, p2), false, rowKey),
              cell("p3_count", p3, count(p3), true, rowKey),
              cell("p3_fix_rate", rateSort(p3Fixed, p3), rate(p3Fixed, p3), false, rowKey),
              cell("module_total", total, count(total), true, rowKey),
              cell("defect_ratio", percentSort(defectRatio), percent(defectRatio), false, rowKey),
              cell("delay_defect_ratio", percentSort(delayRatio), percent(delayRatio), false, rowKey),
              cell("solved_count", solved, count(solved), true, rowKey),
              cell("fix_rate", rateSort(solved, total), rate(solved, total), false, rowKey),
              cell("close_rate", rateSort(closed, total), rate(closed, total), false, rowKey),
              cell("open_count", open, count(open), true, rowKey),
              cell("extension_count", extension, count(extension), true, rowKey),
              cell("retest_failed_count", retest, count(retest), true, rowKey),
              cell("new_issue_fixed", newFixed, count(newFixed), true, rowKey),
              cell("new_issue_total", newTotal, count(newTotal), true, rowKey),
              cell("new_issue_fix_rate", rateSort(newFixed, newTotal), rate(newFixed, newTotal), false, rowKey),
              cell("new_issue_close_rate", rateSort(newClosed, newTotal), rate(newClosed, newTotal), false, rowKey),
              cell("level1_legacy_rate", rateSort(level1Total - level1Fixed, level1Total), rate(level1Total - level1Fixed, level1Total), false, rowKey),
              cell("level2_legacy_count", level2Unfixed, count(level2Unfixed), true, rowKey),
              cell("level3_legacy_count", level3Unfixed, count(level3Unfixed), true, rowKey),
              cell("level23_legacy_rate", rateSort(level23Fixed, total), rate(level23Fixed, total), false, rowKey)));
    }

    private StatisticCellData cell(
        String key, long numericValue, String displayValue, boolean drilldown, String rowKey) {
      return new StatisticCellData(
          key,
          numericValue,
          displayValue,
          drilldown,
          drilldown ? "issue-list" : null,
          Map.of("rowKey", rowKey));
    }
  }

  private record IssueSource(
      Long id,
      Integer iid,
      String sourceInstance,
      String title,
      Long projectId,
      String projectName,
      String milestoneTitle,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String priorityLevel,
      boolean excluded,
      String exclusionReason,
      String bugStatus,
      String category,
      String delayCause,
      String assigneeName,
      boolean fixed,
      boolean delayIssue,
      boolean regression,
      boolean crash,
      boolean level1Other,
      boolean illegal,
      String illegalReason,
      boolean legacy,
      List<String> moduleNames,
      List<String> labels) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isPriority(String priority) {
      return priority.equalsIgnoreCase(priorityLevel);
    }

    boolean isSeverity(String severity) {
      return severity.equalsIgnoreCase(severityLevel);
    }

    boolean isLevel1() {
      return isSeverity("LEVEL1");
    }

    boolean isLevel1Back() {
      return isLevel1() && regression;
    }

    boolean isLevel1Hang() {
      return isLevel1() && crash;
    }

    boolean isLevel1Other() {
      return isLevel1() && level1Other;
    }

    boolean isLevel2() {
      return isSeverity("LEVEL2");
    }

    boolean isLevel3() {
      return isSeverity("LEVEL3");
    }

    boolean isSuggestion() {
      return contains(category, "建议");
    }

    boolean isNewIssue() {
      return isNewIssueByLegacySummary();
    }

    boolean isSolvedLike() {
      return isFixedByLegacySummary();
    }

    boolean isClosedResolved() {
      return isClosed() && hasClosedResolvedStatus();
    }

    boolean hasExtensionLabel() {
      return contains(bugStatus, "申请延期");
    }

    boolean isRetestFailed() {
      return contains(bugStatus, "未修复");
    }

    boolean isFixedByLegacySummary() {
      return contains(bugStatus, "已修复") || contains(bugStatus, "待合并") || contains(bugStatus, "未更新");
    }

    boolean isPriorityFixedByLegacySummary() {
      return contains(bugStatus, "已修复/完成") || contains(bugStatus, "未复现") || isClosed();
    }

    boolean isPriorityClosedByLegacySummary() {
      return isClosed() && hasClosedResolvedStatus();
    }

    boolean isUnfixedByLegacySummary() {
      return !contains(bugStatus, "已修复") && !contains(bugStatus, "待合并") && !contains(bugStatus, "未更新");
    }

    boolean isNewIssueByLegacySummary() {
      return !contains(bugStatus, "历史遗留");
    }

    String severityLabel() {
      if (isSuggestion()) {
        return IssueDisplayValueSupport.displaySeverityLevel("SUGGESTION");
      }
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
    }

    private boolean hasClosedResolvedStatus() {
      return contains(bugStatus, "已修复/完成") || contains(bugStatus, "未复现");
    }

    private boolean contains(String text, String keyword) {
      return StringUtils.hasText(text) && text.contains(keyword);
    }

    private boolean hasScope(String value) {
      return StringUtils.hasText(value) && (value.contains("系统测试") || value.contains("回归测试"));
    }
  }

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
