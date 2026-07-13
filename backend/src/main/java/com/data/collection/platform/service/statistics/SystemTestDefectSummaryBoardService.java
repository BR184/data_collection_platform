package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordListRequest;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.OptionItemResponseFactory;
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
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestLegacyCauseExportFields;
import com.data.collection.platform.service.SystemTestIssueRecordWorkbookExportSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.labelgroup.LabelGroupDefaultFilterService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SystemTestDefectSummaryBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "system-test-defect-summary";
  private static final String MODULE_FIELD = "moduleName";
  private static final String RULE_VERSION = "system-test-defect-summary@2026-07-10-v11";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> LEGACY_FIXED_STATUS_TOKENS = List.of("已修复", "待合并", "未更新");
  private static final List<String> LEGACY_RESOLVED_STATUS_TOKENS = List.of("已修复/完成", "未复现");
  private static final List<String> REALTIME_REFRESH_TABLES = List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private final IssueFactBoardRuntimeSupport runtimeSupport;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final LabelGroupDefaultFilterService labelGroupDefaultFilterService;
  private final LabelGroupExpansionService labelGroupExpansionService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  private final IssueFactRecordRepository issueFactRecordRepository;

  public SystemTestDefectSummaryBoardService(
      JsonUtils jsonUtils,
      IssueFactBoardRuntimeSupport runtimeSupport,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseCatalogService phaseCatalogService,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      LabelGroupDefaultFilterService labelGroupDefaultFilterService,
      LabelGroupExpansionService labelGroupExpansionService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory,
      IssueFactRecordRepository issueFactRecordRepository) {
    super(jsonUtils);
    this.runtimeSupport = runtimeSupport;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseCatalogService = phaseCatalogService;
    this.phaseScopeResolver = phaseScopeResolver;
    this.labelGroupDefaultFilterService = labelGroupDefaultFilterService;
    this.labelGroupExpansionService = labelGroupExpansionService;
    this.snapshotService = snapshotService;
    this.snapshotRequestFactory = snapshotRequestFactory;
    this.issueFactRecordRepository = issueFactRecordRepository;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return buildDefinition(loadPhaseOptions(), loadQuickFilterOptions());
  }

  private StatisticBoardDefinition buildDefinition(
      List<StatisticFilterOption> phaseOptions,
      SystemTestSummaryQuickFilterOptions quickOptions) {
    return new StatisticBoardDefinition(
        BOARD_KEY, "系统测试缺陷汇总", "按模块汇总系统测试范围内的缺陷数量、修复情况、关闭情况和延期情况。", "", "", "模块名",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.select("testingPhase", "测试阶段", 220, phaseOptions),
            StatisticFilterFieldFactory.selectLabelGroup(MODULE_FIELD, "模块名称", 180, quickOptions.moduleNames()),
            StatisticFilterFieldFactory.textLabelGroup("title", "标题", 220),
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
                    new StatisticFilterOption("P3", "P3"))),
            StatisticFilterFieldFactory.selectLabelGroup("bugStatus", "测试状态", 180, quickOptions.bugStatuses()),
            StatisticFilterFieldFactory.selectLabelGroup("delayCause", "延期原因", 180, quickOptions.delayCauses()),
            StatisticFilterFieldFactory.selectLabelGroup("authorName", "创建人", 160, quickOptions.authorNames()),
            StatisticFilterFieldFactory.selectLabelGroup("assigneeName", "处理人", 160, quickOptions.assigneeNames()),
            StatisticFilterFieldFactory.select(
                "state",
                "状态",
                140,
                List.of(
                    new StatisticFilterOption("open", "未关闭"),
                    new StatisticFilterOption("closed", "已关闭"))),
            StatisticFilterFieldFactory.datetime("createdAt", "议题提交时间", 180),
            StatisticFilterFieldFactory.datetime("updatedAt", "更新时间", 180),
            StatisticFilterFieldFactory.textLabelGroup("labels", "标签", 220)),
        List.of(
            new StatisticColumnGroup("level1", "一级缺陷", List.of(
                new StatisticColumnGroup("level1-classification", "分类", List.of(
                    leaf("level1_back", "回退", true, "count"),
                    leaf("level1_hang", "挂机", true, "count"),
                    leaf("level1_other", "其他", true, "count")))),
                List.of(
                    leaf("level1_fixed", "一级缺陷已修复数量", true, "count"),
                    leaf("level1_total", "一级缺陷数量(个)", true, "count"),
                    leaf("level1_rate", "一级缺陷修复率（%）", false, "ratio"))),
            new StatisticColumnGroup("level2", "二级缺陷", List.of(
                leaf("level2_fixed", "二级缺陷已修复数量", true, "count"),
                leaf("level2_total", "二级缺陷（个）", true, "count"),
                leaf("level2_rate", "二级缺陷修复率(%)", false, "ratio"))),
            new StatisticColumnGroup("level3", "三级缺陷", List.of(
                leaf("level3_fixed", "三级缺陷修复数量", true, "count"),
                leaf("level3_total", "三级缺陷(个)", true, "count"),
                leaf("level3_rate", "三级缺陷修复率(%)", false, "ratio"))),
            new StatisticColumnGroup("suggestion", "建议类缺陷", List.of(
                leaf("suggestion_total", "建议类缺陷(个)", true, "count"))),
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
                leaf("p3_fix_rate", "P3缺陷修复率(%)", false, "ratio"),
                leaf("p3_close_rate", "P3缺陷关闭率(%)", false, "ratio"))),
            new StatisticColumnGroup("module_total_group", "模块总缺陷数(个)", List.of(
                leaf("module_total", "模块总缺陷数(个)", true, "count"))),
            new StatisticColumnGroup("defect_ratio_group", "缺陷占比(%)", List.of(
                leaf("defect_ratio", "缺陷占比(%)", false, "ratio"))),
            new StatisticColumnGroup("delay_defect_ratio_group", "延期缺陷占比(%)", List.of(
                leaf("delay_defect_ratio", "延期缺陷占比(%)", false, "ratio"))),
            new StatisticColumnGroup("solved_count_group", "已修复/未更新", List.of(
                leaf("solved_count", "已修复/未更新", true, "count"))),
            new StatisticColumnGroup("fix_rate_group", "修复率(%)", List.of(
                leaf("fix_rate", "修复率(%)", false, "ratio"))),
            new StatisticColumnGroup("close_rate_group", "关闭率(%)", List.of(
                leaf("close_rate", "关闭率(%)", false, "ratio"))),
            new StatisticColumnGroup("open_count_group", "未关闭缺陷数(个)", List.of(
                leaf("open_count", "未关闭缺陷数(个)", true, "count"))),
            new StatisticColumnGroup("extension_count_group", "申请延期(个)", List.of(
                leaf("extension_count", "申请延期(个)", true, "count"))),
            new StatisticColumnGroup("retest_failed_count_group", "复测未通过缺陷数(个)", List.of(
                leaf("retest_failed_count", "复测未通过缺陷数(个)", true, "count"))),
            new StatisticColumnGroup("new-issue", "新发议题", List.of(
                leaf("new_issue_fixed", "新发议题修复数量", true, "count"),
                leaf("new_issue_total", "新发议题数量", true, "count"),
                leaf("new_issue_fix_rate", "新发议题修复率(%)", false, "ratio"),
                leaf("new_issue_close_rate", "新发缺陷关闭率(%)", false, "ratio"))),
            new StatisticColumnGroup("legacy", "遗留率", List.of(
                leaf("level1_legacy_rate", "一级缺陷遗留率(%)", false, "ratio"),
                leaf("level2_legacy_count", "二级缺陷遗留数量", true, "count"),
                leaf("level3_legacy_count", "三级缺陷遗留数量", true, "count"),
                leaf("level23_legacy_rate", "二三级缺陷遗留率(%)", false, "ratio")))),
        StatisticIssueDetailColumns.moduleTableLegacyDetail(),
        10, "当前没有可展示的系统测试缺陷统计数据。");
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    if ("suggestion_total".equals(key)) {
      return new StatisticColumnLeaf(
          key,
          label,
          drilldown,
          metricType,
          SystemTestSuggestionMetricSupport.SUGGESTION_HEADER_TOOLTIP);
    }
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  public byte[] exportIssueRecordsWorkbook(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    EffectiveFilterGroup effectiveFilterGroup = buildEffectiveFilterGroup(filterGroup);
    List<SystemTestIssueSearchRowResponse> rows =
        loadBoardScopedSources(filters, effectiveFilterGroup).stream()
            .sorted(buildDetailComparator("updatedAt", "descending"))
            .map(this::toIssueExportRecord)
            .toList();
    return SystemTestIssueRecordWorkbookExportSupport.exportIssueDataRecords(rows);
  }

  public String exportIssueRecordsFilename(Map<String, String> filters) {
    String phase = selectedTestingPhase(parseFilterGroup(filters, buildDefinition()));
    if (StringUtils.hasText(phase)) {
      return phase + "-全量议题数据.xlsx";
    }
    return "全量议题数据.xlsx";
  }

  @Override
  public byte[] exportBoardWorkbook(Map<String, String> filters) {
    return SystemTestLegacyWorkbookExportSupport.exportDefectSummary(loadBoard(filters));
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    String phase = selectedTestingPhase(parseFilterGroup(filters, buildDefinition()));
    if (StringUtils.hasText(phase)) {
      return phase + "-系统测试缺陷汇总统计.xlsx";
    }
    return "系统测试缺陷汇总统计.xlsx";
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    EffectiveFilterGroup effectiveFilterGroup = buildEffectiveFilterGroup(filterGroup);
    StatisticBoardDefinition definition = buildDefinition();
    return snapshotService.readOrRefresh(
        snapshotRequest(filters, effectiveFilterGroup, definition),
        () -> buildBoardResponse(filters, effectiveFilterGroup, definition));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      EffectiveFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    long startedAt = System.currentTimeMillis();
    Map<String, List<String>> phaseValueCache = new LinkedHashMap<>();
    RuleFlowSnapshot snapshot =
        buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup, phaseValueCache);
    List<IssueSource> sources = snapshot.finalSources();
    boolean hasRequiredPhase = hasTestingPhaseCondition(effectiveFilterGroup.userGroup());
    List<StatisticRowData> rows = new ArrayList<>(moduleRows(moduleRowSources(snapshot.scopedSources(), effectiveFilterGroup, phaseValueCache)).stream()
        .map(moduleName -> toSummaryRowData(moduleName, moduleName, sources))
        .sorted(legacySummaryRowComparator())
        .toList());
    if (hasRequiredPhase) {
      rows.add(toSummaryRowData(TOTAL_ROW_KEY, TOTAL_ROW_LABEL, sources));
    }
    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount = definition.columnGroups().stream().flatMap(group -> group.leafColumns().stream()).mapToInt(c -> c.drilldown() ? 1 : 0).sum();
    return new StatisticBoardResponse(definition, withoutReservedFilters(filters), effectiveFilterGroup.appliedGroup(), rows,
        new StatisticBoardMeta(LocalDateTime.now(), System.currentTimeMillis() - startedAt, rows.size(), columnCount, drilldownCount));
  }

  @Override
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    StatisticBoardDefinition definition = buildDefinition();
    for (StatisticFilterOption option : loadPhaseOptions()) {
      StatisticFilterGroup filterGroup =
          new StatisticFilterGroup(
              "AND",
              List.of(new StatisticFilterCondition("testingPhase", "eq", option.value(), null)));
      EffectiveFilterGroup effectiveFilterGroup = buildEffectiveFilterGroup(filterGroup);
      Map<String, String> filters = Map.of("testingPhase", option.value());
      snapshotService.save(
          snapshotRequest(filters, effectiveFilterGroup, definition),
          buildBoardResponse(filters, effectiveFilterGroup, definition));
    }
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    EffectiveFilterGroup effectiveFilterGroup = buildEffectiveFilterGroup(filterGroup);
    List<IssueSource> scoped = loadBoardScopedSources(request.filters(), effectiveFilterGroup).stream()
        .filter(issue -> matchesRow(issue, request.rowKey())).filter(matchesMetric(request.columnKey()))
        .sorted(buildDetailComparator(request.sortField(), request.sortOrder())).toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse("系统测试缺陷明细", "展示当前模块与指标命中的议题明细。", buildDefinition().detailColumns(),
        pageSlice.records(), pageSlice.total(), pageSlice.page(), pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending",
        pageSlice.quickFilterOptions());
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return runtimeSupport.getRealtimeStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus(Map<String, String> filters) {
    return runtimeSupport.getRealtimeStatus(BOARD_KEY, filters);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return runtimeSupport.requestRealtimeRefresh(BOARD_KEY, REALTIME_REFRESH_TABLES);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    EffectiveFilterGroup effectiveFilterGroup = buildEffectiveFilterGroup(filterGroup);
    RuleFlowSnapshot s = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY, true, "系统测试缺陷汇总规则说明", RULE_VERSION,
        "当前统计限定为 CrownCAD 系统测试范围。选择一个测试阶段后，平台会按阶段定义展开对应轮次，再统计这些议题在各模块下的缺陷数量、修复情况、关闭情况、延期情况和遗留情况。",
        "主表数字和下钻明细使用同一套筛选条件、模块归属和指标判定规则；点击数字后看到的明细数量应与主表单元格保持一致。", s.flowSteps(), buildMetricDefinitions(), null);
  }

  private List<IssueSource> loadBoardScopedSources(Map<String, String> filters, EffectiveFilterGroup effectiveFilterGroup) {
    return buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup, new LinkedHashMap<>()).finalSources();
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, EffectiveFilterGroup effectiveFilterGroup) {
    return buildRuleFlowSnapshot(loaded, effectiveFilterGroup, new LinkedHashMap<>());
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded, EffectiveFilterGroup effectiveFilterGroup, Map<String, List<String>> phaseValueCache) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial;
    List<IssueSource> validBeforeFilter =
        scoped.stream().filter(IssueSource::isVisibleForRegularOrSuggestionColumn).toList();
    List<IssueSource> valid =
        hasTestingPhaseCondition(effectiveFilterGroup.userGroup())
            ? validBeforeFilter.stream().filter(issue -> matchesEffectiveFilterGroup(issue, effectiveFilterGroup, phaseValueCache)).toList()
            : List.of();
    return new RuleFlowSnapshot(scoped, valid, List.of(
        StatisticRuleFlowSupport.step(
            "source-load",
            "加载议题数据",
            "加载已同步到平台的议题数据，并使用平台按老平台规则整理后的项目、阶段、模块、严重程度和处理状态。",
            initial.size(),
            initial,
            this::toRuleFlowSample
        ),
        StatisticRuleFlowSupport.step(
            "scope-filter",
            "限定系统测试范围",
            "按老平台系统测试缺陷汇总入口限定 CrownCAD 项目和测试阶段定义范围，不再以“系统测试/回归测试”标签作为前置范围。",
            initial.size(),
            scoped,
            this::toRuleFlowSample
        ),
        StatisticRuleFlowSupport.step(
            "exclude-invalid-issues",
            "排除无效数据",
            "剔除功能屏蔽、已拒绝以及关闭后属于申请否决/需求如此的议题；仅因建议类被排除的数据只允许回到建议类列。",
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
            "一个议题如果属于多个模块，会分别计入命中的模块；主表单元格与下钻明细使用同一套模块归属和指标判定规则。",
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

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      EffectiveFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    String selectedPhase = selectedTestingPhase(effectiveFilterGroup.userGroup());
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    if (StringUtils.hasText(selectedPhase)) {
      payload.put("testingPhase", selectedPhase);
    }
    payload.put("projectId", String.valueOf(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID));
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=" + SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID
            + ";testingPhase=" + (StringUtils.hasText(selectedPhase) ? selectedPhase : "none"),
        payload,
        definition,
        effectiveFilterGroup.appliedGroup());
  }

  private EffectiveFilterGroup buildEffectiveFilterGroup(StatisticFilterGroup userGroup) {
    StatisticFilterGroup expandedUserGroup = expandLabelGroupConditions(userGroup);
    StatisticFilterCondition defaultCondition =
        labelGroupDefaultFilterService
            .defaultCondition(BOARD_KEY, MODULE_FIELD)
            .orElse(null);
    return new EffectiveFilterGroup(
        expandedUserGroup, defaultCondition, appliedFilterGroup(expandedUserGroup, defaultCondition));
  }

  private StatisticFilterGroup expandLabelGroupConditions(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return emptyFilterGroup();
    }
    List<StatisticFilterCondition> conditions = new ArrayList<>();
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      conditions.add(expandLabelGroupCondition(condition));
    }
    return conditions.isEmpty() ? emptyFilterGroup() : new StatisticFilterGroup(filterGroup.logic(), conditions);
  }

  private StatisticFilterCondition expandLabelGroupCondition(StatisticFilterCondition condition) {
    if (condition == null || !condition.usesLabelGroup()) {
      return condition;
    }
    var expansion =
        labelGroupExpansionService.expand(
            condition.labelGroupId(),
            labelGroupValueType(condition.fieldKey()),
            condition.fieldKey(),
            BOARD_KEY,
            null);
    return new StatisticFilterCondition(
        condition.fieldKey(),
        condition.operator(),
        null,
        null,
        "LABEL_GROUP",
        condition.labelGroupId(),
        StringUtils.hasText(condition.labelGroupName()) ? condition.labelGroupName() : expansion.groupName(),
        expansion.values());
  }

  private StatisticFilterGroup appliedFilterGroup(
      StatisticFilterGroup userGroup, StatisticFilterCondition defaultCondition) {
    if (defaultCondition == null) {
      return userGroup == null ? emptyFilterGroup() : userGroup;
    }
    if (userGroup == null || userGroup.conditions() == null || userGroup.conditions().isEmpty()) {
      return new StatisticFilterGroup("AND", List.of(defaultCondition));
    }
    if (!"OR".equalsIgnoreCase(userGroup.logic())) {
      List<StatisticFilterCondition> merged = new ArrayList<>(userGroup.conditions());
      merged.add(defaultCondition);
      return new StatisticFilterGroup("AND", merged);
    }
    return userGroup;
  }

  private String labelGroupValueType(String fieldKey) {
    return "STRING";
  }

  private List<String> moduleRows(List<IssueSource> scopedSources) {
    Set<String> moduleNames = new LinkedHashSet<>();
    for (IssueSource issue : scopedSources) {
      moduleNames.addAll(issue.moduleNames());
    }
    return moduleNames.stream().toList();
  }

  private Comparator<StatisticRowData> legacySummaryRowComparator() {
    return Comparator.comparingLong((StatisticRowData row) -> metricNumericValue(row, "defect_ratio"))
        .reversed()
        .thenComparing(StatisticRowData::rowLabel, String.CASE_INSENSITIVE_ORDER);
  }

  private long metricNumericValue(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .mapToLong(StatisticCellData::numericValue)
        .findFirst()
        .orElse(0L);
  }

  private List<IssueSource> moduleRowSources(
      List<IssueSource> scopedSources, EffectiveFilterGroup effectiveFilterGroup, Map<String, List<String>> phaseValueCache) {
    StatisticFilterGroup userGroup = effectiveFilterGroup.userGroup();
    if (userGroup == null || userGroup.conditions() == null || userGroup.conditions().isEmpty()) {
      return List.of();
    }
    List<StatisticFilterCondition> phaseConditions =
        userGroup.conditions().stream()
            .filter(condition -> condition != null && "testingPhase".equals(condition.fieldKey()))
            .toList();
    if (phaseConditions.isEmpty()) {
      return List.of();
    }
    if (phaseConditions.stream()
        .filter(condition -> trimTextToNull(condition.value()) != null)
        .anyMatch(condition -> legacyPhaseValues(condition.value(), phaseValueCache).isEmpty())) {
      return List.of();
    }
    List<String> enabledPhaseValues =
        phaseScopeResolver.resolveLegacyCrownCadPhases(loadEnabledPhaseParents());
    if (enabledPhaseValues.isEmpty()) {
      return List.of();
    }
    return scopedSources.stream()
        .filter(issue -> issue.matchesAnyTestingPhaseLike(enabledPhaseValues))
        .filter(issue -> matchesModuleDirectoryFilters(issue, effectiveFilterGroup, phaseValueCache))
        .toList();
  }

  private boolean matchesModuleDirectoryFilters(
      IssueSource issue, EffectiveFilterGroup effectiveFilterGroup, Map<String, List<String>> phaseValueCache) {
    if (effectiveFilterGroup.defaultCondition() != null
        && !matchesCondition(issue, effectiveFilterGroup.defaultCondition(), phaseValueCache)) {
      return false;
    }
    StatisticFilterGroup userGroup = effectiveFilterGroup.userGroup();
    if (userGroup == null || userGroup.conditions() == null || userGroup.conditions().isEmpty()) {
      return true;
    }
    List<StatisticFilterCondition> moduleConditions =
        userGroup.conditions().stream()
            .filter(condition -> condition != null && MODULE_FIELD.equals(condition.fieldKey()))
            .toList();
    if (moduleConditions.isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(userGroup.logic());
    for (StatisticFilterCondition condition : moduleConditions) {
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

  private List<String> loadEnabledPhaseParents() {
    try {
      return phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID);
    } catch (Exception e) {
      log.debug("Failed to load enabled phase parents for {}", BOARD_KEY, e);
      return List.of();
    }
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
        new StatisticRuleMetricDefinition("level1", "一级缺陷", "严重程度为一级缺陷的议题会进入一级缺陷统计，并继续拆分为回退、挂机和其他一级缺陷。", "一级缺陷修复率 = 一级缺陷已修复数量 / 一级缺陷总数", null),
        new StatisticRuleMetricDefinition("priority-summary", "P1/P2/P3", "P1/P2/P3 是优先级统计，一级/二级/三级是严重程度统计，两套口径不能混用。", "某优先级修复率 = 该优先级已修复、已完成、未复现或已关闭数量 / 该优先级总数；缺陷汇总表按老平台口径只展示 P1 关闭率", null),
        new StatisticRuleMetricDefinition("summary", "综合汇总", "综合区展示模块总缺陷数、缺陷占比、延期占比、已修复或未更新、修复率、关闭率、未关闭数量、申请延期和复测未通过。", "修复率 = 已修复、待合并或未更新数量 / 模块总缺陷数；复测未通过按处理状态包含未修复统计", null),
        new StatisticRuleMetricDefinition("new-issue", "新发议题", "新发议题不包含标记为历史遗留的议题。", "新发议题修复率 = 新发议题中已修复、待合并或未更新数量 / 新发议题总数；关闭率还要求议题已关闭且状态为已修复、已完成或未复现", null),
        new StatisticRuleMetricDefinition("legacy", "遗留率", "遗留率沿用老平台系统测试缺陷汇总的历史遗留判定口径。", "一级缺陷遗留率 = 一级缺陷未按已修复口径命中的数量 / 一级缺陷总数；二级、三级遗留按对应严重程度中未修复、未待合并、未更新的数据统计", null));
  }

  private List<IssueSource> loadSources(Map<String, String> filters, EffectiveFilterGroup effectiveFilterGroup) {
    if (!hasTestingPhaseCondition(effectiveFilterGroup.userGroup())) {
      return List.of();
    }
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.put("projectId", String.valueOf(effectiveProjectId(queryFilters)));
    alignTestingPhaseSqlFilter(queryFilters, effectiveFilterGroup.userGroup());
    try {
      return runtimeSupport
          .loadFacts(queryFilters, null)
          .stream()
          .map(this::toIssueSource)
          .toList();
    } catch (Exception e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private long effectiveProjectId(Map<String, String> filters) {
    Long projectId =
        filters == null ? null : StatisticSourceValueSupport.parseLong(filters.get("projectId"));
    return projectId == null ? SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID : projectId;
  }

  private void alignTestingPhaseSqlFilter(Map<String, String> queryFilters, StatisticFilterGroup filterGroup) {
    String selectedPhase = selectedTestingPhase(filterGroup);
    if (selectedPhase == null) {
      queryFilters.remove("testingPhase");
      return;
    }
    List<String> resolvedPhases = legacyPhaseValues(selectedPhase, new LinkedHashMap<>());
    boolean selectedCanBeUsedAsSqlContains =
        !resolvedPhases.isEmpty()
            && resolvedPhases.stream().allMatch(phase -> containsIgnoreCase(phase, selectedPhase));
    if (!selectedCanBeUsedAsSqlContains) {
      queryFilters.remove("testingPhase");
      return;
    }
    queryFilters.put("testingPhase", selectedPhase);
  }

  private String selectedTestingPhase(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return null;
    }
    return filterGroup.conditions().stream()
        .filter(condition -> condition != null && "testingPhase".equals(condition.fieldKey()))
        .map(StatisticFilterCondition::value)
        .map(this::trimTextToNull)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
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

  private SystemTestSummaryQuickFilterOptions loadQuickFilterOptions() {
    try {
      List<IssueFactRecord> records =
          issueFactRecordRepository.findForFilterOptions(
              new IssueFactRecordListRequest(
                  SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  1,
                  20,
                  "updatedAt",
                  "desc"));
      return new SystemTestSummaryQuickFilterOptions(
          toStatisticOptions(OptionItemResponseFactory.fromLegacyBusinessValues(
              records.stream().flatMap(record -> record.moduleNames().stream()).toList())),
          toStatisticOptions(OptionItemResponseFactory.fromLegacyBusinessValues(
              records.stream().map(IssueFactRecord::bugStatus).toList())),
          toStatisticOptions(OptionItemResponseFactory.fromLegacyBusinessValues(
              records.stream().map(IssueFactRecord::delayCause).toList())),
          toStatisticOptions(OptionItemResponseFactory.fromLegacyBusinessValues(
              records.stream().map(IssueFactRecord::authorName).toList())),
          toStatisticOptions(OptionItemResponseFactory.fromLegacyBusinessValues(
              records.stream().map(IssueFactRecord::assigneeName).toList())));
    } catch (Exception error) {
      log.debug("Failed to load quick filter options for {}", BOARD_KEY, error);
      return SystemTestSummaryQuickFilterOptions.empty();
    }
  }

  private List<StatisticFilterOption> toStatisticOptions(List<OptionItemResponse> options) {
    return options.stream()
        .map(option -> new StatisticFilterOption(option.label(), option.value()))
        .toList();
  }

  private boolean matchesFilterGroup(IssueSource issue, StatisticFilterGroup filterGroup) {
    return matchesFilterGroup(issue, filterGroup, new LinkedHashMap<>());
  }

  private boolean matchesEffectiveFilterGroup(
      IssueSource issue, EffectiveFilterGroup effectiveFilterGroup, Map<String, List<String>> phaseValueCache) {
    return matchesFilterGroup(issue, effectiveFilterGroup.userGroup(), phaseValueCache)
        && (effectiveFilterGroup.defaultCondition() == null
            || matchesCondition(issue, effectiveFilterGroup.defaultCondition(), phaseValueCache));
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
      StatisticFilterCondition condition) {
    return matchesCondition(issue, condition, new LinkedHashMap<>());
  }

  private boolean matchesCondition(
      IssueSource issue,
      StatisticFilterCondition condition,
      Map<String, List<String>> phaseValueCache) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    String operator = condition.operator();
    String value = trimTextToNull(condition.value());
    if (condition.usesLabelGroup()) {
      return switch (condition.fieldKey()) {
        case MODULE_FIELD -> matchesSetOperator(issue.moduleNames(), operator, condition.values());
        case "projectName" -> matchesSetOperator(singleValue(issue.projectName()), operator, condition.values());
        case "title" -> matchesSetOperator(singleValue(issue.title()), operator, condition.values());
        case "severityLevel" -> matchesSetOperator(singleValue(issue.severityLevel()), operator, condition.values());
        case "priorityLevel" -> matchesSetOperator(singleValue(issue.priorityLevel()), operator, condition.values());
        case "bugStatus" -> matchesSetOperator(singleValue(issue.bugStatus()), operator, condition.values());
        case "delayCause" -> matchesSetOperator(singleValue(issue.delayCause()), operator, condition.values());
        case "authorName" -> matchesSetOperator(singleValue(issue.authorName()), operator, condition.values());
        case "assigneeName" -> matchesSetOperator(singleValue(issue.assigneeName()), operator, condition.values());
        case "labels" -> matchesSetOperator(issue.labels(), operator, condition.values());
        default -> true;
      };
    }
    return switch (condition.fieldKey()) {
      case "projectName" -> matchesText(issue.projectName(), operator, value);
      case "testingPhase" -> matchesPhase(issue, operator, value, phaseValueCache);
      case MODULE_FIELD -> matchesAny(issue.moduleNames(), operator, value);
      case "title" -> matchesText(issue.title(), operator, value);
      case "severityLevel" -> matchesText(issue.severityLevel(), operator, value);
      case "priorityLevel" -> matchesText(issue.priorityLevel(), operator, value);
      case "bugStatus" -> matchesText(issue.bugStatus(), operator, value);
      case "delayCause" -> matchesText(issue.delayCause(), operator, value);
      case "authorName" -> matchesText(issue.authorName(), operator, value);
      case "assigneeName" -> matchesText(issue.assigneeName(), operator, value);
      case "state" -> matchesIssueState(issue, operator, value);
      case "createdAt" -> matchesDateTime(issue.createdAt(), operator, value, condition.secondaryValue());
      case "updatedAt" -> matchesDateTime(issue.updatedAt(), operator, value, condition.secondaryValue());
      case "labels" -> matchesAny(issue.labels(), operator, value);
      default -> true;
    };
  }

  private boolean matchesIssueState(IssueSource issue, String operator, String value) {
    String state = issue.isClosed() ? "closed" : "open";
    return matchesText(state, operator, value);
  }

  private boolean matchesDateTime(LocalDateTime candidate, String operator, String value, String secondaryValue) {
    if (candidate == null) {
      return "isEmpty".equals(operator);
    }
    if (!StringUtils.hasText(value)) {
      return true;
    }
    LocalDateTime target = parseDateTimeBoundary(value, false);
    if (target == null) {
      return true;
    }
    return switch (operator) {
      case "year" -> candidate.getYear() == target.getYear();
      case "month" -> candidate.getYear() == target.getYear() && candidate.getMonth() == target.getMonth();
      case "day", "at" -> candidate.toLocalDate().equals(target.toLocalDate());
      case "before" -> candidate.isBefore(target);
      case "after" -> candidate.isAfter(target);
      case "between" -> {
        LocalDateTime end = parseDateTimeBoundary(secondaryValue, true);
        yield end == null || (!candidate.isBefore(target) && !candidate.isAfter(end));
      }
      case "isEmpty" -> false;
      case "isNotEmpty" -> true;
      default -> true;
    };
  }

  private LocalDateTime parseDateTimeBoundary(String value, boolean endOfDay) {
    String normalized = trimTextToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return LocalDateTime.parse(normalized);
    } catch (java.time.format.DateTimeParseException ignored) {
      // Date-only picker values are common in board filters.
    }
    try {
      LocalDate date = LocalDate.parse(normalized);
      return endOfDay ? date.atTime(23, 59, 59, 999_999_999) : date.atStartOfDay();
    } catch (java.time.format.DateTimeParseException ignored) {
      return null;
    }
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
      case "eq" -> value == null || issue.matchesAnyTestingPhaseLike(legacyPhaseValues);
      case "ne" -> value == null || !issue.matchesAnyTestingPhaseLike(legacyPhaseValues);
      case "contains" -> value == null || issue.matchesTestingPhaseLike(value);
      case "isEmpty" -> !StringUtils.hasText(issue.testingPhase());
      case "isNotEmpty" -> StringUtils.hasText(issue.testingPhase());
      default -> true;
    };
  }

  private List<String> legacyPhaseValues(String value, Map<String, List<String>> phaseValueCache) {
    String normalized = trimTextToNull(value);
    if (normalized == null) {
      return List.of();
    }
    return phaseValueCache.computeIfAbsent(normalized, key -> {
      List<String> resolved = phaseScopeResolver.resolveLegacyCrownCadPhases(key);
      return resolved.isEmpty() ? List.of(key) : resolved;
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

  private boolean matchesSetOperator(List<String> candidates, String operator, List<String> expectedValues) {
    if ("partialContainsAny".equals(operator)) {
      return matchesPartialContainsAny(candidates, expectedValues);
    }
    Set<String> candidateSet = normalizedSet(candidates);
    Set<String> expectedSet = normalizedSet(expectedValues);
    boolean containsAll = candidateSet.containsAll(expectedSet);
    boolean intersects = expectedSet.stream().anyMatch(candidateSet::contains);
    return switch (operator) {
      case "intersects" -> intersects;
      case "notIntersects" -> !intersects;
      case "containsAll" -> containsAll;
      case "notContainsAll" -> !containsAll;
      default -> true;
    };
  }

  private boolean matchesPartialContainsAny(List<String> candidates, List<String> expectedValues) {
    List<String> safeCandidates = candidates == null ? List.of() : candidates;
    List<String> safeExpected = expectedValues == null ? List.of() : expectedValues;
    return safeCandidates.stream()
        .filter(value -> trimTextToNull(value) != null)
        .anyMatch(candidate ->
            safeExpected.stream()
                .filter(value -> trimTextToNull(value) != null)
                .anyMatch(expected -> containsIgnoreCase(candidate, expected)));
  }

  private Set<String> normalizedSet(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .map(this::trimTextToNull)
        .filter(java.util.Objects::nonNull)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private List<String> singleValue(String value) {
    return StringUtils.hasText(value) ? List.of(value) : List.of();
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
        source.sourceInstance(),
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
        source.milestoneTitle(),
        source.excluded(),
        source.exclusionReason(),
        source.fixed(),
        source.delayIssue(),
        source.regression(),
        source.crash(),
        source.level1Other(),
        false,
        "",
        source.legacy(),
        source.assigneeName(),
        source.fixUser(),
        source.functionName(),
        source.reasonCategory(),
        source.moduleNames(),
        source.labels());
  }

  private Map<String, Object> toDetailRecord(IssueSource i) {
    Map<String, Object> r = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(r, i.sourceInstance(), i.iid(), i.projectId(), i.projectName(), i.id(), i.labels());
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

  private SystemTestIssueSearchRowResponse toIssueExportRecord(IssueSource i) {
    SystemTestLegacyCauseExportFields causeFields =
        SystemTestLegacyCauseExportFields.fromReasonText(i.reasonCategory());
    return new SystemTestIssueSearchRowResponse(
        i.id(),
        i.iid(),
        null,
        i.sourceInstance(),
        i.projectId(),
        i.projectName(),
        i.title(),
        i.issueState(),
        i.testingPhase(),
        i.displaySeverityLevel(),
        i.priorityLevel(),
        i.bugStatus(),
        i.category(),
        i.milestoneTitle(),
        i.delayCause(),
        i.authorName(),
        i.assigneeName(),
        String.join(" & ", i.moduleNames()),
        i.functionName(),
        i.fixUser(),
        causeFields.fixStatus(),
        causeFields.majorCause(),
        causeFields.secondCause(),
        causeFields.specificReason(),
        causeFields.modification(),
        causeFields.causedByOther(),
        causeFields.effectFunction(),
        causeFields.hasTested(),
        causeFields.potentialImpact(),
        causeFields.relationTableUpdated(),
        i.createdAt(),
        i.updatedAt(),
        i.closedAt(),
        i.labels());
  }

  private Predicate<IssueSource> matchesMetric(String key) {
    return switch (key) {
      case "level1_back" -> IssueSource::isRegularLevel1Back;
      case "level1_hang" -> IssueSource::isRegularLevel1Hang;
      case "level1_other" -> IssueSource::isRegularLevel1Other;
      case "level1_fixed" -> i -> i.isRegularLevel1() && i.isLegacyFixed();
      case "level1_total" -> IssueSource::isRegularLevel1;
      case "level2_fixed" -> i -> i.isRegularLevel2() && i.isLegacyFixed();
      case "level2_total" -> IssueSource::isRegularLevel2;
      case "level3_fixed" -> i -> i.isRegularLevel3() && i.isLegacyFixed();
      case "level3_total" -> IssueSource::isRegularLevel3;
      case "suggestion_total" -> IssueSource::isSuggestion;
      case "p1_count" -> i -> i.isRegularMetricIssue() && i.isPriority("P1");
      case "p2_count" -> i -> i.isRegularMetricIssue() && i.isPriority("P2");
      case "p3_count" -> i -> i.isRegularMetricIssue() && i.isPriority("P3");
      case "solved_count" -> i -> i.isRegularMetricIssue() && i.isLegacyFixed();
      case "open_count" -> i -> i.isRegularMetricIssue() && !i.isClosed();
      case "extension_count" -> i -> i.isRegularMetricIssue() && i.hasExtensionLabel();
      case "retest_failed_count" -> i -> i.isRegularMetricIssue() && i.isRetestFailed();
      case "new_issue_fixed" -> i -> i.isRegularMetricIssue() && i.isNewIssue() && i.isLegacyFixed();
      case "new_issue_total" -> i -> i.isRegularMetricIssue() && i.isNewIssue();
      case "level2_legacy_count" -> i -> i.isRegularLevel2() && i.isLegacyOpenForLevel23();
      case "level3_legacy_count" -> i -> i.isRegularLevel3() && i.isLegacyOpenForLevel23();
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
  private static long rateSort(long n, long d) { return StatisticMetricCalculator.ratioSortValue(n, d); }
  private static long percentSort(double value) { return StatisticMetricCalculator.percentSortValue(value); }

  private StatisticRowData toSummaryRowData(String rowKey, String rowLabel, List<IssueSource> sourceIssues) {
    List<IssueSource> rowIssues = sourceIssues.stream().filter(issue -> matchesRow(issue, rowKey)).toList();
    long regularOverall = sourceIssues.stream().filter(IssueSource::isRegularMetricIssue).count();
    SummaryCounts counts = SummaryCounts.from(rowIssues, regularOverall);
    return new StatisticRowData(rowKey, rowLabel, List.of(
        cell("level1_back", counts.level1Back(), count(counts.level1Back()), true, rowKey),
        cell("level1_hang", counts.level1Hang(), count(counts.level1Hang()), true, rowKey),
        cell("level1_other", counts.level1Other(), count(counts.level1Other()), true, rowKey),
        cell("level1_fixed", counts.level1Fixed(), count(counts.level1Fixed()), true, rowKey),
        cell("level1_total", counts.level1(), count(counts.level1()), true, rowKey),
        cell("level1_rate", rateSort(counts.level1Fixed(), counts.level1()), rate(counts.level1Fixed(), counts.level1()), false, rowKey),
        cell("level2_fixed", counts.level2Fixed(), count(counts.level2Fixed()), true, rowKey),
        cell("level2_total", counts.level2(), count(counts.level2()), true, rowKey),
        cell("level2_rate", rateSort(counts.level2Fixed(), counts.level2()), rate(counts.level2Fixed(), counts.level2()), false, rowKey),
        cell("level3_fixed", counts.level3Fixed(), count(counts.level3Fixed()), true, rowKey),
        cell("level3_total", counts.level3(), count(counts.level3()), true, rowKey),
        cell("level3_rate", rateSort(counts.level3Fixed(), counts.level3()), rate(counts.level3Fixed(), counts.level3()), false, rowKey),
        cell("suggestion_total", counts.suggestion(), count(counts.suggestion()), true, rowKey),
        cell("p1_count", counts.p1(), count(counts.p1()), true, rowKey),
        cell("p1_fix_rate", rateSort(counts.p1Fixed(), counts.p1()), rate(counts.p1Fixed(), counts.p1()), false, rowKey),
        cell("p1_close_rate", rateSort(counts.p1Closed(), counts.p1()), rate(counts.p1Closed(), counts.p1()), false, rowKey),
        cell("p2_count", counts.p2(), count(counts.p2()), true, rowKey),
        cell("p2_fix_rate", rateSort(counts.p2Fixed(), counts.p2()), rate(counts.p2Fixed(), counts.p2()), false, rowKey),
        cell("p2_close_rate", rateSort(counts.p2Closed(), counts.p2()), rate(counts.p2Closed(), counts.p2()), false, rowKey),
        cell("p3_count", counts.p3(), count(counts.p3()), true, rowKey),
        cell("p3_fix_rate", rateSort(counts.p3Fixed(), counts.p3()), rate(counts.p3Fixed(), counts.p3()), false, rowKey),
        cell("p3_close_rate", rateSort(counts.p3Closed(), counts.p3()), rate(counts.p3Closed(), counts.p3()), false, rowKey),
        cell("module_total", counts.total(), count(counts.total()), true, rowKey),
        cell("defect_ratio", percentSort(counts.defectRatio()), percent(counts.defectRatio()), false, rowKey),
        cell("delay_defect_ratio", percentSort(counts.delayRatio()), percent(counts.delayRatio()), false, rowKey),
        cell("solved_count", counts.solved(), count(counts.solved()), true, rowKey),
        cell("fix_rate", rateSort(counts.solved(), counts.total()), rate(counts.solved(), counts.total()), false, rowKey),
        cell("close_rate", rateSort(counts.closed(), counts.total()), rate(counts.closed(), counts.total()), false, rowKey),
        cell("open_count", counts.open(), count(counts.open()), true, rowKey),
        cell("extension_count", counts.extension(), count(counts.extension()), true, rowKey),
        cell("retest_failed_count", counts.retestFailed(), count(counts.retestFailed()), true, rowKey),
        cell("new_issue_fixed", counts.newFixed(), count(counts.newFixed()), true, rowKey),
        cell("new_issue_total", counts.newTotal(), count(counts.newTotal()), true, rowKey),
        cell("new_issue_fix_rate", rateSort(counts.newFixed(), counts.newTotal()), rate(counts.newFixed(), counts.newTotal()), false, rowKey),
        cell("new_issue_close_rate", rateSort(counts.newClosed(), counts.newTotal()), rate(counts.newClosed(), counts.newTotal()), false, rowKey),
        cell("level1_legacy_rate", rateSort(counts.level1Legacy(), counts.level1()), rate(counts.level1Legacy(), counts.level1()), false, rowKey),
        cell("level2_legacy_count", counts.level2Legacy(), count(counts.level2Legacy()), true, rowKey),
        cell("level3_legacy_count", counts.level3Legacy(), count(counts.level3Legacy()), true, rowKey),
        cell("level23_legacy_rate", rateSort(counts.level23Legacy(), counts.total()), rate(counts.level23Legacy(), counts.total()), false, rowKey)));
  }

  private StatisticCellData cell(String key, long numericValue, String displayValue, boolean drilldown, String rowKey) {
    return new StatisticCellData(key, numericValue, displayValue, drilldown, drilldown ? "issue-list" : null, Map.of("rowKey", rowKey));
  }

  private record SummaryCounts(
      long total,
      long solved,
      long closed,
      long open,
      long delayed,
      long extension,
      long retestFailed,
      long level1Back,
      long level1Hang,
      long level1Other,
      long level1,
      long level1Fixed,
      long level2,
      long level2Fixed,
      long level2Legacy,
      long level3,
      long level3Fixed,
      long level3Legacy,
      long suggestion,
      long p1,
      long p1Fixed,
      long p1Closed,
      long p2,
      long p2Fixed,
      long p2Closed,
      long p3,
      long p3Fixed,
      long p3Closed,
      long newTotal,
      long newFixed,
      long newClosed,
      long level23Legacy,
      double defectRatio,
      double delayRatio) {
    static SummaryCounts from(List<IssueSource> issues, long overall) {
      List<IssueSource> regularIssues = issues.stream().filter(IssueSource::isRegularMetricIssue).toList();
      long total = regularIssues.size();
      long solved = count(regularIssues, IssueSource::isLegacyFixed);
      long closed = count(regularIssues, IssueSource::isClosed);
      long delayed = count(regularIssues, IssueSource::delayIssue);
      long level1 = count(regularIssues, IssueSource::isLevel1);
      long level1Fixed = count(regularIssues, issue -> issue.isLevel1() && issue.isLegacyFixed());
      long level2 = count(regularIssues, IssueSource::isLevel2);
      long level3 = count(regularIssues, IssueSource::isLevel3);
      long newTotal = count(regularIssues, IssueSource::isNewIssue);
      return new SummaryCounts(
          total,
          solved,
          closed,
          total - closed,
          delayed,
          count(regularIssues, IssueSource::hasExtensionLabel),
          count(regularIssues, IssueSource::isRetestFailed),
          count(regularIssues, IssueSource::isLevel1Back),
          count(regularIssues, IssueSource::isLevel1Hang),
          count(regularIssues, IssueSource::isLevel1Other),
          level1,
          level1Fixed,
          level2,
          count(regularIssues, issue -> issue.isLevel2() && issue.isLegacyFixed()),
          count(regularIssues, issue -> issue.isLevel2() && issue.isLegacyOpenForLevel23()),
          level3,
          count(regularIssues, issue -> issue.isLevel3() && issue.isLegacyFixed()),
          count(regularIssues, issue -> issue.isLevel3() && issue.isLegacyOpenForLevel23()),
          count(issues, IssueSource::isSuggestion),
          count(regularIssues, issue -> issue.isPriority("P1")),
          count(regularIssues, issue -> issue.isPriority("P1") && issue.isPriorityFixed()),
          count(regularIssues, issue -> issue.isPriority("P1") && issue.isP1Closed()),
          count(regularIssues, issue -> issue.isPriority("P2")),
          count(regularIssues, issue -> issue.isPriority("P2") && issue.isPriorityFixed()),
          count(regularIssues, issue -> issue.isPriority("P2") && issue.isPriorityClosedWithResolvedStatus()),
          count(regularIssues, issue -> issue.isPriority("P3")),
          count(regularIssues, issue -> issue.isPriority("P3") && issue.isPriorityFixed()),
          count(regularIssues, issue -> issue.isPriority("P3") && issue.isPriorityClosedWithResolvedStatus()),
          newTotal,
          count(regularIssues, issue -> issue.isNewIssue() && issue.isLegacyFixed()),
          count(regularIssues, issue -> issue.isNewIssue() && issue.isNewClosed()),
          count(regularIssues, issue -> (issue.isLevel2() || issue.isLevel3()) && issue.isLegacyFixed()),
          StatisticMetricCalculator.percentageOf(total, overall),
          StatisticMetricCalculator.percentageOf(delayed, total));
    }

    private static long count(List<IssueSource> issues, Predicate<IssueSource> predicate) {
      return issues.stream().filter(predicate).count();
    }

    long level1Legacy() {
      return level1 - level1Fixed;
    }
  }

  private record IssueSource(Long id, Integer iid, String sourceInstance, String title, Long projectId, String projectName, String authorName, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime closedAt, String issueState, String testingPhase, String systemTestLabel, String severityLevel, String priorityLevel, String bugStatus, String category, String delayCause, String milestoneTitle, boolean excluded, String exclusionReason, boolean fixed, boolean delayIssue, boolean regression, boolean crash, boolean level1Other, boolean illegal, String illegalReason, boolean legacy, String assigneeName, String fixUser, String functionName, String reasonCategory, List<String> moduleNames, List<String> labels) {
    boolean isClosed() { return closedAt != null || "closed".equalsIgnoreCase(issueState); }
    boolean isPriority(String priority) { return priority.equalsIgnoreCase(priorityLevel); }
    boolean isSeverity(String severity) { return severity.equalsIgnoreCase(severityLevel); }
    boolean isLevel1() { return isSeverity("LEVEL1"); }
    boolean isLevel1Back() { return isLevel1() && regression; }
    boolean isLevel1Hang() { return isLevel1() && crash; }
    boolean isLevel1Other() { return isLevel1() && level1Other; }
    boolean isLevel2() { return isSeverity("LEVEL2"); }
    boolean isLevel3() { return isSeverity("LEVEL3"); }
    /*
     * 建议类列是领导确认后的新平台独立列：它恢复 category=建议 的议题，但不让这些议题
     * 进入其它任何缺陷数量或率类指标。不要因为老平台这里经常显示 0 就改回 0；老平台是
     * 为了避免建议类污染二级/三级等指标才整体排除了建议类。
     */
    boolean isSuggestion() {
      return SystemTestSuggestionMetricSupport.isSuggestionColumnIssue(excluded, exclusionReason, severityLevel, category);
    }
    boolean isRegularMetricIssue() {
      return SystemTestSuggestionMetricSupport.isRegularMetricIssue(excluded, exclusionReason, severityLevel, category);
    }
    boolean isVisibleForRegularOrSuggestionColumn() {
      return isRegularMetricIssue() || isSuggestion();
    }
    boolean isRegularLevel1() { return isRegularMetricIssue() && isLevel1(); }
    boolean isRegularLevel1Back() { return isRegularMetricIssue() && isLevel1Back(); }
    boolean isRegularLevel1Hang() { return isRegularMetricIssue() && isLevel1Hang(); }
    boolean isRegularLevel1Other() { return isRegularMetricIssue() && isLevel1Other(); }
    boolean isRegularLevel2() { return isRegularMetricIssue() && isLevel2(); }
    boolean isRegularLevel3() { return isRegularMetricIssue() && isLevel3(); }
    boolean isNewIssue() { return !contains(bugStatus, "历史遗留"); }
    boolean isLegacyFixed() { return containsAny(bugStatus, LEGACY_FIXED_STATUS_TOKENS); }
    boolean isPriorityFixed() { return containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS) || isClosed(); }
    boolean isP1Closed() { return isClosed(); }
    boolean isPriorityClosedWithResolvedStatus() { return isClosed() && containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS); }
    boolean isNewClosed() { return isNewIssue() && isPriorityClosedWithResolvedStatus(); }
    boolean isLegacyOpenForLevel23() { return !containsAny(bugStatus, LEGACY_FIXED_STATUS_TOKENS); }
    boolean hasExtensionLabel() { return contains(bugStatus, "申请延期") || labels.contains("申请延期"); }
    boolean isRetestFailed() { return contains(bugStatus, "未修复"); }
    boolean matchesAnyTestingPhaseLike(List<String> expectedPhases) {
      if (expectedPhases == null || expectedPhases.isEmpty()) {
        return false;
      }
      return expectedPhases.stream().anyMatch(this::matchesTestingPhaseLike);
    }
    boolean matchesTestingPhaseLike(String expectedPhase) {
      return StringUtils.hasText(testingPhase)
          && StringUtils.hasText(expectedPhase)
          && testingPhase.toLowerCase(Locale.ROOT).contains(expectedPhase.toLowerCase(Locale.ROOT));
    }
    String displaySeverityLevel() {
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
    }
    private boolean containsAny(String value, List<String> tokens) { return tokens.stream().anyMatch(token -> contains(value, token)); }
    private boolean contains(String value, String token) { return StringUtils.hasText(value) && value.contains(token); }
  }

  private record EffectiveFilterGroup(
      StatisticFilterGroup userGroup,
      StatisticFilterCondition defaultCondition,
      StatisticFilterGroup appliedGroup) {}

  private record RuleFlowSnapshot(List<IssueSource> scopedSources, List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}

  private record SystemTestSummaryQuickFilterOptions(
      List<StatisticFilterOption> moduleNames,
      List<StatisticFilterOption> bugStatuses,
      List<StatisticFilterOption> delayCauses,
      List<StatisticFilterOption> authorNames,
      List<StatisticFilterOption> assigneeNames) {
    static SystemTestSummaryQuickFilterOptions empty() {
      return new SystemTestSummaryQuickFilterOptions(List.of(), List.of(), List.of(), List.of(), List.of());
    }
  }
}
