package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
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
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.SortSupport;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueByFunctionBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-by-function";
  private static final String RULE_VERSION = "customer-issue-by-function@2026-07-09-v3";
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final String EMPTY_MODULE_LABEL = IssueDisplayValueSupport.EMPTY_MODULE_LABEL;
  private static final String ROW_KEY_SEPARATOR = "||";
  private static final String DETAIL_MODULE_PARAM = "detailModuleName";
  private static final String DETAIL_FUNCTION_PARAM = "detailFunctionName";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String FACT_SQL =
      """
      select source_instance,
             project_id,
             coalesce(project_name, '') as project_name,
             issue_id,
             issue_iid,
             coalesce(title, '') as title,
             coalesce(issue_state, 'opened') as issue_state,
             coalesce(testing_phase, '') as testing_phase,
             coalesce(system_test_label, '') as system_test_label,
             coalesce(severity_level, '') as severity_level,
             coalesce(priority_level, '') as priority_level,
             coalesce(bug_status, '') as bug_status,
             coalesce(category, '') as category,
             coalesce(reason_category, '') as reason_category,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(author_name, '') as author_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(module_names, '') as module_names,
             coalesce(function_name, '') as function_name,
             coalesce(label_names, '') as label_names,
             coalesce(is_excluded, false) as is_excluded,
             coalesce(is_fixed, false) as is_fixed,
             coalesce(delay_issue, false) as delay_issue,
             coalesce(is_response_delayed, false) as is_response_delayed,
             created_at_source,
             updated_at_source,
             closed_at_source
       from issue_fact
       where deleted = false
      """;
  private static final String FUNCTION_AGGREGATE_SQL =
      """
      select btrim(modules.module_name) as module_name,
             coalesce(function_name, '') as function_name,
             count(*) as issue_count
        from issue_fact
        cross join lateral regexp_split_to_table(
             coalesce(module_names, ''),
             '\\s*[,&]\\s*') as modules(module_name)
       where deleted = false
      """;
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.customerIssue(
          "标题",
          "模块",
          List.of(
              new StatisticDetailColumn("functionName", "功能", 180, 180, true, "tag"),
              StatisticIssueDetailColumns.severity("severityLevel", "严重程度", 140),
              new StatisticDetailColumn("priorityLevel", "优先级", 120, 120, true, "tag"),
              StatisticIssueDetailColumns.state("状态")),
          List.of(),
          List.of(StatisticIssueDetailColumns.project("所属项目")));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueByFunctionBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
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
        "客户问题按功能展示缺陷数量",
        "按模块和功能展示客户问题缺陷数量。",
        "",
        "",
        "序号",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, "测试阶段", 200),
            StatisticFilterFieldFactory.text("moduleName", "模块名称", 180),
            StatisticFilterFieldFactory.text("functionName", "功能名称", 180),
            StatisticFilterFieldFactory.text("milestoneTitle", "里程碑", 180),
            StatisticFilterFieldFactory.select(
                "severityLevel",
                "严重程度",
                180,
                IssueDisplayValueSupport.severityFilterOptions(true))),
        List.of(
            new StatisticColumnGroup(
                "placeholder",
                "功能缺陷数量",
                List.of(
                    leaf("placeholder_function", "功能", false, "text"),
                    leaf("placeholder_count", "问题数量", false, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题功能缺陷数量数据。");
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    Map<String, String> snapshotFilters = customerSnapshotFilters(filters, effectiveFilterGroup);
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, effectiveFilterGroup, buildDefinition()),
        () -> buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters, StatisticFilterGroup effectiveFilterGroup) {
    long startedAt = System.currentTimeMillis();
    Map<String, List<AggregateBucket>> bucketsByModule = loadFunctionBuckets(filters, effectiveFilterGroup);
    List<String> orderedModules =
        bucketsByModule.entrySet().stream()
            .sorted(
                Comparator.<Map.Entry<String, List<AggregateBucket>>>comparingInt(entry -> entry.getValue().size())
                    .reversed()
                    .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
            .map(Map.Entry::getKey)
            .toList();
    Map<String, List<AggregateBucket>> orderedBuckets = new LinkedHashMap<>();
    orderedModules.forEach(moduleName -> orderedBuckets.put(moduleName, bucketsByModule.get(moduleName)));
    StatisticBoardDefinition definition = buildDefinition(orderedModules);
    List<StatisticRowData> rows = toLegacyPivotRows(orderedBuckets);
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
    List<StatisticFilterOption> milestoneOptions = loadMilestoneOptions();
    for (StatisticFilterGroup filterGroup :
        CustomerIssueSqlScopeSupport.milestoneFilterGroups(milestoneOptions, 3)) {
      StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
      Map<String, String> filters = customerSnapshotFilters(Map.of(), effectiveFilterGroup);
      snapshotService.save(
          snapshotRequest(filters, effectiveFilterGroup, buildDefinition()),
          buildBoardResponse(filters, effectiveFilterGroup));
    }
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    CellAddress address = parseCellAddress(request.rowKey(), request.columnKey(), request.filters());
    List<IssueSource> loaded = address == null
        ? loadSources(request.filters(), effectiveFilterGroup)
        : loadDetailSources(request.filters(), effectiveFilterGroup, address);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loaded, effectiveFilterGroup).finalSources().stream()
            .filter(issue -> matchesDetailRequest(issue, request, address))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse(
        "客户问题功能缺陷明细",
        "展示当前模块/功能与指标命中的客户问题议题明细。",
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
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    RuleFlowSnapshot snapshot =
        buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题按功能展示缺陷数量规则说明",
        RULE_VERSION,
        "当前统计先限定客户问题范围，再保留标题中能识别出功能名的议题。",
        "功能名来自 issue 标题开头的全角书名号片段，例如【草图约束】；同一条议题如属于多个有效模块，会分别计入对应模块/功能行。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "legacy-pivot", "模块功能列", "主表按模块分组，每个模块包含“功能”和“问题数量”两列。", "问题数量 = 当前客户问题范围内同时命中模块和功能的议题数量", null)),
        null);
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    String milestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(milestone)) {
      return milestone + "-各个功能下缺陷数量.xlsx";
    }
    return "各个功能下缺陷数量.xlsx";
  }

  private StatisticBoardDefinition buildDefinition(List<String> moduleNames) {
    List<StatisticColumnGroup> columnGroups = moduleNames.stream()
        .map(moduleName -> new StatisticColumnGroup(
            columnKey(moduleName),
            moduleName,
            List.of(
                leaf(functionColumnKey(moduleName), "功能", false, "text"),
                leaf(countColumnKey(moduleName), "问题数量", true, "count"))))
        .toList();
    if (columnGroups.isEmpty()) {
      columnGroups = buildDefinition().columnGroups();
    }
    StatisticBoardDefinition base = buildDefinition();
    return new StatisticBoardDefinition(
        base.boardKey(),
        base.title(),
        base.description(),
        base.queryTitle(),
        base.queryDescription(),
        base.rowHeaderLabel(),
        base.filters(),
        columnGroups,
        base.detailColumns(),
        base.defaultPageSize(),
        base.emptyText());
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
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
        .snapshotFilters(withoutReservedFilters(filters), effectiveFilterGroup, LEGACY_CC_PRODUCT_PROJECT_ID)
        .filters();
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> visible = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> phaseFiltered =
        visible.stream().filter(issue -> matchesTestingPhase(issue, filterGroup)).toList();
    List<IssueSource> withFunction = phaseFiltered.stream().filter(issue -> StringUtils.hasText(issue.functionName())).toList();
    return new RuleFlowSnapshot(
        withFunction,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题事实",
                "加载已同步到平台的客户问题议题数据，并使用整理后的模块、里程碑和功能名称。",
                initial.size(),
                initial,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "限定为 CC_Product 自 2026-01-01 以来创建且携带里程碑的客户问题。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "exclude-filter",
                "剔除排除数据",
                "客户问题统计不排除建议类问题；仅剔除关闭后属于申请否决、需求如此或设计如此的数据。",
                scoped.size(),
                visible,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "milestone-filter",
                "应用里程碑切换",
                "根据页面顶部选择的 CC_Product 里程碑收口客户问题；未选择时使用里程碑列表第一项。",
                visible.size(),
                phaseFiltered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "function-filter",
                "保留已识别功能",
                "只保留标题中能识别出功能名的议题。",
                phaseFiltered.size(),
                withFunction,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "module-function-expand",
                "按模块/功能展开",
                "将客户问题议题按模块和功能组合展开；模块为空或未设定的议题不进入本统计。",
                withFunction.size(),
                withFunction.stream().mapToLong(issue -> issue.displayModuleNames().size()).sum(),
                withFunction,
                this::toRuleFlowSample
            )));
  }

  private boolean matchesTestingPhase(IssueSource issue, StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.matches(issue.milestoneTitle(), issue.testingPhase(), filterGroup);
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 功能: " + issue.functionName());
  }
  private List<IssueSource> loadSources(Map<String, String> filters) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(parseFilterGroup(filters, buildDefinition()));
    return loadSources(filters, effectiveFilterGroup);
  }

  private List<IssueSource> loadSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        CustomerIssueSqlScopeSupport.withExtraPredicate(
            CustomerIssueSqlScopeSupport.boardScope(withoutReservedFilters(filters), filterGroup),
            "coalesce(function_name, '') <> ''",
            List.of());
    try {
      return issueFactQueryService.query(
          FACT_SQL,
          scope.filters(),
          scope.predicate(),
          scope.args(),
          this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue by function facts", error);
      return List.of();
    }
  }

  private Map<String, List<AggregateBucket>> loadFunctionBuckets(
      Map<String, String> filters,
      StatisticFilterGroup filterGroup) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        CustomerIssueSqlScopeSupport.withExtraPredicate(
            CustomerIssueSqlScopeSupport.boardScope(withoutReservedFilters(filters), filterGroup),
            "coalesce(function_name, '') <> ''",
            List.of());
    try {
      List<AggregateBucket> buckets =
          issueFactQueryService.query(
              FUNCTION_AGGREGATE_SQL,
              scope.filters(),
              scope.predicate(),
              scope.args(),
              """
              group by btrim(modules.module_name), coalesce(function_name, '')
              having btrim(modules.module_name) <> ''
                 and btrim(modules.module_name) not like '未设定%'
              order by lower(btrim(modules.module_name)), lower(coalesce(function_name, ''))
              """,
              this::mapFunctionBucket);
      Map<String, List<AggregateBucket>> bucketsByModule = new LinkedHashMap<>();
      for (AggregateBucket bucket : buckets) {
        String moduleName = moduleNameFromRowKey(bucket.rowKey());
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, filterGroup)) {
          continue;
        }
        bucketsByModule.computeIfAbsent(moduleName, ignored -> new ArrayList<>()).add(bucket);
      }
      bucketsByModule.replaceAll((moduleName, moduleBuckets) ->
          moduleBuckets.stream()
              .sorted(
                  Comparator.comparing(AggregateBucket::count, Comparator.reverseOrder())
                      .thenComparing(
                          bucket -> functionNameFromRowKey(bucket.rowKey()),
                          String.CASE_INSENSITIVE_ORDER))
              .toList());
      return bucketsByModule;
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue by function aggregates", error);
      return Map.of();
    }
  }

  private AggregateBucket mapFunctionBucket(ResultSet rs, int rowNum) throws SQLException {
    String moduleName = StatisticSourceValueSupport.text(rs.getString("module_name"), EMPTY_MODULE_LABEL);
    String functionName = StatisticSourceValueSupport.text(rs.getString("function_name"));
    String rowKey = rowKey(moduleName, functionName);
    return new AggregateBucket(
        moduleName + " / " + functionName,
        rowKey,
        rs.getLong("issue_count"));
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        StatisticSourceValueSupport.text(rs.getString("source_instance"), "default"),
        rs.getLong("project_id"),
        StatisticSourceValueSupport.text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        StatisticSourceValueSupport.text(rs.getString("title")),
        StatisticSourceValueSupport.text(rs.getString("issue_state")),
        StatisticSourceValueSupport.text(rs.getString("testing_phase")),
        StatisticSourceValueSupport.text(rs.getString("system_test_label")),
        StatisticSourceValueSupport.text(rs.getString("severity_level")),
        StatisticSourceValueSupport.text(rs.getString("priority_level")),
        StatisticSourceValueSupport.text(rs.getString("bug_status")),
        StatisticSourceValueSupport.text(rs.getString("category")),
        StatisticSourceValueSupport.text(rs.getString("reason_category")),
        StatisticSourceValueSupport.text(rs.getString("milestone_title")),
        StatisticSourceValueSupport.text(rs.getString("author_name")),
        StatisticSourceValueSupport.text(rs.getString("assignee_name")),
        splitModuleNames(rs.getString("module_names")),
        StatisticSourceValueSupport.text(rs.getString("function_name")),
        StatisticSourceValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("is_excluded"),
        rs.getBoolean("is_fixed"),
        rs.getBoolean("delay_issue"),
        rs.getBoolean("is_response_delayed"),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at_source")));
  }

  private boolean matchesRow(IssueSource issue, String requestedRowKey) {
    if (!StringUtils.hasText(requestedRowKey) || TOTAL_ROW_KEY.equals(requestedRowKey)) {
      return true;
    }
    return issue.displayModuleNames().stream()
        .map(moduleName -> rowKey(moduleName, issue.functionName()))
        .anyMatch(requestedRowKey::equals);
  }

  private boolean matchesDetailRequest(IssueSource issue, StatisticDetailRequest request, CellAddress address) {
    if (address != null) {
      return issue.displayModuleNames().contains(address.moduleName())
          && address.functionName().equals(issue.functionName());
    }
    return matchesRow(issue, request.rowKey());
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "placeholder_count" -> issue -> false;
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.displayModuleNames()));
          case "functionName" -> SortSupport.nullableString(IssueSource::functionName);
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "severityLevel" -> SortSupport.nullableString(IssueSource::displaySeverityLevel);
          case "priorityLevel" -> SortSupport.nullableString(IssueSource::priorityLevel);
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
    record.put("moduleNames", String.join("、", issue.displayModuleNames()));
    record.put("functionName", issue.functionName());
    record.put("projectName", issue.projectName());
    record.put("severityLevel", issue.displaySeverityLevel());
    record.put("priorityLevel", issue.priorityLevel());
    record.put("bugStatus", issue.bugStatus());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    return record;
  }

  private String rowKey(String moduleName, String functionName) {
    return moduleName + ROW_KEY_SEPARATOR + functionName;
  }

  private List<String> splitModuleNames(String raw) {
    return StatisticSourceValueSupport.split(raw, "\\s*[,&]\\s*").stream()
        .filter(moduleName -> !moduleName.startsWith("未设定"))
        .toList();
  }

  private List<StatisticRowData> toLegacyPivotRows(Map<String, List<AggregateBucket>> bucketsByModule) {
    int maxRows = bucketsByModule.values().stream().mapToInt(List::size).max().orElse(0);
    List<StatisticRowData> rows = new ArrayList<>();
    for (int rowIndex = 0; rowIndex < maxRows; rowIndex++) {
      List<StatisticCellData> cells = new ArrayList<>();
      for (Map.Entry<String, List<AggregateBucket>> entry : bucketsByModule.entrySet()) {
        String moduleName = entry.getKey();
        AggregateBucket bucket = rowIndex < entry.getValue().size() ? entry.getValue().get(rowIndex) : null;
        String functionName = bucket == null ? "" : functionNameFromRowKey(bucket.rowKey());
        long countValue = bucket == null ? 0 : bucket.count();
        String rowKey = String.valueOf(rowIndex + 1);
        cells.add(new StatisticCellData(
            functionColumnKey(moduleName),
            0,
            functionName,
            false,
            null,
            Map.of()));
        cells.add(new StatisticCellData(
            countColumnKey(moduleName),
            countValue,
            countValue == 0 ? "-" : count(countValue),
            countValue > 0,
            countValue > 0 ? "issue-list" : null,
            countValue > 0 ? Map.of(
                DETAIL_MODULE_PARAM, moduleName,
                DETAIL_FUNCTION_PARAM, functionName) : Map.of()));
      }
      rows.add(new StatisticRowData(String.valueOf(rowIndex + 1), String.valueOf(rowIndex + 1), cells));
    }
    return rows;
  }

  private CellAddress parseCellAddress(String rowKey, String columnKey, Map<String, String> filters) {
    String detailModuleName = filters == null ? null : filters.get(DETAIL_MODULE_PARAM);
    String detailFunctionName = filters == null ? null : filters.get(DETAIL_FUNCTION_PARAM);
    if (StringUtils.hasText(detailModuleName) && StringUtils.hasText(detailFunctionName)) {
      return new CellAddress(detailModuleName.trim(), detailFunctionName.trim());
    }
    String moduleName = moduleNameFromCountColumnKey(columnKey);
    Integer rowIndex = parseOneBasedIndex(rowKey);
    if (!StringUtils.hasText(moduleName) || rowIndex == null) {
      return null;
    }
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    List<AggregateBucket> buckets = bucketsForModule(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup, moduleName);
    if (rowIndex < 1 || rowIndex > buckets.size()) {
      return null;
    }
    String functionName = functionNameFromRowKey(buckets.get(rowIndex - 1).rowKey());
    return StringUtils.hasText(functionName) ? new CellAddress(moduleName, functionName) : null;
  }

  private List<AggregateBucket> bucketsForModule(
      List<IssueSource> sources, StatisticFilterGroup effectiveFilterGroup, String moduleName) {
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : buildRuleFlowSnapshot(sources, effectiveFilterGroup).finalSources()) {
      if (!issue.displayModuleNames().contains(moduleName)) {
        continue;
      }
      String rowKey = rowKey(moduleName, issue.functionName());
      buckets.computeIfAbsent(rowKey, key -> new AggregateBucket(moduleName + " / " + issue.functionName(), rowKey))
          .accept(issue);
    }
    return buckets.values().stream()
        .sorted(
            Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(bucket -> bucket.issues().size(), Comparator.reverseOrder()))
        .toList();
  }

  private List<IssueSource> loadDetailSources(
      Map<String, String> filters,
      StatisticFilterGroup filterGroup,
      CellAddress address) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        CustomerIssueSqlScopeSupport.withExtraPredicate(
            CustomerIssueSqlScopeSupport.boardScope(withoutReservedFilters(filters), filterGroup),
            """
            coalesce(function_name, '') = ?
            and exists (
              select 1
                from regexp_split_to_table(coalesce(module_names, ''), '\\s*[,&]\\s*') as detail_modules(module_name)
               where btrim(detail_modules.module_name) = ?
            )
            """,
            List.of(address.functionName(), address.moduleName()));
    try {
      return issueFactQueryService.query(
          FACT_SQL,
          scope.filters(),
          scope.predicate(),
          scope.args(),
          this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue by function detail facts", error);
      return List.of();
    }
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService.listMilestones(), phaseScopeResolver);
  }

  private List<StatisticFilterOption> loadMilestoneOptions() {
    return milestoneCatalogService.listMilestones().stream()
        .map(value -> new StatisticFilterOption(value, value))
        .toList();
  }

  private String functionNameFromRowKey(String rowKey) {
    if (!StringUtils.hasText(rowKey)) {
      return "";
    }
    int separatorIndex = rowKey.indexOf(ROW_KEY_SEPARATOR);
    return separatorIndex < 0 ? "" : rowKey.substring(separatorIndex + ROW_KEY_SEPARATOR.length());
  }

  private String moduleNameFromRowKey(String rowKey) {
    if (!StringUtils.hasText(rowKey)) {
      return "";
    }
    int separatorIndex = rowKey.indexOf(ROW_KEY_SEPARATOR);
    return separatorIndex < 0 ? rowKey : rowKey.substring(0, separatorIndex);
  }

  private Integer parseOneBasedIndex(String rowKey) {
    try {
      return StringUtils.hasText(rowKey) ? Integer.parseInt(rowKey) : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String columnKey(String moduleName) {
    return "module_" + Integer.toHexString(moduleName.hashCode());
  }

  private String functionColumnKey(String moduleName) {
    return "func_name::" + moduleName;
  }

  private String countColumnKey(String moduleName) {
    return "func_count::" + moduleName;
  }

  private String moduleNameFromCountColumnKey(String columnKey) {
    String prefix = "func_count::";
    return StringUtils.hasText(columnKey) && columnKey.startsWith(prefix)
        ? columnKey.substring(prefix.length())
        : null;
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private record AggregateBucket(String rowLabel, String rowKey, long aggregateCount, List<IssueSource> issues) {
    AggregateBucket(String rowLabel, String rowKey) {
      this(rowLabel, rowKey, 0L, new ArrayList<>());
    }

    AggregateBucket(String rowLabel, String rowKey, long aggregateCount) {
      this(rowLabel, rowKey, aggregateCount, List.of());
    }

    void accept(IssueSource issue) {
      issues.add(issue);
    }

    long count() {
      return aggregateCount > 0L ? aggregateCount : issues.size();
    }

  }

  private record CellAddress(String moduleName, String functionName) {}

  private record IssueSource(
      String sourceInstance,
      Long projectId,
      String projectName,
      Long id,
      Integer iid,
      String title,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      String reasonCategory,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      List<String> moduleNames,
      String functionName,
      List<String> labels,
      boolean excluded,
      boolean fixed,
      boolean delayIssue,
      boolean responseDelayed,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }

    List<String> displayModuleNames() {
      return moduleNames.isEmpty() ? List.of(EMPTY_MODULE_LABEL) : moduleNames;
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isSolvedLike() {
      return fixed || isClosed();
    }

    boolean isSeverity(String severity) {
      return severity.equalsIgnoreCase(severityLevel);
    }

    String displaySeverityLevel() {
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
    }
  }

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
