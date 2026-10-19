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
import com.data.collection.platform.entity.statistics.StatisticDetailCollection;
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
import com.data.collection.platform.service.IssueFactFilterGroupSqlSupport;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SqlPredicate;
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
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueByFunctionBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-by-function";
  private static final String RULE_VERSION = "customer-issue-by-function@2026-10-15-v9";
  /** 允许继续作为查询参数下推的键；其余业务字段一律由条件表达式承载，避免同一条件被重复 AND。 */
  private static final Set<String> SCOPE_FILTER_KEYS = Set.of("sourceInstance", "projectId");
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
              StatisticIssueDetailColumns.bugStatus()),
          List.of(),
          List.of(StatisticIssueDetailColumns.project("所属项目")));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;


  public CustomerIssueByFunctionBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.issueLinkSupport = issueLinkSupport;
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
    QueryScope queryScope = queryScope(filterGroup);
    Map<String, String> snapshotFilters =
        customerSnapshotFilters(filters, queryScope.milestoneScope());
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, queryScope, buildDefinition()),
        ignored -> buildBoardResponse(filters, queryScope));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters, QueryScope queryScope) {
    long startedAt = System.currentTimeMillis();
    Map<String, List<AggregateBucket>> bucketsByModule = loadFunctionBuckets(filters, queryScope);
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
        queryScope.echoedFilterGroup(),
        rows,
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount),
        null,
        null);
  }

  @Override
  public void refreshSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    Set<String> affectedMilestones = new LinkedHashSet<>(milestoneCatalogService.listMilestones(context));
    if (affectedMilestones.isEmpty()) {
      return;
    }
    List<StatisticFilterOption> milestoneOptions =
        loadMilestoneOptions().stream()
            .filter(option -> affectedMilestones.contains(option.value()))
            .toList();
    for (StatisticFilterGroup filterGroup :
        CustomerIssueSqlScopeSupport.milestoneFilterGroups(milestoneOptions, 3)) {
      QueryScope queryScope = queryScope(filterGroup);
      Map<String, String> filters =
          customerSnapshotFilters(Map.of(), queryScope.milestoneScope());
      snapshotService.save(
          snapshotRequest(filters, queryScope, buildDefinition()),
          buildBoardResponse(filters, queryScope));
    }
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    QueryScope queryScope = queryScope(filterGroup);
    CellAddress address =
        parseCellAddress(request.rowKey(), request.columnKey(), request.filters(), queryScope);
    List<IssueSource> loaded = address == null
        ? loadSources(request.filters(), queryScope)
        : loadDetailSources(request.filters(), queryScope, address);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loaded, queryScope).finalSources().stream()
            .filter(issue -> matchesDetailRequest(issue, request, address))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse(
        "客户问题功能缺陷明细",
        "展示当前模块/功能与指标命中的客户问题议题明细。",
        List.of(StatisticDetailCollection.detailList()),
        StatisticDetailCollection.DETAIL_KEY,
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
    QueryScope queryScope = queryScope(filterGroup);
    RuleFlowSnapshot snapshot =
        buildRuleFlowSnapshot(loadSources(filters, queryScope), queryScope);
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
      QueryScope queryScope,
      StatisticBoardDefinition definition) {
    String selectedMilestone =
        CustomerIssueMilestoneFilterSupport.selectedMilestone(queryScope.milestoneScope());
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;milestone=" + (StringUtils.hasText(selectedMilestone) ? selectedMilestone : "none"),
        LEGACY_CC_PRODUCT_PROJECT_ID,
        com.data.collection.platform.service.IssueScopeDimension.MILESTONE,
        selectedMilestone,
        filters,
        definition,
        queryScope.echoedFilterGroup());
  }

  private Map<String, String> customerSnapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    return CustomerIssueMilestoneFilterSupport
        .snapshotFilters(withoutReservedFilters(filters), effectiveFilterGroup, LEGACY_CC_PRODUCT_PROJECT_ID)
        .filters();
  }

  /**
   * 组装规则说明的流程快照。
   *
   * <p>里程碑切换用硬范围判定；末步的模块资格必须与主表同源：模块为空或以“未设定”开头的议题不进入本统计，
   * 命中正向模块条件时只保留该模块行，因此末步数量等于主表合计。输入计数保持议题数、输出计数为模块贡献数，
   * 两者单位不同，不做去重合并；末步样本只取实际产生有效模块贡献的议题，零贡献时样本为空。
   */
  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, QueryScope queryScope) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> visible = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> phaseFiltered =
        visible.stream().filter(issue -> matchesTestingPhase(issue, queryScope.milestoneScope())).toList();
    List<IssueSource> withFunction = phaseFiltered.stream().filter(issue -> StringUtils.hasText(issue.functionName())).toList();
    List<List<String>> qualifyingModulesByIssue =
        withFunction.stream()
            .map(
                issue ->
                    issue.moduleNames().stream()
                        .filter(
                            moduleName ->
                                StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(
                                    moduleName, queryScope.userGroup()))
                        .toList())
            .toList();
    List<IssueSource> contributingIssues =
        IntStream.range(0, withFunction.size())
            .filter(index -> !qualifyingModulesByIssue.get(index).isEmpty())
            .mapToObj(withFunction::get)
            .toList();
    long moduleContributions = qualifyingModulesByIssue.stream().mapToLong(List::size).sum();
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
                "将客户问题议题按模块和功能组合展开为统计行；模块为空或以“未设定”开头的议题不计入，"
                    + "命中正向模块条件时只保留对应模块行，因此本步数量与主表合计一致。",
                withFunction.size(),
                moduleContributions,
                contributingIssues,
                this::toRuleFlowSample
            )));
  }

  private boolean matchesTestingPhase(IssueSource issue, StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.matches(
        issue.milestoneTitle(), filterGroup, milestoneCatalogService);
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 功能: " + issue.functionName());
  }
  private List<IssueSource> loadSources(Map<String, String> filters, QueryScope queryScope) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        scopedQuery(filters, queryScope, "coalesce(function_name, '') <> ''", List.of());
    return issueFactQueryService.query(
        FACT_SQL,
        scope.filters(),
        scope.predicate(),
        scope.args(),
        this::mapIssueFact);
  }

  /**
   * 组装本板一次查询的范围谓词。
   *
   * <p>公共范围（项目 325、2026 创建起点、排除规则与里程碑别名集合）由 {@code CustomerIssueSqlScopeSupport}
   * 收口；页面已声明的条件表达式整体 AND 到同一谓词上，主表聚合、明细与规则说明共用本方法，因此三处
   * 看到的是同一套条件。里程碑条件属于硬范围，不再重复进入表达式；其余条件无法下推时报错，不静默忽略。
   *
   * @param filters 原始查询参数，业务字段的收窄一律改由条件表达式单一路径下推
   * @param queryScope 里程碑硬范围与保持原始 AND/OR 的用户条件组
   * @param extraPredicate 该查询自身的附加谓词，例如功能非空或单元格定位
   * @param extraArgs {@code extraPredicate} 的按序绑定参数
   * @return 只带来源与公共范围参数、并已下推全部已声明条件的查询范围
   */
  private CustomerIssueSqlScopeSupport.SqlScope scopedQuery(
      Map<String, String> filters,
      QueryScope queryScope,
      String extraPredicate,
      List<?> extraArgs) {
    CustomerIssueSqlScopeSupport.SqlScope publicScope =
        CustomerIssueSqlScopeSupport.boardScope(
            withoutReservedFilters(filters), queryScope.milestoneScope(), milestoneCatalogService);
    CustomerIssueSqlScopeSupport.SqlScope scope =
        CustomerIssueSqlScopeSupport.withExtraPredicate(
            new CustomerIssueSqlScopeSupport.SqlScope(
                scopeOnlyFilters(publicScope.filters()),
                publicScope.predicate(),
                publicScope.args()),
            extraPredicate,
            extraArgs);
    SqlPredicate conditions = compiledConditions(queryScope.userGroup());
    return conditions == null
        ? scope
        : CustomerIssueSqlScopeSupport.withExtraPredicate(scope, conditions.predicate(), conditions.args());
  }

  /**
   * 解析一次请求的查询收口。
   *
   * <p>默认里程碑只补进硬范围，绝不改写用户条件组的 AND/OR；主表快照与响应沿用合并后的条件形状，
   * 但保留用户选择的逻辑，避免“满足任意”与“满足全部”落到同一个缓存键上。
   */
  private QueryScope queryScope(StatisticFilterGroup filterGroup) {
    StatisticFilterGroup milestoneScope = applyDefaultMilestone(filterGroup);
    return new QueryScope(milestoneScope, userConditionGroup(filterGroup));
  }

  /** 里程碑硬范围 + 保持原始逻辑的用户条件组；两者共同决定一次查询的读取与求值。 */
  private record QueryScope(StatisticFilterGroup milestoneScope, StatisticFilterGroup userGroup) {
    StatisticFilterGroup echoedFilterGroup() {
      return new StatisticFilterGroup(userGroup.logic(), milestoneScope.conditions());
    }
  }

  /** 用户条件组去掉里程碑后的可下推谓词；无用户条件时返回 null，表示公共范围即为完整范围。 */
  private SqlPredicate compiledConditions(StatisticFilterGroup userGroup) {
    if (userGroup.conditions().isEmpty()) {
      return null;
    }
    return IssueFactFilterGroupSqlSupport.toCustomerIssueSql(userGroup).orElseThrow(
        () -> new IllegalArgumentException("按功能统计存在无法求值的筛选条件，请检查条件字段与关系"));
  }

  /**
   * 用户条件组：取出作为硬范围的里程碑等值条件，其余条件保持原有 AND/OR 结构。
   *
   * <p>里程碑等值由公共范围按别名集合展开后收口，重复编译成 {@code milestone_title = 缩写} 会把
   * “CC2026R3”与事实里的“CC2026 R3”判成不同值；里程碑的其他关系（包含、不等）没有别名展开，
   * 继续交给条件编译器求值，不做静默丢弃。
   */
  private static StatisticFilterGroup userConditionGroup(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return new StatisticFilterGroup("AND", List.of());
    }
    List<StatisticFilterCondition> userConditions = filterGroup.conditions().stream()
        .filter(condition -> condition != null)
        .filter(condition -> !isMilestoneHardRange(condition))
        .toList();
    return new StatisticFilterGroup(filterGroup.logic(), userConditions);
  }

  private static boolean isMilestoneHardRange(StatisticFilterCondition condition) {
    return CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD.equals(condition.fieldKey())
        && "eq".equals(condition.operator());
  }

  /** 只保留来源与公共范围参数，避免遗留业务字段下推与条件表达式对同一条件重复求值（OR 会被二次 AND）。 */
  private static Map<String, String> scopeOnlyFilters(Map<String, String> filters) {
    return filters.entrySet().stream()
        .filter(entry -> SCOPE_FILTER_KEYS.contains(entry.getKey()))
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  private Map<String, List<AggregateBucket>> loadFunctionBuckets(
      Map<String, String> filters,
      QueryScope queryScope) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        scopedQuery(filters, queryScope, "coalesce(function_name, '') <> ''", List.of());
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
      if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(
          moduleName, queryScope.userGroup())) {
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
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
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
            0L,
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

  private CellAddress parseCellAddress(
      String rowKey, String columnKey, Map<String, String> filters, QueryScope queryScope) {
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
    List<AggregateBucket> buckets =
        bucketsForModule(loadSources(filters, queryScope), queryScope, moduleName);
    if (rowIndex < 1 || rowIndex > buckets.size()) {
      return null;
    }
    String functionName = functionNameFromRowKey(buckets.get(rowIndex - 1).rowKey());
    return StringUtils.hasText(functionName) ? new CellAddress(moduleName, functionName) : null;
  }

  private List<AggregateBucket> bucketsForModule(
      List<IssueSource> sources, QueryScope queryScope, String moduleName) {
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue :
        buildRuleFlowSnapshot(sources, queryScope).finalSources()) {
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
      QueryScope queryScope,
      CellAddress address) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        scopedQuery(
            filters,
            queryScope,
            """
            coalesce(function_name, '') = ?
            and exists (
              select 1
                from regexp_split_to_table(coalesce(module_names, ''), '\\s*[,&]\\s*') as detail_modules(module_name)
               where btrim(detail_modules.module_name) = ?
            )
            """,
            List.of(address.functionName(), address.moduleName()));
    return issueFactQueryService.query(
        FACT_SQL,
        scope.filters(),
        scope.predicate(),
        scope.args(),
        this::mapIssueFact);
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService);
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
