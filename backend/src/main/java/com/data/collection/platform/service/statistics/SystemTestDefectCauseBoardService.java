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
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.RealtimeIncrementalRefreshService;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseFilterGroupExpander;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SystemTestDefectCauseBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardWorkbookExportSupport,
        StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "system-test-defect-cause";
  private static final String RULE_VERSION = "system-test-defect-cause@2026-07-28-v6";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "共计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final Pattern TURN_LABEL_PATTERN =
      Pattern.compile("(第[一二三四五六七八九十0-9]+轮系统测试|回归测试|系统测试)");
  private static final String PHASE_OPTION_SQL = """
      select coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(label_names,'') as label_names
        from issue_fact
       where deleted = false
      """;
  private static final String FACT_SQL = """
      select issue_id as id, issue_iid as iid, source_instance, title, project_id, project_name,
             coalesce(author_name,'') as author_name, updated_at_source as updated_at,
             closed_at_source as closed_at, coalesce(issue_state,'opened') as issue_state,
             coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(severity_level,'') as severity_level,
             coalesce(category,'') as category,
             coalesce(is_excluded,false) as is_excluded,
             coalesce(reason_category,'') as reason_category,
             coalesce(raw_payload,'') as reason_text,
             coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names
       from issue_fact
       where deleted = false
      """;
  private static final String CAUSE_TEXT_SQL = "coalesce(reason_category, '')";
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.systemTest(
          "标题",
          "模块",
          List.of(
              new StatisticDetailColumn("testingPhase", "测试阶段", 180, 180, true, "tag"),
              new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true, "tag"),
              StatisticIssueDetailColumns.state("状态")),
          List.of(StatisticIssueDetailColumns.author("创建人")),
          List.of(StatisticIssueDetailColumns.project("所属项目")));
  private static final List<DefectCauseMetricCatalog.Metric> CAUSE_METRICS =
      DefectCauseMetricCatalog.METRICS;

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final IssueFactQueryService issueFactQueryService;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final StatisticBoardSnapshotService snapshotService;

  public SystemTestDefectCauseBoardService(
      JsonUtils jsonUtils,
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService,
      IssueFactQueryService issueFactQueryService,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseCatalogService phaseCatalogService,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      StatisticBoardSnapshotService snapshotService) {
    super(jsonUtils);
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
    this.issueFactQueryService = issueFactQueryService;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseCatalogService = phaseCatalogService;
    this.phaseScopeResolver = phaseScopeResolver;
    this.snapshotService = snapshotService;
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
        BOARD_KEY,
        "缺陷原因分析",
        "按模块分析系统测试缺陷的原因分类。",
        "",
        "",
        "模块",
        List.of(StatisticFilterFieldFactory.select("testingPhase", "项目", 220, phaseOptions)),
        List.of(
            StatisticColumnGroup.withChildren(
                "requirement-problem",
                "需求问题",
                List.of(
                    group("requirement-basic", "需求归类", List.of("demand_misunderstand", "missing_requirement", "add_demand_2", "demand_change_not_sync")))),
            StatisticColumnGroup.withChildren(
                "design-problem",
                "设计问题",
                List.of(group("design-basic", "设计归类", List.of("design_forget", "design_scheme", "incomplete", "prompt_message")))),
            StatisticColumnGroup.withChildren(
                "code-problem",
                "编码规范",
                List.of(group("code-basic", "编码归类", List.of("standard_error", "function_forget", "logic_calculation_algorithm_error", "logic_flow_control_error", "logic_data_state_process_error", "logic_business_logic_error", "logic_integration_interface_error")))),
            StatisticColumnGroup.withChildren(
                "package-problem",
                "打包问题",
                List.of(group("package-basic", "打包归类", List.of("environment_config_issue", "compilation_package_deployment_issue")))),
            StatisticColumnGroup.withChildren(
                "dependency-problem",
                "依赖问题",
                List.of(group("dependency-basic", "依赖归类", List.of("other_thirdParty", "algorithm_not_support", "mechanism_not_support", "precondition_data_exception", "other_unIdentifyTask")))),
            StatisticColumnGroup.withChildren(
                "precision-problem",
                "精度问题",
                List.of(group("precision-basic", "精度归类", List.of("precision_constraint_exception", "precision_algorithm_exception"))))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的缺陷原因分析结果。");
  }

  private StatisticColumnGroup group(String key, String label, List<String> metricKeys) {
    List<StatisticColumnLeaf> columns =
        metricKeys.stream().map(this::leafByMetricKey).toList();
    return new StatisticColumnGroup(key, label, columns);
  }

  private StatisticColumnLeaf leafByMetricKey(String metricKey) {
    DefectCauseMetricCatalog.Metric metric = metric(metricKey);
    return leaf(metric.key(), metric.label(), true, "count");
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);
    String selectedTestingPhase =
        SystemTestPhaseMembershipPolicy.selectedTestingPhase(effectiveFilterGroup);
    StatisticFilterGroup appliedGroup = effectiveFilterGroup;
    return snapshotService.readOrRefresh(
        snapshotRequest(filters, appliedGroup, definition, selectedTestingPhase),
        () -> buildBoardResponse(filters, appliedGroup, definition));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    long startedAt = System.currentTimeMillis();
    List<String> moduleRows = loadBoardModuleRows(filters, effectiveFilterGroup);
    Map<String, AggregateCounts> aggregateCounts = loadBoardAggregateCounts(filters, effectiveFilterGroup);
    AggregateCounts totalCounts = loadBoardTotalAggregateCounts(filters, effectiveFilterGroup);

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (String moduleName : moduleRows) {
      buckets.computeIfAbsent(moduleName, AggregateBucket::new);
    }
    for (Map.Entry<String, AggregateCounts> entry : aggregateCounts.entrySet()) {
      AggregateBucket bucket = buckets.get(entry.getKey());
      if (bucket != null) {
        bucket.accept(entry.getValue());
      }
    }

    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
            .map(AggregateBucket::toRowData)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (!buckets.isEmpty()) {
      AggregateBucket totalBucket = new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY);
      totalBucket.accept(totalCounts);
      rows.add(totalBucket.toRowData());
      rows.add(AggregateBucket.ratioRow(totalBucket));
    }

    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount =
        definition.columnGroups().stream()
            .flatMap(group -> group.leafColumns().stream())
            .mapToInt(column -> column.drilldown() ? 1 : 0)
            .sum();
    StatisticBoardMeta meta =
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount);
    return new StatisticBoardResponse(definition, appliedFilters(filters, effectiveFilterGroup), effectiveFilterGroup, rows, meta);
  }

  @Override
  public void refreshSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    Set<String> affectedPhases =
        new LinkedHashSet<>(
            phaseCatalogService.listParentNames(
                SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, context));
    if (affectedPhases.isEmpty()) {
      return;
    }
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);
    for (StatisticFilterOption option : phaseOptions.stream()
        .filter(candidate -> affectedPhases.contains(candidate.value()))
        .toList()) {
      Map<String, String> filters = Map.of("testingPhase", option.value());
      StatisticFilterGroup filterGroup =
          SystemTestPhaseFilterGroupExpander.expand(
              new StatisticFilterGroup(
                  "AND",
                  List.of(new StatisticFilterCondition("testingPhase", "eq", option.value(), null))),
              phaseScopeResolver);
      snapshotService.save(
          snapshotRequest(filters, filterGroup, definition, option.value()),
          buildBoardResponse(filters, filterGroup, definition));
    }
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, loadPhaseOptions());
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters(), effectiveFilterGroup), effectiveFilterGroup).reasonSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse(
        "缺陷原因分析明细",
        "展示当前模块与缺陷原因命中的议题明细。",
        DETAIL_COLUMNS,
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending",
        pageSlice.quickFilterOptions());
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return realtimeWorkspaceService.getStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus(Map<String, String> filters) {
    return realtimeWorkspaceService.getStatus(BOARD_KEY, filters);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return realtimeWorkspaceService.requestRefreshWithResult(BOARD_KEY, this::refreshMirrorForRealtimeView);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(phaseOptions));
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    long moduleCount =
        snapshot.scopedSources().stream()
            .flatMap(issue -> issue.moduleNames().stream())
            .distinct()
            .count();
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "缺陷原因分析规则说明",
        RULE_VERSION,
        "从议题回复的缺陷原因段落识别需求问题、设计问题、编码规范等原因分类。",
        "模块行来自当前系统测试范围内的模块全集；不要求议题携带已修复/完成标签；同一议题关联多个模块或多个缺陷原因时会分别计数。",
        List.of(
            snapshot.flowSteps().get(0),
            snapshot.flowSteps().get(1),
            snapshot.flowSteps().get(2),
            snapshot.flowSteps().get(3),
            snapshot.flowSteps().get(4),
            StatisticRuleFlowSupport.step(
                "group-by-module",
                "按模块聚合",
                "先用当前系统测试范围内的模块全集生成行，再将命中缺陷原因的议题按模块展开并归类聚合。",
                snapshot.reasonSources().size(),
                moduleCount,
                snapshot.reasonSources(),
                this::toRuleFlowSample
            )),
        CAUSE_METRICS.stream()
            .map(metric -> new StatisticRuleMetricDefinition(
                metric.key(),
                metric.label(),
                "按缺陷原因说明识别：" + String.join(" / ", metric.tokens()),
                metric.label() + "数量 = 当前模块内命中该原因分类的缺陷数量",
                null))
            .toList(),
        null);
  }

  @Override
  public byte[] exportBoardWorkbook(Map<String, String> filters) {
    return SystemTestLegacyWorkbookExportSupport.exportDefectCause(loadBoard(filters));
  }

  @Override
  public String exportFilename() {
    return "缺陷原因统计表.xlsx";
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(loadPhaseOptions()));
    String phase = SystemTestPhaseMembershipPolicy.selectedTestingPhase(filterGroup);
    if (StringUtils.hasText(phase)) {
      return phase + "-缺陷原因统计表.xlsx";
    }
    return exportFilename();
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial.stream().filter(IssueSource::inSystemTestScope).toList();
    List<IssueSource> valid = scoped.stream().filter(issue -> !issue.excluded()).toList();
    SystemTestPhaseMembershipPolicy.Membership phaseMembership =
        SystemTestPhaseMembershipPolicy.membership(
            filterGroup,
            phaseScopeResolver,
            SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER);
    List<IssueSource> phaseFiltered =
        valid.stream().filter(phaseMembership::matches).toList();
    List<IssueSource> withReason =
        phaseFiltered.stream().filter(IssueSource::hasDefectCause).toList();
    return new RuleFlowSnapshot(
        phaseFiltered,
        withReason,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题数据",
                "加载已同步到平台的议题数据，并使用整理后的模块、测试阶段和缺陷原因说明。",
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
                "按系统测试公共规则剔除功能屏蔽、已拒绝、建议，以及关闭后属于申请否决/需求如此的议题；不额外要求已修复/完成标签。",
                scoped.size(),
                valid,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "phase-filter",
                "应用测试阶段筛选",
                "根据页面上的测试阶段筛选进一步收敛范围；未选择时保留全部系统测试阶段。",
                valid.size(),
                phaseFiltered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "reason-category-filter",
                "保留已识别原因",
                "只保留评论文本中能识别出缺陷原因分类的议题，原因个数按命中的原因分类计算。",
                phaseFiltered.size(),
                withReason,
                this::toRuleFlowSample
            )));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 原因: " + String.join("、", issue.causeLabels()) + " | 模块: " + String.join("、", issue.moduleNames()));
  }
  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey)
        || TOTAL_ROW_KEY.equals(rowKey)
        || issue.moduleNames().contains(rowKey);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    if (!StringUtils.hasText(columnKey)) {
      return issue -> true;
    }
    return issue -> issue.matchesMetric(columnKey);
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "testingPhase" -> SortSupport.nullableString(IssueSource::primaryPhaseLabel);
          case "reasonCategory" -> SortSupport.nullableString(IssueSource::reasonCategory);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.moduleNames()));
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "state" -> SortSupport.nullableComparable(issue -> issue.isClosed() ? 1 : 0);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName(), issue.id(), issue.labels());
    record.put("title", issue.title());
    record.put("testingPhase", displayPhaseLabel(issue.primaryPhaseLabel(), null));
    record.put("reasonCategory", String.join("、", issue.causeLabels()));
    record.put("moduleNames", String.join("、", issue.moduleNames()));
    record.put("projectName", issue.projectName());
    record.put("authorName", issue.authorName());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private List<IssueSource> loadSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = effectiveProjectId(queryFilters);
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        defectCausePhasePredicate(filterGroup);
    try {
      List<IssueSource> facts = ensureFactsReady(projectId, queryFilters, phasePredicate);
      return facts.isEmpty() ? List.of() : facts;
    } catch (DataAccessException e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult refreshMirrorForRealtimeView() {
    return realtimeIncrementalRefreshService.requestIncrementalRefresh(
        com.data.collection.platform.entity.WorkspaceRefreshRequest.global(BOARD_KEY));
  }

  private List<IssueSource> ensureFactsReady(
      Long projectId,
      Map<String, String> filters,
      SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate) {
    List<IssueSource> facts = loadSourcesFromFact(projectId, filters, phasePredicate);
    if (!facts.isEmpty()) {
      return facts;
    }
    log.info("System test defect cause board returned empty result without triggering synchronous rebuild");
    return List.of();
  }

  private List<IssueSource> loadSourcesFromFact(
      Long projectId,
      Map<String, String> filters,
      SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate) {
    Map<String, String> mergedFilters = new LinkedHashMap<>();
    if (filters != null) {
      mergedFilters.putAll(filters);
    }
    if (projectId != null) {
      mergedFilters.put("projectId", String.valueOf(projectId));
    }
    return issueFactQueryService.query(FACT_SQL, mergedFilters, phasePredicate.sql(), phasePredicate.args(), this::mapIssueFact);
  }

  private long effectiveProjectId(Map<String, String> filters) {
    Long projectId =
        filters == null ? null : StatisticSourceValueSupport.parseLong(filters.get("projectId"));
    return projectId == null ? SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID : projectId;
  }

  private List<String> loadBoardModuleRows(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = effectiveProjectId(queryFilters);
    queryFilters.put("projectId", String.valueOf(projectId));
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        moduleRowPhasePredicate(filterGroup);
    String sql = """
        select btrim(modules.module_name) as module_name
          from issue_fact
          cross join lateral regexp_split_to_table(coalesce(module_names,''), ',') as modules(module_name)
         where deleted = false
           and coalesce(module_names,'') <> ''
        """;
    try {
      return issueFactQueryService.query(
              sql,
              queryFilters,
              phasePredicate.sql(),
              phasePredicate.args(),
              """
              group by btrim(modules.module_name)
              having btrim(modules.module_name) <> ''
                 and btrim(modules.module_name) not like '未设定%'
              """,
              (rs, rowNum) -> StatisticSourceValueSupport.text(rs.getString("module_name"), ""))
          .stream()
          .filter(StringUtils::hasText)
          .distinct()
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    } catch (DataAccessException e) {
      log.warn("Failed to load system test defect cause module rows", e);
      return List.of();
    }
  }

  private Map<String, AggregateCounts> loadBoardAggregateCounts(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = effectiveProjectId(queryFilters);
    queryFilters.put("projectId", String.valueOf(projectId));
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        defectCausePhasePredicate(filterGroup);
    String sql = buildBoardAggregateSql();
    try {
      return issueFactQueryService.query(
              sql,
              queryFilters,
              phasePredicate.sql(),
              phasePredicate.args(),
              "group by btrim(modules.module_name) having btrim(modules.module_name) <> ''",
              this::mapAggregateCounts)
          .stream()
          .collect(
              java.util.stream.Collectors.toMap(
                  Map.Entry::getKey,
                  Map.Entry::getValue,
                  AggregateCounts::plus,
                  LinkedHashMap::new));
    } catch (DataAccessException e) {
      log.warn("Failed to load system test defect cause aggregate counts", e);
      return Map.of();
    }
  }

  private AggregateCounts loadBoardTotalAggregateCounts(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = effectiveProjectId(queryFilters);
    queryFilters.put("projectId", String.valueOf(projectId));
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        defectCausePhasePredicate(filterGroup);
    try {
      List<AggregateCounts> results =
          issueFactQueryService.query(
              buildBoardTotalAggregateSql(),
              queryFilters,
              phasePredicate.sql(),
              phasePredicate.args(),
              "",
              (rs, rowNum) -> mapAggregateCountsWithoutModule(rs));
      return results.isEmpty() ? AggregateCounts.empty() : results.get(0);
    } catch (DataAccessException e) {
      log.warn("Failed to load system test defect cause total aggregate counts", e);
      return AggregateCounts.empty();
    }
  }

  private String buildBoardAggregateSql() {
    StringBuilder sql = new StringBuilder(
        """
        select btrim(modules.module_name) as module_name
        """);
    for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
      String matched = metricMatchedSql(metric);
      sql.append(", sum(case when ").append(matched).append(" then 1 else 0 end) as ").append(sqlIdentifier(metric.key()));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL1' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level1"));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL2' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level2"));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL3' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level3"));
      sql.append(", sum(case when ").append(matched)
          .append(" and (severity_level = 'SUGGESTION' or category like '%建议%') then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_suggestion"));
    }
    sql.append(
        """
          from issue_fact
          cross join lateral regexp_split_to_table(coalesce(module_names,''), ',') as modules(module_name)
         where deleted = false
           and coalesce(is_excluded,false) = false
           and coalesce(module_names,'') <> ''
        """)
        .append("   and ").append(CAUSE_TEXT_SQL).append(" <> ''\n");
    return sql.toString();
  }

  private SystemTestPhaseMembershipPolicy.SqlPredicate defectCausePhasePredicate(
      StatisticFilterGroup filterGroup) {
    return SystemTestPhaseMembershipPolicy.sqlPredicate(
        filterGroup,
        phaseScopeResolver,
        SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER);
  }

  private SystemTestPhaseMembershipPolicy.SqlPredicate moduleRowPhasePredicate(
      StatisticFilterGroup filterGroup) {
    return SystemTestPhaseMembershipPolicy.sqlPredicate(
        filterGroup,
        phaseScopeResolver,
        SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER);
  }

  private String buildBoardTotalAggregateSql() {
    StringBuilder sql = new StringBuilder("select 1 as total_marker");
    for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
      String matched = metricMatchedSql(metric);
      sql.append(", sum(case when ").append(matched).append(" then 1 else 0 end) as ").append(sqlIdentifier(metric.key()));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL1' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level1"));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL2' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level2"));
      sql.append(", sum(case when ").append(matched).append(" and severity_level = 'LEVEL3' then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_level3"));
      sql.append(", sum(case when ").append(matched)
          .append(" and (severity_level = 'SUGGESTION' or category like '%建议%') then 1 else 0 end) as ")
          .append(sqlIdentifier(metric.key() + "_suggestion"));
    }
    sql.append(
        """
          from issue_fact
         where deleted = false
           and coalesce(is_excluded,false) = false
        """)
        .append("   and ").append(CAUSE_TEXT_SQL).append(" <> ''\n");
    return sql.toString();
  }

  private String metricMatchedSql(DefectCauseMetricCatalog.Metric metric) {
    List<String> reasonMatches = new ArrayList<>();
    for (String token : metric.tokens()) {
      String literal = sqlLiteral(token);
      reasonMatches.add(CAUSE_TEXT_SQL + " like '%" + literal + "%'");
    }
    return "(" + CAUSE_TEXT_SQL + " <> '' and (" + String.join(" or ", reasonMatches) + "))";
  }

  private String sqlLiteral(String value) {
    return value == null ? "" : value.replace("'", "''");
  }

  private String sqlIdentifier(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private Map.Entry<String, AggregateCounts> mapAggregateCounts(ResultSet rs, int rowNum) throws SQLException {
    return Map.entry(
        StatisticSourceValueSupport.text(rs.getString("module_name"), ""),
        mapAggregateCountsWithoutModule(rs));
  }

  private AggregateCounts mapAggregateCountsWithoutModule(ResultSet rs) throws SQLException {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
      counts.put(metric.key(), rs.getLong(metric.key()));
      counts.put(metric.key() + "_level1", rs.getLong(metric.key() + "_level1"));
      counts.put(metric.key() + "_level2", rs.getLong(metric.key() + "_level2"));
      counts.put(metric.key() + "_level3", rs.getLong(metric.key() + "_level3"));
      counts.put(metric.key() + "_suggestion", rs.getLong(metric.key() + "_suggestion"));
    }
    return new AggregateCounts(counts);
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        rs.getLong("id"),
        rs.getInt("iid"),
        StatisticSourceValueSupport.text(rs.getString("source_instance"), "default"),
        StatisticSourceValueSupport.text(rs.getString("title"), ""),
        rs.getLong("project_id"),
        StatisticSourceValueSupport.text(rs.getString("project_name"), "未命名项目"),
        StatisticSourceValueSupport.text(rs.getString("author_name"), ""),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at")),
        StatisticSourceValueSupport.text(rs.getString("issue_state"), "opened"),
        StatisticSourceValueSupport.text(rs.getString("testing_phase"), ""),
        StatisticSourceValueSupport.text(rs.getString("system_test_label"), ""),
        StatisticSourceValueSupport.text(rs.getString("severity_level"), ""),
        StatisticSourceValueSupport.text(rs.getString("category"), ""),
        rs.getBoolean("is_excluded"),
        StatisticSourceValueSupport.text(rs.getString("reason_category"), ""),
        StatisticSourceValueSupport.text(rs.getString("reason_text"), ""),
        StatisticSourceValueSupport.split(rs.getString("module_names")),
        StatisticSourceValueSupport.split(rs.getString("label_names")));
  }

  private List<StatisticFilterOption> loadPhaseOptions() {
    try {
      return phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID).stream()
          .map(value -> new StatisticFilterOption(value, value))
          .toList();
    } catch (DataAccessException e) {
      log.debug("Failed to load phase options for {}", BOARD_KEY, e);
      return List.of();
    }
  }

  private StatisticFilterGroup applyDefaultTestingPhase(
      StatisticFilterGroup filterGroup,
      List<StatisticFilterOption> phaseOptions) {
    if (StringUtils.hasText(
        SystemTestPhaseMembershipPolicy.selectedTestingPhase(filterGroup))) {
      return filterGroup;
    }
    String defaultPhase = defaultTestingPhase(phaseOptions);
    if (!StringUtils.hasText(defaultPhase)) {
      return filterGroup == null ? emptyFilterGroup() : filterGroup;
    }
    List<StatisticFilterCondition> conditions = new ArrayList<>();
    if (filterGroup != null && filterGroup.conditions() != null) {
      conditions.addAll(filterGroup.conditions());
    }
    conditions.add(new StatisticFilterCondition("testingPhase", "eq", defaultPhase, null));
    return new StatisticFilterGroup("AND", conditions);
  }

  private String defaultTestingPhase(List<StatisticFilterOption> phaseOptions) {
    if (phaseOptions == null || phaseOptions.isEmpty()) {
      return "";
    }
    return phaseOptions.stream()
        .map(StatisticFilterOption::value)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("");
  }

  private Map<String, String> appliedFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    Map<String, String> applied = new LinkedHashMap<>(withoutReservedFilters(filters));
    String selectedTestingPhase =
        SystemTestPhaseMembershipPolicy.selectedTestingPhase(effectiveFilterGroup);
    if (StringUtils.hasText(selectedTestingPhase)) {
      applied.put("testingPhase", selectedTestingPhase);
    }
    return applied;
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition,
      String selectedTestingPhase) {
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    payload.put("testingPhase", StringUtils.hasText(selectedTestingPhase) ? selectedTestingPhase : "");
    return new StatisticBoardSnapshotService.SnapshotRequest(
        BOARD_KEY,
        "project=" + SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID
            + ";testingPhase=" + (StringUtils.hasText(selectedTestingPhase) ? selectedTestingPhase : "none"),
        RULE_VERSION,
        snapshotService.issueFactSourceVersion(
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
            com.data.collection.platform.service.IssueScopeDimension.TESTING_PHASE,
            selectedTestingPhase),
        payload,
        definition,
        effectiveFilterGroup);
  }

  private String displayPhaseLabel(String phaseKey, String selectedTestingPhase) {
    String normalized = trimToNull(phaseKey);
    if (normalized == null) {
      return "未识别阶段";
    }
    if (StringUtils.hasText(selectedTestingPhase) && normalized.startsWith(selectedTestingPhase.trim())) {
      Matcher matcher = TURN_LABEL_PATTERN.matcher(normalized);
      if (matcher.find()) {
        return matcher.group(1);
      }
      String suffix = trimToNull(normalized.substring(selectedTestingPhase.trim().length()));
      if (suffix != null) {
        return suffix;
      }
    }
    return normalized;
  }

  private boolean isPhaseScopedValue(String value) {
    return StringUtils.hasText(value)
        && (value.contains("系统测试") || value.contains("回归测试"));
  }

  private String phaseFilterValue(String phaseLabel) {
    String normalized = trimToNull(phaseLabel);
    if (normalized == null) {
      return "";
    }
    Matcher matcher = TURN_LABEL_PATTERN.matcher(normalized);
    if (matcher.find()) {
      String base = trimToNull(normalized.replace(matcher.group(1), ""));
      if (base != null) {
        return base;
      }
    }
    return normalized;
  }

  private DefectCauseMetricCatalog.Metric metric(String key) {
    return DefectCauseMetricCatalog.get(key);
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private record AggregateBucket(String rowLabel, String rowKey, List<AggregateCounts> counts) {
    AggregateBucket(String rowLabel) {
      this(rowLabel, rowLabel, new ArrayList<>());
    }

    AggregateBucket(String rowLabel, String rowKey) {
      this(rowLabel, rowKey, new ArrayList<>());
    }

    AggregateBucket acceptAll(List<AggregateCounts> sourceCounts) {
      counts.addAll(sourceCounts);
      return this;
    }

    void accept(AggregateCounts count) {
      counts.add(count);
    }

    StatisticRowData toRowData() {
      List<StatisticCellData> cells = new ArrayList<>();
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        cells.add(cell(metric.key(), countByMetric(metric.key()), true));
      }
      return new StatisticRowData(
          rowKey,
          rowLabel,
          cells);
    }

    private long countByMetric(String metricKey) {
      return counts.stream().mapToLong(count -> count.value(metricKey)).sum();
    }

    private long metricTotal() {
      return CAUSE_METRICS.stream().mapToLong(metric -> countByMetric(metric.key())).sum();
    }

    static StatisticRowData ratioRow(AggregateBucket totalBucket) {
      long denominator = totalBucket.metricTotal();
      List<StatisticCellData> cells = new ArrayList<>();
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        long numerator = totalBucket.countByMetric(metric.key());
        String display = denominator == 0 ? "0" : String.format(java.util.Locale.ROOT, "%.2f%%", numerator * 100.0 / denominator);
        cells.add(new StatisticCellData(
            metric.key(),
            StatisticMetricCalculator.ratioSortValue(numerator, denominator),
            display,
            false,
            null,
            Map.of("rowKey", "__ratio__")));
      }
      return new StatisticRowData("__ratio__", "比例", cells);
    }

    private StatisticCellData cell(String key, long numericValue, boolean drilldown) {
      Map<String, String> detailParams = new LinkedHashMap<>();
      detailParams.put("rowKey", rowKey);
      detailParams.put("level1", count(countByMetricAndSeverity(key, "LEVEL1")));
      detailParams.put("level2", count(countByMetricAndSeverity(key, "LEVEL2")));
      detailParams.put("level3", count(countByMetricAndSeverity(key, "LEVEL3")));
      detailParams.put("suggestion", count(countByMetricAndSeverity(key, "SUGGESTION")));
      return new StatisticCellData(
          key,
          numericValue,
          count(numericValue),
          drilldown && numericValue > 0,
          drilldown && numericValue > 0 ? "issue-list" : null,
          detailParams);
    }

    private long countByMetricAndSeverity(String metricKey, String severity) {
      return counts.stream()
          .mapToLong(count -> count.value(metricKey + "_" + severity.toLowerCase(java.util.Locale.ROOT)))
          .sum();
    }
  }

  private record AggregateCounts(Map<String, Long> values) {
    static AggregateCounts empty() {
      Map<String, Long> emptyValues = new LinkedHashMap<>();
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        emptyValues.put(metric.key(), 0L);
        emptyValues.put(metric.key() + "_level1", 0L);
        emptyValues.put(metric.key() + "_level2", 0L);
        emptyValues.put(metric.key() + "_level3", 0L);
        emptyValues.put(metric.key() + "_suggestion", 0L);
      }
      return new AggregateCounts(emptyValues);
    }

    long value(String key) {
      return values.getOrDefault(key, 0L);
    }

    AggregateCounts plus(AggregateCounts other) {
      Map<String, Long> merged = new LinkedHashMap<>(values);
      other.values.forEach((key, value) -> merged.merge(key, value, Long::sum));
      return new AggregateCounts(merged);
    }
  }

  private record IssueSource(
      Long id,
      Integer iid,
      String sourceInstance,
      String title,
      Long projectId,
      String projectName,
      String authorName,
      LocalDateTime updatedAt,
      LocalDateTime closedAt,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String category,
      boolean excluded,
      String reasonCategory,
      String reasonText,
      List<String> moduleNames,
      List<String> labels) implements SystemTestPhaseFilterSource {
    boolean inSystemTestScope() {
      return StringUtils.hasText(primaryPhaseLabel());
    }

    boolean hasDefectCause() {
      return !matchedMetricKeys().isEmpty();
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean matchesMetric(String metricKey) {
      if (!StringUtils.hasText(metricKey) || "__ratio__".equals(metricKey)) {
        return true;
      }
      if ("total".equals(metricKey)) {
        return hasDefectCause();
      }
      return matchedMetricKeys().contains(metricKey);
    }

    boolean isSeverity(String severity) {
      return severity.equalsIgnoreCase(severityLevel);
    }

    boolean isSuggestion() {
      return isSeverity("SUGGESTION") || contains(category, "建议");
    }

    List<String> causeLabels() {
      return CAUSE_METRICS.stream()
          .filter(metric -> matchedMetricKeys().contains(metric.key()))
          .map(DefectCauseMetricCatalog.Metric::label)
          .toList();
    }

    private Set<String> matchedMetricKeys() {
      Set<String> matched = new LinkedHashSet<>();
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        if (DefectCauseMetricCatalog.containsAny(reasonCategory, metric.tokens())) {
          matched.add(metric.key());
        }
      }
      return matched;
    }

    String primaryPhaseLabel() {
      if (hasScope(testingPhase)) {
        return testingPhase;
      }
      if (hasScope(systemTestLabel)) {
        return systemTestLabel;
      }
      return labels.stream().filter(this::hasScope).findFirst().orElse("");
    }

    public String phaseFilterValue() {
      String primary = primaryPhaseLabel();
      String normalized = StringUtils.hasText(primary) ? primary : "";
      Matcher matcher = TURN_LABEL_PATTERN.matcher(normalized);
      if (matcher.find()) {
        String base = normalized.replace(matcher.group(1), "").trim();
        if (!base.isEmpty()) {
          return base;
        }
      }
      return normalized;
    }

    public String phaseLabel() {
      return primaryPhaseLabel();
    }

    private boolean hasScope(String value) {
      return StringUtils.hasText(value)
          && (value.contains("系统测试") || value.contains("回归测试"));
    }
  }

  private record PhaseOptionSource(String testingPhase, String systemTestLabel, List<String> labels) {
    List<String> candidates() {
      List<String> values = new ArrayList<>();
      if (StringUtils.hasText(testingPhase)) {
        values.add(testingPhase);
      }
      if (StringUtils.hasText(systemTestLabel)) {
        values.add(systemTestLabel);
      }
      values.addAll(labels);
      return values;
    }
  }

  private record RuleFlowSnapshot(
      List<IssueSource> scopedSources,
      List<IssueSource> reasonSources,
      List<StatisticRuleFlowStep> flowSteps) {}

  private static boolean contains(String text, String keyword) {
    return StringUtils.hasText(text) && StringUtils.hasText(keyword) && text.contains(keyword);
  }

}
