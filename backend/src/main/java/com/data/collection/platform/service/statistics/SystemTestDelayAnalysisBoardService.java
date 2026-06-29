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
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class SystemTestDelayAnalysisBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "system-test-delay-analysis";
  private static final String RULE_VERSION = "system-test-delay-analysis@2026-04-22-v1";
  private static final String TESTING_PHASE_FIELD = "testingPhase";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> LEGACY_DELAY_CAUSES =
      List.of("技术卡点", "方案卡点", "资源卡点", "数据异常", "算法问题", "机制问题", "计算效率");
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "labels", "notes");
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
             coalesce(author_name,'') as author_name, coalesce(assignee_name,'') as assignee_name,
             created_at_source as created_at, updated_at_source as updated_at,
             closed_at_source as closed_at, coalesce(issue_state,'opened') as issue_state,
             coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(severity_level,'') as severity_level,
             coalesce(bug_status,'') as bug_status,
             coalesce(category,'') as category,
             coalesce(delay_cause,'') as delay_cause,
             coalesce(is_excluded,false) as is_excluded,
             coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names
        from issue_fact
       where deleted = false
      """;
  private static final String BOARD_AGGREGATE_SQL = """
      select delay_cause as row_key,
             sum(case when severity_level = 'LEVEL1' then 1 else 0 end) as level1,
             sum(case when severity_level = 'LEVEL2' then 1 else 0 end) as level2,
             sum(case when severity_level = 'LEVEL3' then 1 else 0 end) as level3,
             sum(case when severity_level = 'SUGGESTION' or category like '%建议%' then 1 else 0 end) as suggestion
        from issue_fact
       where deleted = false
         and coalesce(is_excluded,false) = false
         and delay_cause in ('技术卡点','方案卡点','资源卡点','数据异常','算法问题','机制问题','计算效率')
      """;
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.systemTest(
          "议题标题",
          "模块名",
          List.of(
              StatisticIssueDetailColumns.state("议题状态"),
              StatisticIssueDetailColumns.severity("severityLevel", "严重程度", 120),
              StatisticIssueDetailColumns.bugStatus(),
              StatisticIssueDetailColumns.delayCause()),
          List.of(
              StatisticIssueDetailColumns.author("议题提交人"),
              StatisticIssueDetailColumns.assignee("议题处理人", 160)),
          List.of(StatisticIssueDetailColumns.createdAt()));

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final IssueFactQueryService issueFactQueryService;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final StatisticBoardSnapshotService snapshotService;

  public SystemTestDelayAnalysisBoardService(
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
        "申请延期缺陷分析",
        "基于 issue_fact 的延期原因维度缺陷分析。",
        "",
        "",
        "延期原因",
        List.of(StatisticFilterFieldFactory.select("testingPhase", "测试阶段", 220, phaseOptions)),
        List.of(
            new StatisticColumnGroup(
                "delay-summary",
                "延期原因统计",
                List.of(
                    leaf("level1", "一级缺陷(个)", true, "count"),
                    leaf("level2", "二级缺陷(个)", true, "count"),
                    leaf("level3", "三级缺陷(个)", true, "count"),
                    leaf("suggestion", "建议类缺陷(个)", true, "count"),
                    leaf("total", "总计(个)", true, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的申请延期缺陷分析结果。");
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup effectiveFilterGroup = applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);
    String selectedTestingPhase = SystemTestPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
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
    Map<String, AggregateCounts> aggregateCounts = loadBoardAggregateCounts(filters, effectiveFilterGroup);

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (String delayCause : LEGACY_DELAY_CAUSES) {
      buckets.put(delayCause, new AggregateBucket(delayCause));
    }
    for (Map.Entry<String, AggregateCounts> entry : aggregateCounts.entrySet()) {
      AggregateBucket bucket = buckets.get(entry.getKey());
      if (bucket != null) {
        bucket.accept(entry.getValue());
      }
    }

    List<StatisticRowData> rows =
        buckets.values().stream()
            .map(AggregateBucket::toRowData)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

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
    return new StatisticBoardResponse(
        definition,
        appliedFilters(filters, effectiveFilterGroup),
        effectiveFilterGroup,
        rows,
        meta);
  }

  @Override
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);
    for (StatisticFilterOption option : phaseOptions) {
      Map<String, String> filters = Map.of(TESTING_PHASE_FIELD, option.value());
      StatisticFilterGroup filterGroup =
          SystemTestPhaseFilterGroupExpander.expand(
              new StatisticFilterGroup(
                  "AND",
                  List.of(new StatisticFilterCondition(TESTING_PHASE_FIELD, "eq", option.value(), null))),
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
        buildRuleFlowSnapshot(loadSources(request.filters(), effectiveFilterGroup), effectiveFilterGroup).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "申请延期缺陷明细",
        "展示当前延期原因与指标命中的 issue_fact 明细，字段按老平台通用议题详情口径展示。",
        DETAIL_COLUMNS,
        pageSlice.records().stream().map(this::toDetailRecord).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending");
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return realtimeWorkspaceService.getStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return realtimeWorkspaceService.requestRefreshWithResult(BOARD_KEY, this::refreshMirrorForRealtimeView);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(phaseOptions));
    StatisticFilterGroup effectiveFilterGroup = applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    long causeCount =
        LEGACY_DELAY_CAUSES.stream()
            .filter(cause -> snapshot.finalSources().stream().anyMatch(issue -> cause.equals(issue.delayCause())))
            .count();
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "申请延期缺陷分析规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact 的归一化事实字段，先限定系统测试/回归测试范围，再按老平台 DelayEnum 固定延期原因统计。",
        "同一条议题只会归入一个老平台固定延期原因；没有命中数据的延期原因仍显示为 0。",
        List.of(
            snapshot.flowSteps().get(0),
            snapshot.flowSteps().get(1),
            snapshot.flowSteps().get(2),
            snapshot.flowSteps().get(3),
            StatisticRuleFlowSupport.step(
                "group-by-delay-cause",
                "按延期原因聚合",
                "将延期议题按老平台固定延期原因聚合，再统计一级、二级、三级和建议类缺陷数量。",
                snapshot.finalSources().size(),
                causeCount,
                snapshot.finalSources(),
                this::toRuleFlowSample
            )),
        List.of(
            new StatisticRuleMetricDefinition("level1", "一级缺陷", "按 issue_fact.severity_level = LEVEL1 统计。", "一级缺陷数 = 当前延期原因下 LEVEL1 议题数", null),
            new StatisticRuleMetricDefinition("level2", "二级缺陷", "按 issue_fact.severity_level = LEVEL2 统计。", "二级缺陷数 = 当前延期原因下 LEVEL2 议题数", null),
            new StatisticRuleMetricDefinition("level3", "三级缺陷", "按 issue_fact.severity_level = LEVEL3 统计。", "三级缺陷数 = 当前延期原因下 LEVEL3 议题数", null),
            new StatisticRuleMetricDefinition("suggestion", "建议类缺陷", "按老平台 category like 建议 口径统计，兼容 severity_level = SUGGESTION。", "建议类缺陷数 = 当前延期原因下 category 含建议或 SUGGESTION 的议题数", null),
            new StatisticRuleMetricDefinition("total", "总计", "统计当前延期原因下一级、二级、三级和建议类缺陷。", "总计 = 一级缺陷 + 二级缺陷 + 三级缺陷 + 建议类缺陷", null)),
        null);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial.stream().filter(IssueSource::inSystemTestScope).toList();
    List<IssueSource> delayed =
        scoped.stream()
            .filter(issue -> !issue.excluded())
            .filter(IssueSource::hasLegacyDelayCause)
            .toList();
    List<IssueSource> filtered =
        delayed.stream().filter(issue -> SystemTestPhaseFilterSupport.matches(issue, filterGroup, phaseScopeResolver)).toList();
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
                "限定系统测试范围",
                "只保留带有系统测试或回归测试标签的议题。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "delay-cause-filter",
                "保留延期议题",
                "只保留 issue_fact.delay_cause 命中老平台固定延期原因且未被排除的延期议题。",
                scoped.size(),
                delayed,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "phase-filter",
                "应用测试阶段筛选",
                "根据页面上的测试阶段筛选进一步收敛范围；未选择时按老平台默认使用阶段列表第一项。",
                delayed.size(),
                filtered,
                this::toRuleFlowSample
            )));
  }

  private StatisticFilterGroup applyDefaultTestingPhase(
      StatisticFilterGroup filterGroup,
      List<StatisticFilterOption> phaseOptions) {
    if (StringUtils.hasText(SystemTestPhaseFilterSupport.selectedTestingPhase(filterGroup))) {
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
    conditions.add(new StatisticFilterCondition(TESTING_PHASE_FIELD, "eq", defaultPhase, null));
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
    String selectedTestingPhase = SystemTestPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    if (StringUtils.hasText(selectedTestingPhase)) {
      applied.put(TESTING_PHASE_FIELD, selectedTestingPhase);
    }
    return applied;
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition,
      String selectedTestingPhase) {
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    payload.put(
        TESTING_PHASE_FIELD,
        StringUtils.hasText(selectedTestingPhase) ? selectedTestingPhase : "");
    return new StatisticBoardSnapshotService.SnapshotRequest(
        BOARD_KEY,
        "project=" + SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID
            + ";testingPhase=" + (StringUtils.hasText(selectedTestingPhase) ? selectedTestingPhase : "none"),
        RULE_VERSION,
        snapshotService.issueFactSourceVersion(),
        payload,
        definition,
        effectiveFilterGroup);
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 延期原因: " + issue.delayCause() + " | 严重程度: " + issue.displaySeverityLevel());
  }
  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey)
        || rowKey.equals(issue.delayCause());
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "level1" -> IssueSource::isLevel1;
      case "level2" -> IssueSource::isLevel2;
      case "level3" -> IssueSource::isLevel3;
      case "suggestion" -> IssueSource::isSuggestion;
      case "total" -> IssueSource::isCountableByLegacyTotal;
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "testingPhase" -> SortSupport.nullableString(IssueSource::primaryPhaseLabel);
          case "delayCause" -> SortSupport.nullableString(IssueSource::delayCause);
          case "severityLevel" -> SortSupport.nullableString(IssueSource::displaySeverityLevel);
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.moduleNames()));
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueSource::assigneeName);
          case "state" -> SortSupport.nullableComparable(issue -> issue.isClosed() ? 1 : 0);
          case "createdAt" -> SortSupport.nullableComparable(IssueSource::createdAt);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName(), issue.id(), issue.labels());
    record.put("moduleNames", String.join("、", issue.moduleNames()));
    record.put("title", issue.title());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("severityLevel", issue.displaySeverityLevel());
    record.put("bugStatus", issue.bugStatus());
    record.put("delayCause", issue.delayCause());
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    return record;
  }

  private List<IssueSource> loadSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = effectiveProjectId(queryFilters);
    SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate =
        SystemTestPhaseSqlPredicateSupport.legacyStatisticPhasePredicate(filterGroup, phaseScopeResolver);
    try {
      List<IssueSource> facts = ensureFactsReady(projectId, queryFilters, phasePredicate);
      return facts.isEmpty() ? List.of() : facts;
    } catch (DataAccessException e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult refreshMirrorForRealtimeView() {
    return realtimeIncrementalRefreshService.requestIncrementalRefresh(BOARD_KEY, REALTIME_REFRESH_TABLES);
  }

  private List<IssueSource> ensureFactsReady(Long projectId, Map<String, String> filters, SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate) {
    List<IssueSource> facts = loadSourcesFromFact(projectId, filters, phasePredicate);
    if (!facts.isEmpty()) {
      return facts;
    }
    log.info("System test delay analysis board returned empty result without triggering synchronous rebuild");
    return List.of();
  }

  private List<IssueSource> loadSourcesFromFact(Long projectId, Map<String, String> filters, SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate) {
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

  private Map<String, AggregateCounts> loadBoardAggregateCounts(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove(TESTING_PHASE_FIELD);
    Long projectId = effectiveProjectId(queryFilters);
    queryFilters.put("projectId", String.valueOf(projectId));
    SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate =
        SystemTestPhaseSqlPredicateSupport.legacyStatisticPhasePredicate(filterGroup, phaseScopeResolver);
    try {
      return issueFactQueryService.query(
              BOARD_AGGREGATE_SQL,
              queryFilters,
              phasePredicate.sql(),
              phasePredicate.args(),
              "group by delay_cause",
              (rs, rowNum) ->
                  Map.entry(
                      StatisticSourceValueSupport.text(rs.getString("row_key"), ""),
                      new AggregateCounts(
                          rs.getLong("level1"),
                          rs.getLong("level2"),
                          rs.getLong("level3"),
                          rs.getLong("suggestion"))))
          .stream()
          .filter(entry -> LEGACY_DELAY_CAUSES.contains(entry.getKey()))
          .collect(
              java.util.stream.Collectors.toMap(
                  Map.Entry::getKey,
                  Map.Entry::getValue,
                  AggregateCounts::plus,
                  LinkedHashMap::new));
    } catch (DataAccessException e) {
      log.warn("Failed to load system test delay analysis aggregate counts", e);
      return Map.of();
    }
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
        StatisticSourceValueSupport.text(rs.getString("assignee_name"), ""),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at")),
        StatisticSourceValueSupport.text(rs.getString("issue_state"), "opened"),
        StatisticSourceValueSupport.text(rs.getString("testing_phase"), ""),
        StatisticSourceValueSupport.text(rs.getString("system_test_label"), ""),
        StatisticSourceValueSupport.text(rs.getString("severity_level"), ""),
        StatisticSourceValueSupport.text(rs.getString("bug_status"), ""),
        StatisticSourceValueSupport.text(rs.getString("category"), ""),
        StatisticSourceValueSupport.text(rs.getString("delay_cause"), ""),
        rs.getBoolean("is_excluded"),
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

  private boolean isPhaseScopedValue(String value) {
    return StringUtils.hasText(value)
        && (value.contains("系统测试") || value.contains("回归测试"));
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

    void accept(AggregateCounts count) {
      counts.add(count);
    }

    StatisticRowData toRowData() {
      long level1 = counts.stream().mapToLong(AggregateCounts::level1).sum();
      long level2 = counts.stream().mapToLong(AggregateCounts::level2).sum();
      long level3 = counts.stream().mapToLong(AggregateCounts::level3).sum();
      long suggestion = counts.stream().mapToLong(AggregateCounts::suggestion).sum();
      long total = level1 + level2 + level3 + suggestion;
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              cell("level1", level1, true),
              cell("level2", level2, true),
              cell("level3", level3, true),
              cell("suggestion", suggestion, true),
              cell("total", total, true)));
    }

    private StatisticCellData cell(String key, long numericValue, boolean drilldown) {
      return new StatisticCellData(
          key,
          numericValue,
          count(numericValue),
          drilldown,
          drilldown ? "issue-list" : null,
          Map.of("rowKey", rowKey));
    }
  }

  private record AggregateCounts(long level1, long level2, long level3, long suggestion) {
    AggregateCounts plus(AggregateCounts other) {
      return new AggregateCounts(
          level1 + other.level1,
          level2 + other.level2,
          level3 + other.level3,
          suggestion + other.suggestion);
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
      String assigneeName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String bugStatus,
      String category,
      String delayCause,
      boolean excluded,
      List<String> moduleNames,
      List<String> labels) implements SystemTestPhaseFilterSource {
    boolean inSystemTestScope() {
      return StringUtils.hasText(primaryPhaseLabel());
    }

    boolean hasLegacyDelayCause() {
      return LEGACY_DELAY_CAUSES.contains(delayCause);
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isLevel1() {
      return "LEVEL1".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel2() {
      return "LEVEL2".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel3() {
      return "LEVEL3".equalsIgnoreCase(severityLevel);
    }

    boolean isSuggestion() {
      return "SUGGESTION".equalsIgnoreCase(severityLevel) || contains(category, "建议");
    }

    boolean isCountableByLegacyTotal() {
      return isLevel1() || isLevel2() || isLevel3() || isSuggestion();
    }

    String displaySeverityLevel() {
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
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

    private boolean contains(String value, String token) {
      return StringUtils.hasText(value) && value.contains(token);
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

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
