package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.service.IssueDisplayValueSupport;
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
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SystemTestDefectSummaryBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport {
  private static final String BOARD_KEY = "system-test-defect-summary";
  private static final String RULE_VERSION = "system-test-defect-summary@2026-04-09-v6";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> LEGACY_FIXED_STATUS_TOKENS = List.of("已修复", "待合并", "未更新");
  private static final List<String> LEGACY_RESOLVED_STATUS_TOKENS = List.of("已修复/完成", "未复现");
  private static final List<String> REALTIME_REFRESH_TABLES = List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private final IssueFactBoardRuntimeSupport runtimeSupport;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;

  public SystemTestDefectSummaryBoardService(
      JsonUtils jsonUtils,
      IssueFactBoardRuntimeSupport runtimeSupport,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseCatalogService phaseCatalogService) {
    super(jsonUtils);
    this.runtimeSupport = runtimeSupport;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseCatalogService = phaseCatalogService;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return buildDefinition(loadPhaseOptions());
  }

  private StatisticBoardDefinition buildDefinition(List<StatisticFilterOption> phaseOptions) {
    return new StatisticBoardDefinition(
        BOARD_KEY, "系统测试缺陷汇总", "基于 issue_fact 的模块维度系统测试汇总。", "", "", "模块名称",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.select("testingPhase", "测试阶段", 220, phaseOptions),
            StatisticFilterFieldFactory.text("moduleName", "模块名称", 180),
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
            StatisticColumnGroup.withChildren("level1", "一级缺陷", List.of(
                new StatisticColumnGroup("level1-classification", "分类", List.of(
                    leaf("level1_back", "回退(个)", true, "count"),
                    leaf("level1_hang", "挂机(个)", true, "count"),
                    leaf("level1_other", "其他(个)", true, "count"))),
                new StatisticColumnGroup("level1-status", "状态统计", List.of(
                    leaf("level1_fixed", "一级缺陷已修复数量", true, "count"),
                    leaf("level1_total", "一级缺陷数量(个)", true, "count"),
                    leaf("level1_rate", "一级缺陷修复率%", false, "ratio"))))),
            new StatisticColumnGroup("level2", "二级缺陷", List.of(
                leaf("level2_fixed", "二级缺陷已修复数量", true, "count"),
                leaf("level2_total", "二级缺陷(个)", true, "count"),
                leaf("level2_rate", "二级缺陷修复率%", false, "ratio"))),
            new StatisticColumnGroup("level3", "三级缺陷", List.of(
                leaf("level3_fixed", "三级缺陷已修复数量", true, "count"),
                leaf("level3_total", "三级缺陷(个)", true, "count"),
                leaf("level3_rate", "三级缺陷修复率%", false, "ratio"))),
            new StatisticColumnGroup("suggestion", "建议类缺陷", List.of(
                leaf("suggestion_total", "建议类缺陷(个)", true, "count"))),
            StatisticColumnGroup.withChildren("priority-summary", "缺陷级别汇总", List.of(
                new StatisticColumnGroup("p1", "P1", List.of(
                    leaf("p1_count", "P1级别缺陷", true, "count"),
                    leaf("p1_fix_rate", "P1缺陷修复率(%)", false, "ratio"),
                    leaf("p1_close_rate", "P1缺陷关闭率(%)", false, "ratio"))),
                new StatisticColumnGroup("p2", "P2", List.of(
                    leaf("p2_count", "P2级别缺陷", true, "count"),
                    leaf("p2_fix_rate", "P2缺陷修复率(%)", false, "ratio"),
                    leaf("p2_close_rate", "P2缺陷关闭率(%)", false, "ratio"))),
                new StatisticColumnGroup("p3", "P3", List.of(
                    leaf("p3_count", "P3级别缺陷", true, "count"),
                    leaf("p3_fix_rate", "P3缺陷修复率(%)", false, "ratio"))),
                new StatisticColumnGroup("summary", "综合汇总", List.of(
                    leaf("module_total", "模块总缺陷数(个)", true, "count"),
                    leaf("defect_ratio", "缺陷占比(%)", false, "ratio"),
                    leaf("delay_defect_ratio", "延期缺陷占比(%)", false, "ratio"),
                    leaf("solved_count", "已修复/未更新", true, "count"),
                    leaf("fix_rate", "修复率(%)", false, "ratio"),
                    leaf("close_rate", "关闭率(%)", false, "ratio"),
                    leaf("open_count", "未关闭缺陷数(个)", true, "count"),
                    leaf("extension_count", "申请延期(个)", true, "count"),
                    leaf("retest_failed_count", "复测未通过缺陷数(个)", true, "count"))))),
            new StatisticColumnGroup("new-issue", "新发议题", List.of(
                leaf("new_issue_fixed", "新发议题修复数量", true, "count"),
                leaf("new_issue_total", "新发议题数量", true, "count"),
                leaf("new_issue_fix_rate", "新发议题修复率(%)", false, "ratio"),
                leaf("new_issue_close_rate", "新发议题关闭率(%)", false, "ratio"))),
            new StatisticColumnGroup("legacy", "遗留率", List.of(
                leaf("level1_legacy_rate", "一级缺陷遗留率(%)", false, "ratio"),
                leaf("level2_legacy_count", "二级缺陷遗留数量", true, "count"),
                leaf("level3_legacy_count", "三级缺陷遗留数量", true, "count"),
                leaf("level23_legacy_rate", "二三级缺陷遗留率(%)", false, "ratio")))),
        List.of(
            new StatisticDetailColumn("iid", "议题编号", 120, 120, true),
            new StatisticDetailColumn("title", "标题", null, 260, true),
            new StatisticDetailColumn("moduleNames", "模块", null, 180, true),
            new StatisticDetailColumn("projectName", "所属项目", null, 160, true),
            new StatisticDetailColumn("severityLevel", "严重程度", 140, 140, true),
            new StatisticDetailColumn("bugStatus", "测试状态", 160, 160, true),
            new StatisticDetailColumn("delayCause", "延期原因", 160, 160, true),
            new StatisticDetailColumn("authorName", "创建人", 140, 140, true),
            new StatisticDetailColumn("assigneeName", "处理人", 140, 140, true),
            new StatisticDetailColumn("state", "状态", 120, 120, true),
            new StatisticDetailColumn("createdAt", "议题提交时间", 180, 180, true),
            new StatisticDetailColumn("labels", "标签", null, 240, false),
            new StatisticDetailColumn("updatedAt", "更新时间", 180, 180, true)),
        10, "当前没有可展示的系统测试缺陷统计数据。");
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    long startedAt = System.currentTimeMillis();
    Map<String, List<String>> phaseValueCache = new LinkedHashMap<>();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), filterGroup, phaseValueCache);
    List<IssueSource> sources = snapshot.finalSources();
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (String moduleName : moduleRows(moduleRowSources(snapshot.scopedSources(), filterGroup, phaseValueCache))) {
      buckets.computeIfAbsent(moduleName, AggregateBucket::new);
    }
    for (IssueSource issue : sources) {
      for (String moduleName : issue.moduleNames()) {
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }
    List<StatisticRowData> rows = new ArrayList<>(buckets.values().stream()
        .map(bucket -> bucket.toRowData(sources.size()))
        .sorted(Comparator.comparing(StatisticRowData::rowLabel, String.CASE_INSENSITIVE_ORDER))
        .toList());
    rows.add(new AggregateBucket(TOTAL_ROW_LABEL).acceptAll(sources).toRowData(sources.size(), TOTAL_ROW_KEY));
    StatisticBoardDefinition definition = buildDefinition(loadPhaseOptions());
    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount = definition.columnGroups().stream().flatMap(group -> group.leafColumns().stream()).mapToInt(c -> c.drilldown() ? 1 : 0).sum();
    return new StatisticBoardResponse(definition, withoutReservedFilters(filters), filterGroup, rows,
        new StatisticBoardMeta(LocalDateTime.now(), System.currentTimeMillis() - startedAt, rows.size(), columnCount, drilldownCount));
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    List<IssueSource> scoped = loadBoardScopedSources(request.filters(), filterGroup).stream()
        .filter(issue -> matchesRow(issue, request.rowKey())).filter(matchesMetric(request.columnKey()))
        .sorted(buildDetailComparator(request.sortField(), request.sortOrder())).toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse("系统测试缺陷明细", "展示当前模块与指标命中的 issue_fact 明细。", buildDefinition(loadPhaseOptions()).detailColumns(),
        pageSlice.records().stream().map(this::toDetailRecord).toList(), pageSlice.total(), pageSlice.page(), pageSlice.size(),
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
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(loadPhaseOptions()));
    RuleFlowSnapshot s = buildRuleFlowSnapshot(loadSources(filters), filterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY, true, "系统测试缺陷汇总规则说明", RULE_VERSION,
        "当前统计基于 issue_fact 的归一化事实字段，先限定系统测试/回归测试范围，再按模块展开。",
        "同一条议题如果关联多个模块，会分别计入对应模块；总计行仍按议题本身统计。", s.flowSteps(), buildMetricDefinitions(), null);
  }

  private List<IssueSource> loadBoardScopedSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    return buildRuleFlowSnapshot(loadSources(filters), filterGroup, new LinkedHashMap<>()).finalSources();
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    return buildRuleFlowSnapshot(loaded, filterGroup, new LinkedHashMap<>());
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded, StatisticFilterGroup filterGroup, Map<String, List<String>> phaseValueCache) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial.stream().filter(IssueSource::inSystemTestScope).toList();
    List<IssueSource> validBeforeFilter = scoped.stream().filter(i -> !i.excluded()).toList();
    List<IssueSource> valid =
        hasTestingPhaseCondition(filterGroup)
            ? validBeforeFilter.stream().filter(issue -> matchesFilterGroup(issue, filterGroup, phaseValueCache)).toList()
            : List.of();
    return new RuleFlowSnapshot(scoped, valid, List.of(
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
            "限定系统测试范围",
            "只保留带有系统测试或回归测试标签的议题。",
            initial.size(),
            scoped,
            this::toRuleFlowSample
        ),
        StatisticRuleFlowSupport.step(
            "exclude-invalid-issues",
            "排除无效数据",
            "剔除功能屏蔽、已拒绝、建议，以及关闭后属于申请否决/数据异常/需求如此的议题。",
            scoped.size(),
            validBeforeFilter,
            this::toRuleFlowSample
        ),
        StatisticRuleFlowSupport.step(
            "apply-filter-group",
            "应用页面筛选",
            "按页面上的测试阶段、项目、模块、严重程度和优先级等条件进一步收敛统计范围。",
            validBeforeFilter.size(),
            valid,
            this::toRuleFlowSample
        ),
        StatisticRuleFlowSupport.step(
            "module-expand",
            "按模块展开",
            "同一条议题可能属于多个模块，模块行会分别计入；总计行仍按议题本身统计。",
            valid.size(),
            valid.stream().mapToLong(i -> i.moduleNames().size()).sum(),
            valid,
            this::toRuleFlowSample
        )));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource i) {
    return new StatisticRuleFlowStepSample("#" + i.iid() + " " + i.projectName(),
        i.title() + (i.moduleNames().isEmpty() ? "" : " | 模块: " + String.join("、", i.moduleNames())));
  }

  private List<String> moduleRows(List<IssueSource> scopedSources) {
    Set<String> moduleNames = new LinkedHashSet<>();
    for (IssueSource issue : scopedSources) {
      moduleNames.addAll(issue.moduleNames());
    }
    return moduleNames.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  private List<IssueSource> moduleRowSources(
      List<IssueSource> scopedSources, StatisticFilterGroup filterGroup, Map<String, List<String>> phaseValueCache) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return List.of();
    }
    List<com.data.collection.platform.entity.statistics.StatisticFilterCondition> phaseConditions =
        filterGroup.conditions().stream()
            .filter(condition -> condition != null && "testingPhase".equals(condition.fieldKey()))
            .toList();
    if (phaseConditions.isEmpty()) {
      return List.of();
    }
    return scopedSources.stream()
        .filter(issue -> phaseConditions.stream().allMatch(condition -> matchesCondition(issue, condition, phaseValueCache)))
        .toList();
  }

  private boolean hasTestingPhaseCondition(StatisticFilterGroup filterGroup) {
    return filterGroup != null
        && filterGroup.conditions() != null
        && filterGroup.conditions().stream()
            .anyMatch(condition -> condition != null
                && "testingPhase".equals(condition.fieldKey())
                && trimTextToNull(condition.value()) != null);
  }

  private List<StatisticRuleMetricDefinition> buildMetricDefinitions() {
    return List.of(
        new StatisticRuleMetricDefinition("level1", "一级缺陷", "一级缺陷基于 severity_level = LEVEL1，再拆分回退、挂机、其他一级。", "一级缺陷修复率 = 一级缺陷已修复数量 / 一级缺陷总数", null),
        new StatisticRuleMetricDefinition("priority-summary", "缺陷级别汇总", "P1/P2/P3 与一级/二级/三级缺陷是两套独立统计体系，按老平台 urgency 口径映射到 priority_level。", "Pn 修复率 = bug_status 含已修复/完成或未复现或议题已关闭的 Pn 数量 / Pn 总数；P2/P3 关闭率还要求 bug_status 含已修复/完成或未复现", null),
        new StatisticRuleMetricDefinition("summary", "综合汇总", "综合区展示模块总缺陷、缺陷占比、延期占比、已修复/未更新、修复率、关闭率、未关闭数量、申请延期和复测未通过。", "修复率 = bug_status 含已修复、待合并或未更新的数量 / 模块总缺陷数；复测未通过 = bug_status 含未修复", null),
        new StatisticRuleMetricDefinition("new-issue", "新发议题", "新发议题按 bug_status 不含“历史遗留”统计。", "新发议题修复率 = 新发议题中 bug_status 含已修复、待合并或未更新的数量 / 新发议题总数；关闭率还要求关闭且 bug_status 含已修复/完成或未复现", null),
        new StatisticRuleMetricDefinition("legacy", "遗留率", "遗留区沿用老平台 ModuleTableRow 写死口径，而不是 issue_fact.is_legacy。", "一级缺陷遗留率 = (一级缺陷总数 - 一级 setFixQuery 命中数) / 一级缺陷总数；二/三级遗留数量 = 对应严重程度下 bug_status 不含已修复、待合并、未更新；二三级遗留率 = 二三级 setFixQuery 命中数 / 模块总缺陷数", null));
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    try {
      return runtimeSupport
          .loadFacts(withoutReservedFilters(filters), StatisticIssueFactSource::inSystemTestScope)
          .stream()
          .map(this::toIssueSource)
          .toList();
    } catch (Exception e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private List<StatisticFilterOption> loadPhaseOptions() {
    try {
      return phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID).stream()
          .map(value -> new StatisticFilterOption(value, value))
          .toList();
    } catch (Exception e) {
      log.debug("Failed to load phase options for {}", BOARD_KEY, e);
      return List.of();
    }
  }

  private boolean matchesFilterGroup(IssueSource issue, StatisticFilterGroup filterGroup) {
    return matchesFilterGroup(issue, filterGroup, new LinkedHashMap<>());
  }

  private boolean matchesFilterGroup(
      IssueSource issue, StatisticFilterGroup filterGroup, Map<String, List<String>> phaseValueCache) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (var condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(issue, condition, phaseValueCache);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private boolean matchesCondition(
      IssueSource issue,
      com.data.collection.platform.entity.statistics.StatisticFilterCondition condition) {
    return matchesCondition(issue, condition, new LinkedHashMap<>());
  }

  private boolean matchesCondition(
      IssueSource issue,
      com.data.collection.platform.entity.statistics.StatisticFilterCondition condition,
      Map<String, List<String>> phaseValueCache) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    String operator = condition.operator();
    String value = trimTextToNull(condition.value());
    return switch (condition.fieldKey()) {
      case "projectName" -> matchesText(issue.projectName(), operator, value);
      case "testingPhase" -> matchesPhase(issue, operator, value, phaseValueCache);
      case "moduleName" -> matchesAny(issue.moduleNames(), operator, value);
      case "severityLevel" -> matchesText(issue.severityLevel(), operator, value);
      case "priorityLevel" -> matchesText(issue.priorityLevel(), operator, value);
      default -> true;
    };
  }

  private boolean matchesText(String candidate, String operator, String value) {
    String safeCandidate = trimTextToNull(candidate);
    return switch (operator) {
      case "eq" -> value == null || (safeCandidate != null && safeCandidate.equalsIgnoreCase(value));
      case "ne" -> value == null || safeCandidate == null || !safeCandidate.equalsIgnoreCase(value);
      case "contains" -> value == null || containsIgnoreCase(safeCandidate, value);
      case "isEmpty" -> safeCandidate == null;
      case "isNotEmpty" -> safeCandidate != null;
      default -> true;
    };
  }

  private boolean matchesPhase(
      IssueSource issue, String operator, String value, Map<String, List<String>> phaseValueCache) {
    List<String> legacyPhaseValues = legacyPhaseValues(value, phaseValueCache);
    return switch (operator) {
      case "eq" -> value == null || issue.hasAnyPhaseLabel(legacyPhaseValues);
      case "ne" -> value == null || !issue.hasAnyPhaseLabel(legacyPhaseValues);
      case "contains" -> value == null || issue.phaseLabels().stream().anyMatch(label -> containsIgnoreCase(label, value));
      case "isEmpty" -> issue.phaseLabels().isEmpty();
      case "isNotEmpty" -> !issue.phaseLabels().isEmpty();
      default -> true;
    };
  }

  private List<String> legacyPhaseValues(String value, Map<String, List<String>> phaseValueCache) {
    String normalized = trimTextToNull(value);
    if (normalized == null) {
      return List.of();
    }
    return phaseValueCache.computeIfAbsent(normalized, key -> {
      List<String> configuredPhases =
          phaseCatalogService.listTestingPhasesByParent(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, key);
      return configuredPhases.isEmpty() ? List.of(key) : configuredPhases;
    });
  }

  private boolean matchesAny(List<String> candidates, String operator, String value) {
    List<String> safeCandidates = candidates == null ? List.of() : candidates;
    return switch (operator) {
      case "eq" -> value == null || safeCandidates.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(value));
      case "ne" -> value == null || safeCandidates.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(value));
      case "contains" -> value == null || safeCandidates.stream().anyMatch(candidate -> containsIgnoreCase(candidate, value));
      case "isEmpty" -> safeCandidates.isEmpty();
      case "isNotEmpty" -> !safeCandidates.isEmpty();
      default -> true;
    };
  }

  private boolean containsIgnoreCase(String candidate, String value) {
    return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
  }

  private String trimTextToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private IssueSource toIssueSource(StatisticIssueFactSource source) {
    return new IssueSource(
        source.id(),
        source.iid(),
        source.title(),
        source.projectId(),
        source.projectName(),
        source.authorName(),
        source.createdAt(),
        source.updatedAt(),
        source.closedAt(),
        source.issueState(),
        source.testingPhase(),
        source.systemTestLabel(),
        source.severityLevel(),
        source.priorityLevel(),
        source.bugStatus(),
        source.category(),
        source.delayCause(),
        source.excluded(),
        "",
        source.fixed(),
        source.delayIssue(),
        source.regression(),
        source.crash(),
        source.level1Other(),
        false,
        "",
        source.legacy(),
        source.assigneeName(),
        source.moduleNames(),
        source.labels());
  }

  private Map<String, Object> toDetailRecord(IssueSource i) {
    Map<String, Object> r = new LinkedHashMap<>();
    issueLinkSupport.putIssueFields(r, i.iid(), i.projectId(), i.projectName());
    r.put("title", i.title()); r.put("moduleNames", String.join("、", i.moduleNames()));
    r.put("projectName", i.projectName());
    r.put("severityLevel", i.displaySeverityLevel());
    r.put("bugStatus", i.bugStatus());
    r.put("delayCause", i.delayCause());
    r.put("authorName", i.authorName());
    r.put("assigneeName", i.assigneeName());
    r.put("state", i.isClosed() ? "已关闭" : "未关闭");
    r.put("createdAt", i.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(i.createdAt()));
    r.put("labels", String.join(", ", i.labels()));
    r.put("updatedAt", i.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(i.updatedAt()));
    return r;
  }

  private Predicate<IssueSource> matchesMetric(String key) {
    return switch (key) {
      case "level1_back" -> IssueSource::isLevel1Back;
      case "level1_hang" -> IssueSource::isLevel1Hang;
      case "level1_other" -> IssueSource::isLevel1Other;
      case "level1_fixed" -> i -> i.isLevel1() && i.isLegacyFixed();
      case "level1_total" -> IssueSource::isLevel1;
      case "level2_fixed" -> i -> i.isLevel2() && i.isLegacyFixed();
      case "level2_total" -> IssueSource::isLevel2;
      case "level3_fixed" -> i -> i.isLevel3() && i.isLegacyFixed();
      case "level3_total" -> IssueSource::isLevel3;
      case "suggestion_total" -> IssueSource::isSuggestion;
      case "p1_count" -> i -> i.isPriority("P1");
      case "p2_count" -> i -> i.isPriority("P2");
      case "p3_count" -> i -> i.isPriority("P3");
      case "solved_count" -> IssueSource::isLegacyFixed;
      case "open_count" -> i -> !i.isClosed();
      case "extension_count" -> IssueSource::hasExtensionLabel;
      case "retest_failed_count" -> IssueSource::isRetestFailed;
      case "new_issue_fixed" -> i -> i.isNewIssue() && i.isLegacyFixed();
      case "new_issue_total" -> IssueSource::isNewIssue;
      case "level2_legacy_count" -> i -> i.isLevel2() && i.isLegacyOpenForLevel23();
      case "level3_legacy_count" -> i -> i.isLevel3() && i.isLegacyOpenForLevel23();
      default -> i -> true;
    };
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey) || TOTAL_ROW_KEY.equals(rowKey) || issue.moduleNames().contains(rowKey);
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> c = switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
      case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
      case "title" -> SortSupport.nullableString(IssueSource::title);
      case "moduleNames" -> SortSupport.nullableString(i -> String.join("、", i.moduleNames()));
      case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
      case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
      case "assigneeName" -> SortSupport.nullableString(IssueSource::assigneeName);
      case "severityLevel" -> SortSupport.nullableString(IssueSource::displaySeverityLevel);
      case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
      case "delayCause" -> SortSupport.nullableString(IssueSource::delayCause);
      case "createdAt" -> SortSupport.nullableComparable(IssueSource::createdAt);
      case "state" -> SortSupport.nullableComparable(i -> i.isClosed() ? 1 : 0);
      default -> SortSupport.nullableComparable(IssueSource::updatedAt);
    };
    c = c.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(c, "ascending".equalsIgnoreCase(sortOrder));
  }

  private static String count(long v) { return StatisticMetricCalculator.count(v); }
  private static String rate(long n, long d) { return StatisticMetricCalculator.rate(n, d); }
  private static String percent(double value) { return StatisticMetricCalculator.percent(value); }

  private record AggregateBucket(String rowLabel, List<IssueSource> issues) {
    AggregateBucket(String rowLabel) { this(rowLabel, new ArrayList<>()); }
    AggregateBucket acceptAll(List<IssueSource> sourceIssues) { issues.addAll(sourceIssues); return this; }
    void accept(IssueSource issue) { issues.add(issue); }
    StatisticRowData toRowData(long overall) { return toRowData(overall, rowLabel); }
    StatisticRowData toRowData(long overall, String rowKey) {
      long total = issues.size(), solved = issues.stream().filter(IssueSource::isLegacyFixed).count(), closed = issues.stream().filter(IssueSource::isClosed).count(), open = total - closed;
      long delayed = issues.stream().filter(IssueSource::delayIssue).count(), extension = issues.stream().filter(IssueSource::hasExtensionLabel).count(), retest = issues.stream().filter(IssueSource::isRetestFailed).count();
      long l1b = issues.stream().filter(IssueSource::isLevel1Back).count(), l1h = issues.stream().filter(IssueSource::isLevel1Hang).count(), l1o = issues.stream().filter(IssueSource::isLevel1Other).count(), l1 = issues.stream().filter(IssueSource::isLevel1).count(), l1f = issues.stream().filter(i -> i.isLevel1() && i.isLegacyFixed()).count(), l1FixedForRetention = l1f;
      long l2 = issues.stream().filter(IssueSource::isLevel2).count(), l2f = issues.stream().filter(i -> i.isLevel2() && i.isLegacyFixed()).count(), l2legacy = issues.stream().filter(i -> i.isLevel2() && i.isLegacyOpenForLevel23()).count();
      long l3 = issues.stream().filter(IssueSource::isLevel3).count(), l3f = issues.stream().filter(i -> i.isLevel3() && i.isLegacyFixed()).count(), l3legacy = issues.stream().filter(i -> i.isLevel3() && i.isLegacyOpenForLevel23()).count();
      long sug = issues.stream().filter(IssueSource::isSuggestion).count();
      long p1 = issues.stream().filter(i -> i.isPriority("P1")).count(), p1f = issues.stream().filter(i -> i.isPriority("P1") && i.isPriorityFixed()).count(), p1c = issues.stream().filter(i -> i.isPriority("P1") && i.isP1Closed()).count();
      long p2 = issues.stream().filter(i -> i.isPriority("P2")).count(), p2f = issues.stream().filter(i -> i.isPriority("P2") && i.isPriorityFixed()).count(), p2c = issues.stream().filter(i -> i.isPriority("P2") && i.isPriorityClosedWithResolvedStatus()).count();
      long p3 = issues.stream().filter(i -> i.isPriority("P3")).count(), p3f = issues.stream().filter(i -> i.isPriority("P3") && i.isPriorityFixed()).count();
      long newTotal = issues.stream().filter(IssueSource::isNewIssue).count(), newFixed = issues.stream().filter(i -> i.isNewIssue() && i.isLegacyFixed()).count(), newClosed = issues.stream().filter(i -> i.isNewIssue() && i.isNewClosed()).count();
      long l23legacy = issues.stream().filter(i -> (i.isLevel2() || i.isLevel3()) && i.isLegacyFixed()).count();
      double defectRatio = StatisticMetricCalculator.percentageOf(total, overall);
      double delayRatio = StatisticMetricCalculator.percentageOf(delayed, total);
      return new StatisticRowData(rowKey, rowLabel, List.of(
          cell("level1_back", l1b, count(l1b), true, rowKey), cell("level1_hang", l1h, count(l1h), true, rowKey), cell("level1_other", l1o, count(l1o), true, rowKey),
          cell("level1_fixed", l1f, count(l1f), true, rowKey), cell("level1_total", l1, count(l1), true, rowKey), cell("level1_rate", l1f, rate(l1f, l1), false, rowKey),
          cell("level2_fixed", l2f, count(l2f), true, rowKey), cell("level2_total", l2, count(l2), true, rowKey), cell("level2_rate", l2f, rate(l2f, l2), false, rowKey),
          cell("level3_fixed", l3f, count(l3f), true, rowKey), cell("level3_total", l3, count(l3), true, rowKey), cell("level3_rate", l3f, rate(l3f, l3), false, rowKey),
          cell("suggestion_total", sug, count(sug), true, rowKey), cell("p1_count", p1, count(p1), true, rowKey), cell("p1_fix_rate", p1f, rate(p1f, p1), false, rowKey),
          cell("p1_close_rate", p1c, rate(p1c, p1), false, rowKey), cell("p2_count", p2, count(p2), true, rowKey), cell("p2_fix_rate", p2f, rate(p2f, p2), false, rowKey),
          cell("p2_close_rate", p2c, rate(p2c, p2), false, rowKey), cell("p3_count", p3, count(p3), true, rowKey), cell("p3_fix_rate", p3f, rate(p3f, p3), false, rowKey),
          cell("module_total", total, count(total), true, rowKey), cell("defect_ratio", Math.round(defectRatio), percent(defectRatio), false, rowKey), cell("delay_defect_ratio", Math.round(delayRatio), percent(delayRatio), false, rowKey),
          cell("solved_count", solved, count(solved), true, rowKey), cell("fix_rate", solved, rate(solved, total), false, rowKey), cell("close_rate", closed, rate(closed, total), false, rowKey),
          cell("open_count", open, count(open), true, rowKey), cell("extension_count", extension, count(extension), true, rowKey), cell("retest_failed_count", retest, count(retest), true, rowKey),
          cell("new_issue_fixed", newFixed, count(newFixed), true, rowKey), cell("new_issue_total", newTotal, count(newTotal), true, rowKey), cell("new_issue_fix_rate", newFixed, rate(newFixed, newTotal), false, rowKey),
          cell("new_issue_close_rate", newClosed, rate(newClosed, newTotal), false, rowKey), cell("level1_legacy_rate", l1 - l1FixedForRetention, rate(l1 - l1FixedForRetention, l1), false, rowKey), cell("level2_legacy_count", l2legacy, count(l2legacy), true, rowKey),
          cell("level3_legacy_count", l3legacy, count(l3legacy), true, rowKey), cell("level23_legacy_rate", l23legacy, rate(l23legacy, total), false, rowKey)));
    }
    private StatisticCellData cell(String key, long numericValue, String displayValue, boolean drilldown, String rowKey) {
      return new StatisticCellData(key, numericValue, displayValue, drilldown, drilldown ? "issue-list" : null, Map.of("rowKey", rowKey));
    }
  }

  private record IssueSource(Long id, Integer iid, String title, Long projectId, String projectName, String authorName, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime closedAt, String issueState, String testingPhase, String systemTestLabel, String severityLevel, String priorityLevel, String bugStatus, String category, String delayCause, boolean excluded, String exclusionReason, boolean fixed, boolean delayIssue, boolean regression, boolean crash, boolean level1Other, boolean illegal, String illegalReason, boolean legacy, String assigneeName, List<String> moduleNames, List<String> labels) {
    boolean inSystemTestScope() { return hasScope(testingPhase) || hasScope(systemTestLabel) || labels.stream().anyMatch(this::hasScope); }
    boolean isClosed() { return closedAt != null || "closed".equalsIgnoreCase(issueState); }
    boolean isPriority(String priority) { return priority.equalsIgnoreCase(priorityLevel); }
    boolean isSeverity(String severity) { return severity.equalsIgnoreCase(severityLevel); }
    boolean isLevel1() { return isSeverity("LEVEL1"); }
    boolean isLevel1Back() { return isLevel1() && regression; }
    boolean isLevel1Hang() { return isLevel1() && crash; }
    boolean isLevel1Other() { return isLevel1() && level1Other; }
    boolean isLevel2() { return isSeverity("LEVEL2"); }
    boolean isLevel3() { return isSeverity("LEVEL3"); }
    boolean isSuggestion() { return isSeverity("SUGGESTION") || contains(category, "建议"); }
    boolean isNewIssue() { return !contains(bugStatus, "历史遗留"); }
    boolean isLegacyFixed() { return containsAny(bugStatus, LEGACY_FIXED_STATUS_TOKENS); }
    boolean isPriorityFixed() { return containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS) || isClosed(); }
    boolean isP1Closed() { return isClosed(); }
    boolean isPriorityClosedWithResolvedStatus() { return isClosed() && containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS); }
    boolean isNewClosed() { return isNewIssue() && isPriorityClosedWithResolvedStatus(); }
    boolean isLegacyOpenForLevel23() { return !containsAny(bugStatus, LEGACY_FIXED_STATUS_TOKENS); }
    boolean hasExtensionLabel() { return contains(bugStatus, "申请延期") || labels.contains("申请延期"); }
    boolean isRetestFailed() { return contains(bugStatus, "未修复"); }
    String primaryPhaseLabel() {
      if (hasScope(testingPhase)) {
        return testingPhase;
      }
      if (hasScope(systemTestLabel)) {
        return systemTestLabel;
      }
      return labels.stream().filter(this::hasScope).findFirst().orElse("");
    }
    List<String> phaseLabels() {
      Set<String> values = new LinkedHashSet<>();
      addScopeLabel(values, testingPhase);
      addScopeLabel(values, systemTestLabel);
      labels.forEach(label -> addScopeLabel(values, label));
      return List.copyOf(values);
    }
    boolean hasAnyPhaseLabel(List<String> expectedLabels) {
      if (expectedLabels == null || expectedLabels.isEmpty()) {
        return true;
      }
      List<String> actualLabels = phaseLabels();
      return expectedLabels.stream().anyMatch(expected ->
          actualLabels.stream().anyMatch(actual -> actual.equalsIgnoreCase(expected)));
    }
    String displaySeverityLevel() {
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
    }
    private boolean hasScope(String value) { return StringUtils.hasText(value) && (value.contains("系统测试") || value.contains("回归测试")); }
    private void addScopeLabel(Set<String> values, String value) {
      if (hasScope(value)) {
        values.add(value.trim());
      }
    }
    private boolean containsAny(String value, List<String> tokens) { return tokens.stream().anyMatch(token -> contains(value, token)); }
    private boolean contains(String value, String token) { return StringUtils.hasText(value) && value.contains(token); }
  }

  private record RuleFlowSnapshot(List<IssueSource> scopedSources, List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
