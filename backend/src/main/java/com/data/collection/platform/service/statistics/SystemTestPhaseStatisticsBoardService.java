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
import com.data.collection.platform.service.IssueDisplayValueSupport;
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
public class SystemTestPhaseStatisticsBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "system-test-phase-statistics";
  private static final String RULE_VERSION = "system-test-phase-statistics@2026-07-09-v5";
  private static final String TESTING_PHASE_FIELD = "testingPhase";
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private static final Pattern TURN_LABEL_PATTERN =
      Pattern.compile("(第[一二三四五六七八九十0-9]+轮系统测试|回归测试|系统测试)");
  private static final String FACT_SQL = """
      select issue_id as id, issue_iid as iid, source_instance, title, project_id, project_name,
             coalesce(author_name,'') as author_name, coalesce(assignee_name,'') as assignee_name,
             created_at_source as created_at,
             updated_at_source as updated_at, closed_at_source as closed_at,
             coalesce(issue_state,'opened') as issue_state, coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label, coalesce(severity_level,'') as severity_level,
             coalesce(severity_alias,'') as severity_alias,
             coalesce(priority_level,'') as priority_level, coalesce(bug_status,'') as bug_status,
             coalesce(category,'') as category, coalesce(delay_cause,'') as delay_cause,
             coalesce(is_excluded,false) as is_excluded,
             coalesce(exclusion_reason,'') as exclusion_reason, coalesce(is_fixed,false) as is_fixed,
             coalesce(delay_issue,false) as delay_issue, coalesce(is_regression,false) as is_regression,
             coalesce(is_crash,false) as is_crash, coalesce(is_level1_other,false) as is_level1_other,
             coalesce(is_illegal,false) as is_illegal, coalesce(illegal_reason,'') as illegal_reason,
             coalesce(is_legacy,false) as is_legacy, coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names
        from issue_fact
       where deleted = false
      """;
  private static final String BOARD_AGGREGATE_SQL = """
      select testing_phase as row_key,
             sum(case when %s and coalesce(severity_alias,'') like '%%一级缺陷%%' then 1 else 0 end) as level1,
             sum(case when %s and coalesce(severity_alias,'') like '%%二级缺陷%%' then 1 else 0 end) as level2,
             sum(case when %s and coalesce(severity_alias,'') like '%%三级缺陷%%' then 1 else 0 end) as level3,
             sum(case when %s then 1 else 0 end) as suggestion
        from issue_fact
       where deleted = false
         and coalesce(testing_phase,'') <> ''
         and (((%s) and (coalesce(severity_alias,'') like '%%一级缺陷%%'
              or coalesce(severity_alias,'') like '%%二级缺陷%%'
              or coalesce(severity_alias,'') like '%%三级缺陷%%'))
              or (%s))
      """.formatted(
          SystemTestSuggestionMetricSupport.regularMetricSql("issue_fact"),
          SystemTestSuggestionMetricSupport.regularMetricSql("issue_fact"),
          SystemTestSuggestionMetricSupport.regularMetricSql("issue_fact"),
          SystemTestSuggestionMetricSupport.suggestionMetricSql("issue_fact"),
          SystemTestSuggestionMetricSupport.regularMetricSql("issue_fact"),
          SystemTestSuggestionMetricSupport.suggestionMetricSql("issue_fact"));
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
              StatisticIssueDetailColumns.assignee("议题处理人", 140)),
          List.of(StatisticIssueDetailColumns.createdAt()));

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final IssueFactQueryService issueFactQueryService;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final StatisticBoardSnapshotService snapshotService;

  public SystemTestPhaseStatisticsBoardService(
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
    return buildDefinition(loadPhaseOptions(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID));
  }

  private StatisticBoardDefinition buildDefinition(List<StatisticFilterOption> phaseOptions) {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "议题阶段统计",
        "按系统测试轮次统计各严重程度缺陷数量。",
        "",
        "",
        "轮次",
        List.of(StatisticFilterFieldFactory.select("testingPhase", "项目", 220, phaseOptions)),
        List.of(
            new StatisticColumnGroup(
                "phase-summary",
                "阶段统计",
                List.of(
                    leaf("level1", "一级缺陷(个)", true, "count"),
                    leaf("level2", "二级缺陷(个)", true, "count"),
                    leaf("level3", "三级缺陷(个)", true, "count"),
                    leaf("suggestion", "建议类缺陷(个)", true, "count"),
                    leaf("total", "总计(排除建议类)(个)", false, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的议题阶段统计结果。");
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    long projectId = effectiveProjectId(filters);
    List<PhaseDefinition> phaseDefinitions = loadPhaseDefinitions(projectId);
    List<StatisticFilterOption> phaseOptions = phaseOptionsFromDefinitions(phaseDefinitions);
    StatisticFilterGroup effectiveFilterGroup = applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    String selectedTestingPhase = SystemTestPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);
    StatisticFilterGroup appliedGroup = effectiveFilterGroup;
    return snapshotService.readOrRefresh(
        snapshotRequest(filters, appliedGroup, definition, selectedTestingPhase),
        () -> buildBoardResponse(filters, appliedGroup, definition, phaseDefinitions, selectedTestingPhase));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition,
      List<PhaseDefinition> phaseDefinitions,
      String selectedTestingPhase) {
    long startedAt = System.currentTimeMillis();
    Map<String, AggregateCounts> aggregateCounts = loadBoardAggregateCounts(filters, effectiveFilterGroup);

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (PhaseDefinition phaseDefinition : phaseDefinitionsForSelectedPhase(phaseDefinitions, selectedTestingPhase)) {
      buckets.putIfAbsent(
          phaseDefinition.testingPhase(),
          new AggregateBucket(
              phaseDefinition.testingPhase(),
              phaseDefinition.testingPhase()));
    }
    for (Map.Entry<String, AggregateCounts> entry : aggregateCounts.entrySet()) {
      AggregateBucket bucket = buckets.get(entry.getKey());
      if (bucket != null) {
        bucket.accept(entry.getValue());
      }
    }

    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(Comparator.comparingInt((AggregateBucket bucket) -> turnOrder(bucket.rowLabel()))
                .thenComparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
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
    return new StatisticBoardResponse(definition, appliedFilters(filters, effectiveFilterGroup), effectiveFilterGroup, rows, meta);
  }

  @Override
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    long projectId = SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID;
    List<PhaseDefinition> phaseDefinitions = loadPhaseDefinitions(projectId);
    List<StatisticFilterOption> phaseOptions = phaseOptionsFromDefinitions(phaseDefinitions);
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
          buildBoardResponse(filters, filterGroup, definition, phaseDefinitions, option.value()));
    }
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    long projectId = effectiveProjectId(request.filters());
    List<PhaseDefinition> phaseDefinitions = loadPhaseDefinitions(projectId);
    List<StatisticFilterOption> phaseOptions = phaseOptionsFromDefinitions(phaseDefinitions);
    StatisticFilterGroup effectiveFilterGroup = applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters(), effectiveFilterGroup), effectiveFilterGroup, phaseDefinitions).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse(
        "议题阶段统计明细",
        "展示当前轮次与指标命中的议题明细。",
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
    long projectId = effectiveProjectId(filters);
    List<PhaseDefinition> phaseDefinitions = loadPhaseDefinitions(projectId);
    List<StatisticFilterOption> phaseOptions = phaseOptionsFromDefinitions(phaseDefinitions);
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(phaseOptions));
    StatisticFilterGroup effectiveFilterGroup = applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup, phaseDefinitions);
    long phaseCount =
        snapshot.finalSources().stream()
            .map(IssueSource::primaryPhaseLabel)
            .filter(StringUtils::hasText)
            .distinct()
            .count();
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "议题阶段统计规则说明",
        RULE_VERSION,
        "当前统计按系统测试阶段定义中的轮次聚合，展示每个轮次下不同严重程度的缺陷数量。",
        "默认项目为老平台 CrownCAD 项目 9；本页对齐老平台 getDefectAndPhaseTable，按子轮次精确匹配 testing_phase，并按严重程度字面值统计一级、二级、三级缺陷。",
        List.of(
            snapshot.flowSteps().get(0),
            snapshot.flowSteps().get(1),
            snapshot.flowSteps().get(2),
            snapshot.flowSteps().get(3),
            StatisticRuleFlowSupport.step(
                "group-by-turn",
                "按轮次聚合",
                "按系统测试阶段定义中的轮次行聚合，并统计各严重程度数量。",
                snapshot.finalSources().size(),
                phaseCount,
                snapshot.finalSources(),
                this::toRuleFlowSample
            )),
        List.of(
            new StatisticRuleMetricDefinition("level1", "一级缺陷", "统计当前轮次内严重程度为一级缺陷的议题。", "一级缺陷数 = 当前轮次内一级缺陷议题数", null),
            new StatisticRuleMetricDefinition("level2", "二级缺陷", "统计当前轮次内严重程度为二级缺陷的议题。", "二级缺陷数 = 当前轮次内二级缺陷议题数", null),
            new StatisticRuleMetricDefinition("level3", "三级缺陷", "统计当前轮次内严重程度为三级缺陷的议题。", "三级缺陷数 = 当前轮次内三级缺陷议题数", null),
            new StatisticRuleMetricDefinition("suggestion", "建议类缺陷", "建议类缺陷只在本列单独统计，不进入一级、二级、三级和总计。", "建议类缺陷数 = 当前轮次内 category 包含“建议”的议题数", null),
            new StatisticRuleMetricDefinition("total", "总计(排除建议类)", "总计只统计一级、二级、三级缺陷，排除建议类。", "总计 = 一级缺陷 + 二级缺陷 + 三级缺陷", null)),
        null);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    if ("suggestion".equals(key)) {
      return new StatisticColumnLeaf(
          key,
          label,
          drilldown,
          metricType,
          SystemTestSuggestionMetricSupport.SUGGESTION_HEADER_TOOLTIP);
    }
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded,
      StatisticFilterGroup filterGroup,
      List<PhaseDefinition> phaseDefinitions) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream()
            .filter(IssueSource::inSystemTestScope)
            .filter(issue -> StringUtils.hasText(issue.primaryPhaseLabel()))
            .toList();
    List<IssueSource> valid = scoped.stream().filter(IssueSource::isVisibleForRegularOrSuggestionColumn).toList();
    List<IssueSource> filtered =
        valid.stream().filter(issue -> SystemTestPhaseFilterSupport.matches(issue, filterGroup, phaseScopeResolver)).toList();
    List<IssueSource> configured =
        filtered.stream()
            .filter(issue -> isConfiguredPhase(issue.primaryPhaseLabel(), phaseDefinitions))
            .filter(IssueSource::isCountableByRuleOrSuggestion)
            .toList();
    return new RuleFlowSnapshot(
        configured,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题数据",
                "加载已同步到平台的议题数据，并使用对齐老平台的测试阶段和严重程度字面值。",
                initial.size(),
                initial,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定系统测试范围",
                "只保留带有系统测试或回归测试标签，且可识别主轮次的议题。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "legacy-filter",
                "应用系统测试公共排除规则",
                "剔除功能屏蔽、已拒绝，以及关闭后属于申请否决/需求如此的议题；建议类只进入建议类缺陷列，不进入其它指标。",
                scoped.size(),
                valid,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "phase-filter",
                "应用测试阶段筛选",
                "根据页面上的“测试阶段”筛选条件进一步保留精确匹配的子轮次；未填写时按阶段定义第一项作为默认测试阶段。",
                valid.size(),
                configured,
                this::toRuleFlowSample
            )));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 轮次: " + displayPhaseLabel(issue.primaryPhaseLabel(), issue.phaseFilterValue()));
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
    long projectId = effectiveProjectId(filters);
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    payload.put("projectId", String.valueOf(projectId));
    if (StringUtils.hasText(selectedTestingPhase)) {
      payload.put(TESTING_PHASE_FIELD, selectedTestingPhase);
    }
    return new StatisticBoardSnapshotService.SnapshotRequest(
        BOARD_KEY,
        "project=" + projectId + ";testingPhase=" + (StringUtils.hasText(selectedTestingPhase) ? selectedTestingPhase : "none"),
        RULE_VERSION,
        snapshotService.issueFactSourceVersion(),
        payload,
        definition,
        effectiveFilterGroup);
  }

  private String displayPhaseLabel(String phaseKey, String selectedTestingPhase) {
    String normalized = trimToNull(phaseKey);
    if (normalized == null) {
      return "未识别轮次";
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

  private int turnOrder(String rowLabel) {
    String normalized = trimToNull(rowLabel);
    if (normalized == null) {
      return Integer.MAX_VALUE;
    }
    if (normalized.contains("第一轮")) return 1;
    if (normalized.contains("第二轮")) return 2;
    if (normalized.contains("第三轮")) return 3;
    if (normalized.contains("第四轮")) return 4;
    if (normalized.contains("第五轮")) return 5;
    if (normalized.contains("第六轮")) return 6;
    if (normalized.contains("第七轮")) return 7;
    if (normalized.contains("第八轮")) return 8;
    if (normalized.contains("第九轮")) return 9;
    if (normalized.contains("第十轮")) return 10;
    if (normalized.contains("回归测试")) return 99;
    return 500;
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey)
        || rowKey.equals(issue.primaryPhaseLabel());
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "level1" -> IssueSource::isLevel1;
      case "level2" -> IssueSource::isLevel2;
      case "level3" -> IssueSource::isLevel3;
      case "suggestion" -> IssueSource::isSuggestion;
      case "total" -> IssueSource::isCountableByRule;
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "testingPhase" -> SortSupport.nullableString(IssueSource::primaryPhaseLabel);
          case "severityLevel" -> SortSupport.nullableString(IssueSource::severityDisplay);
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
          case "delayCause" -> SortSupport.nullableString(IssueSource::delayCause);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.moduleNames()));
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
    record.put("severityLevel", issue.severityDisplay());
    record.put("bugStatus", issue.bugStatus());
    record.put("delayCause", issue.delayCause());
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private List<IssueSource> loadSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove(TESTING_PHASE_FIELD);
    Long projectId = effectiveProjectId(queryFilters);
    SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate =
        SystemTestPhaseSqlPredicateSupport.legacyExactPhasePredicate(filterGroup, phaseScopeResolver);
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
    log.info("System test phase statistics board returned empty result without triggering synchronous rebuild");
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

  private Map<String, AggregateCounts> loadBoardAggregateCounts(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove(TESTING_PHASE_FIELD);
    Long projectId = effectiveProjectId(queryFilters);
    if (projectId != null) {
      queryFilters.put("projectId", String.valueOf(projectId));
    }
    SystemTestPhaseSqlPredicateSupport.SqlPredicate phasePredicate =
        SystemTestPhaseSqlPredicateSupport.legacyExactPhasePredicate(filterGroup, phaseScopeResolver);
    try {
      return issueFactQueryService.query(
              BOARD_AGGREGATE_SQL,
              queryFilters,
              phasePredicate.sql(),
              phasePredicate.args(),
              "group by testing_phase",
              (rs, rowNum) ->
                  Map.entry(
                      StatisticSourceValueSupport.text(rs.getString("row_key"), ""),
                      new AggregateCounts(
                          rs.getLong("level1"),
                          rs.getLong("level2"),
                          rs.getLong("level3"),
                          rs.getLong("suggestion"))))
          .stream()
          .filter(entry -> StringUtils.hasText(entry.getKey()))
          .collect(
              java.util.stream.Collectors.toMap(
                  Map.Entry::getKey,
                  Map.Entry::getValue,
                  AggregateCounts::plus,
                  LinkedHashMap::new));
    } catch (DataAccessException e) {
      log.warn("Failed to load system test phase statistics aggregate counts", e);
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
        StatisticSourceValueSupport.text(rs.getString("severity_alias"), ""),
        StatisticSourceValueSupport.text(rs.getString("bug_status"), ""),
        StatisticSourceValueSupport.text(rs.getString("category"), ""),
        StatisticSourceValueSupport.text(rs.getString("delay_cause"), ""),
        rs.getBoolean("is_excluded"),
        StatisticSourceValueSupport.text(rs.getString("exclusion_reason"), ""),
        rs.getBoolean("is_regression"),
        rs.getBoolean("is_crash"),
        rs.getBoolean("is_level1_other"),
        StatisticSourceValueSupport.split(rs.getString("module_names")),
        StatisticSourceValueSupport.split(rs.getString("label_names")));
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private List<StatisticFilterOption> loadPhaseOptions(long projectId) {
    return phaseOptionsFromDefinitions(loadPhaseDefinitions(projectId));
  }

  private List<StatisticFilterOption> phaseOptionsFromDefinitions(List<PhaseDefinition> definitions) {
    Map<String, StatisticFilterOption> options = new LinkedHashMap<>();
    for (PhaseDefinition definition : definitions) {
      String value = definition.parentName();
      if (StringUtils.hasText(value)) {
        options.putIfAbsent(value, new StatisticFilterOption(value, value));
      }
    }
    return new ArrayList<>(options.values());
  }

  private List<PhaseDefinition> loadPhaseDefinitions(long projectId) {
    return phaseCatalogService.listGroups(projectId).stream()
        .flatMap(group -> group.testingPhases().stream()
            .filter(StringUtils::hasText)
            .map(testingPhase -> new PhaseDefinition(testingPhase, group.name())))
        .toList();
  }

  private List<PhaseDefinition> phaseDefinitionsForSelectedPhase(
      List<PhaseDefinition> definitions,
      String selectedTestingPhase) {
    String selected = trimToNull(selectedTestingPhase);
    if (selected == null) {
      return List.of();
    }
    return definitions.stream()
        .filter(definition -> selected.equalsIgnoreCase(definition.parentName()))
        .toList();
  }

  private boolean isConfiguredPhase(String phaseLabel, List<PhaseDefinition> definitions) {
    if (!StringUtils.hasText(phaseLabel) || definitions == null || definitions.isEmpty()) {
      return false;
    }
    return definitions.stream().anyMatch(definition -> definition.testingPhase().equalsIgnoreCase(phaseLabel));
  }

  private long effectiveProjectId(Map<String, String> filters) {
    Long projectId =
        filters == null ? null : StatisticSourceValueSupport.parseLong(filters.get("projectId"));
    return projectId == null ? SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID : projectId;
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

  private record AggregateBucket(String rowKey, String rowLabel, List<AggregateCounts> counts) {
    AggregateBucket(String rowKey, String rowLabel) {
      this(rowKey, rowLabel, new ArrayList<>());
    }

    void accept(AggregateCounts count) {
      counts.add(count);
    }

    StatisticRowData toRowData() {
      long level1 = counts.stream().mapToLong(AggregateCounts::level1).sum();
      long level2 = counts.stream().mapToLong(AggregateCounts::level2).sum();
      long level3 = counts.stream().mapToLong(AggregateCounts::level3).sum();
      long suggestion = counts.stream().mapToLong(AggregateCounts::suggestion).sum();
      long total = level1 + level2 + level3;
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              cell("level1", level1, true),
              cell("level2", level2, true),
              cell("level3", level3, true),
              cell("suggestion", suggestion, true),
              cell("total", total, false)));
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
      String severityAlias,
      String bugStatus,
      String category,
      String delayCause,
      boolean excluded,
      String exclusionReason,
      boolean regression,
      boolean crash,
      boolean level1Other,
      List<String> moduleNames,
      List<String> labels) implements SystemTestPhaseFilterSource {
    boolean inSystemTestScope() {
      return StringUtils.hasText(primaryPhaseLabel());
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isSeverity(String severity) {
      return contains(severityAlias, severity);
    }

    boolean isLevel1() {
      return isRegularMetricIssue() && isSeverity("一级缺陷");
    }

    boolean isLevel2() {
      return isRegularMetricIssue() && isSeverity("二级缺陷");
    }

    boolean isLevel3() {
      return isRegularMetricIssue() && isSeverity("三级缺陷");
    }

    /*
     * 建议类是领导确认后的独立列：老平台常把 category=建议 的议题在公共过滤中排除，
     * 这样能保护一级/二级/三级和总计不被建议类污染，但也会让建议列固定为 0。
     * 新平台在本页只恢复“建议类缺陷”列，且明确不影响其它列。后续若有 AI 或开发者
     * 想按“老平台建议列为 0”回退，请先和业务/开发确认这段例外口径。
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

    boolean isCountableByRule() {
      return isLevel1() || isLevel2() || isLevel3();
    }

    boolean isCountableByRuleOrSuggestion() {
      return isCountableByRule() || isSuggestion();
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

    String severityDisplay() {
      if (isLevel1()) return level1Kind();
      if (isSuggestion()) return IssueDisplayValueSupport.displaySeverityLevel("SUGGESTION");
      if (StringUtils.hasText(severityAlias)) return severityAlias;
      return IssueDisplayValueSupport.displaySeverityLevelOrFallback(severityLevel, "-");
    }

    private String level1Kind() {
      if (regression) return "一级缺陷-回退";
      if (crash) return "一级缺陷-挂机";
      if (level1Other) return "一级缺陷-其他";
      return "一级缺陷";
    }

    private boolean hasScope(String value) {
      return StringUtils.hasText(value)
          && (value.contains("系统测试") || value.contains("回归测试"));
    }

    private boolean contains(String text, String token) {
      return StringUtils.hasText(text) && text.contains(token);
    }
  }

  private record PhaseDefinition(String testingPhase, String parentName) {}

  private record RuleFlowSnapshot(
      List<IssueSource> finalSources,
      List<StatisticRuleFlowStep> flowSteps) {}
}
