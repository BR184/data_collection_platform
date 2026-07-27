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
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDelayIssuesBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-delay-issues";
  private static final String RULE_VERSION = "customer-issue-delay-issues@2026-07-10-v3";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总数";
  private static final String EMPTY_MODULE_LABEL = "未设定模块";
  private static final String P1 = "P1";
  private static final String P2 = "P2";
  private static final String P3 = "P3";
  private static final String GITLAB_API_ERROR = "GitLab接口报错";
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
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
             coalesce(priority_level, '') as priority_level,
             coalesce(bug_status, '') as bug_status,
             coalesce(category, '') as category,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(author_name, '') as author_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(module_names, '') as module_names,
             coalesce(label_names, '') as label_names,
             coalesce(is_excluded, false) as is_excluded,
             coalesce(delay_issue, false) as delay_issue,
             coalesce(is_response_delayed, false) as is_response_delayed,
             coalesce(is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(illegal_reason, '') as illegal_reason,
             coalesce(illegal_reasons, '') as illegal_reasons,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
       where deleted = false
      """;
  private static final String MODULE_CATALOG_SQL =
      """
      select coalesce(module_names, '') as module_names
        from issue_fact
       where deleted = false
         and project_id = ?
         and nullif(btrim(coalesce(module_names, '')), '') is not null
      """;
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.customerIssue(
          "议题标题",
          "模块名",
          List.of(
              StatisticIssueDetailColumns.state("议题状态"),
              new StatisticDetailColumn("priorityLevel", "紧急程度", 120, 120, true, "tag"),
              new StatisticDetailColumn("delayType", "延期类型", 140, 140, true, "tag"),
              StatisticIssueDetailColumns.bugStatus()),
          List.of(
              StatisticIssueDetailColumns.author("议题提交人"),
              StatisticIssueDetailColumns.assignee("议题处理人", 160)),
          List.of(
              StatisticIssueDetailColumns.milestone("里程碑"),
              StatisticIssueDetailColumns.createdAt()));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueDelayIssuesBoardService(
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
    PersonFilterOptions personOptions = loadPersonOptions();
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题延期问题",
        "按模块展示客户问题的响应延期和解决延期数量。",
        "",
        "",
        "模块",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text("milestoneTitle", "里程碑", 180),
            StatisticFilterFieldFactory.text("moduleName", "模块名", 180),
            StatisticFilterFieldFactory.select(
                "priorityLevel",
                "紧急程度",
                160,
                List.of(
                    new StatisticFilterOption("P1", P1),
                    new StatisticFilterOption("P2", P2),
                    new StatisticFilterOption("P3", P3))),
            StatisticFilterFieldFactory.text("issueState", "议题状态", 160),
            StatisticFilterFieldFactory.select("authorName", "议题提交人", 160, personOptions.authorNames()),
            StatisticFilterFieldFactory.select("assigneeName", "议题处理人", 160, personOptions.assigneeNames())),
        List.of(
            new StatisticColumnGroup(
                "response-delay",
                "响应延期的缺陷数量",
                List.of(
                    leaf("resp_delay_p1", "P1", true, "count"),
                    leaf("resp_delay_p2", "P2", true, "count"),
                    leaf("resp_delay_p3", "P3", true, "count"),
                    leaf("resp_delay_sum", "总计", true, "count"))),
            new StatisticColumnGroup(
                "resolve-delay",
                "解决延期的缺陷数量",
                List.of(
                    leaf("fix_delay_p1", "P1", true, "count"),
                    leaf("fix_delay_p2", "P2", true, "count"),
                    leaf("fix_delay_p3", "P3", true, "count"),
                    leaf("fix_delay_sum", "总计", true, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题延期统计数据。");
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
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    StatisticBoardDefinition definition = buildDefinition();
    List<String> moduleCatalog = loadLegacyModuleCatalog(effectiveFilterGroup);
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (String moduleName : moduleCatalog) {
      buckets.computeIfAbsent(moduleName, AggregateBucket::new);
    }
    if (StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(EMPTY_MODULE_LABEL, effectiveFilterGroup)) {
      buckets.computeIfAbsent(EMPTY_MODULE_LABEL, AggregateBucket::new);
    }
    for (IssueSource issue : snapshot.finalSources()) {
      for (String moduleName : issue.displayModuleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }
    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
            .map(AggregateBucket::toRowData)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    rows.add(new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY).acceptAll(snapshot.finalSources()).toRowData());
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
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters(), effectiveFilterGroup), effectiveFilterGroup).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    DetailRecordPage pageSlice = sliceDetailRecords(request, scoped, this::toDetailRecord);
    return new StatisticDetailResponse(
        "客户问题延期明细",
        "展示当前模块、紧急程度和延期类型命中的 CC_Product 议题明细。",
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
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters, effectiveFilterGroup), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题延期问题规则说明",
        RULE_VERSION,
        "当前统计先限定客户问题范围，再保留仍未关闭且已经命中延期规则的议题，按模块、紧急程度和延期类型统计。",
        "表格数量只统计紧急程度命中 P1、P2 或 P3 的议题；未设定紧急程度可参与延期事实判定，但不计入 P3 和总计。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "resp_delay",
                "响应延期",
                "统计响应超过期限且未按调研模板响应的议题。",
                "响应延期 = 超过对应紧急程度的响应期限且未按调研模板响应",
                null),
            new StatisticRuleMetricDefinition(
                "fix_delay",
                "解决延期",
                "统计超过解决期限且未按要求闭环或填写缺陷原因分析的议题。",
                "解决延期 = 超过解决期限且未按要求闭环或填写缺陷原因分析",
                null),
            new StatisticRuleMetricDefinition(
                "priority",
                "紧急程度归桶",
                "P1/P2/P3 按紧急程度标签统计；未设定紧急程度不进入表格数量。",
                "P1/P2/P3 = 紧急程度字段包含对应标签；其他值不计入表格数量",
                null)),
        null);
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    String milestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(milestone)) {
      return milestone + "-客户问题延期问题.xlsx";
    }
    return "客户问题延期问题.xlsx";
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> visible = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> gitlabReadable =
        visible.stream().filter(issue -> !issue.hasGitLabApiError()).toList();
    List<IssueSource> openIssues =
        gitlabReadable.stream().filter(IssueSource::open).toList();
    List<IssueSource> delayed =
        openIssues.stream().filter(issue -> issue.responseDelayed() || issue.resolveDelayed()).toList();
    List<IssueSource> legacyPriorityIssues =
        delayed.stream().filter(IssueSource::hasLegacyPriorityBucket).toList();
    List<IssueSource> rowSources =
        scoped.stream().filter(issue -> matchesFilterGroup(issue, filterGroup)).toList();
    List<IssueSource> filtered =
        legacyPriorityIssues.stream().filter(issue -> matchesFilterGroup(issue, filterGroup)).toList();
    return new RuleFlowSnapshot(
        rowSources,
        filtered,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题事实",
                "加载已同步到平台的客户问题议题数据，并使用整理后的模块、里程碑、紧急程度和延期判定结果。",
                initial.size(),
                initial,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "限定为 CC_Product 自 2026-01-01 以来创建的客户问题。",
                initial.size(),
                scoped,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "exclude-filter",
                "剔除排除数据",
                "客户问题统计不排除建议类问题；仅剔除关闭后属于申请否决、需求如此或设计如此的数据。",
                scoped.size(),
                visible,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "gitlab-api-filter",
                "剔除 GitLab 接口报错",
                "排除非法类型包含 GitLab 接口报错的议题。",
                visible.size(),
                gitlabReadable,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "open-filter",
                "保留 open 议题",
                "延期问题页只统计仍处于 open 状态的客户问题议题。",
                gitlabReadable.size(),
                openIssues,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "delay-filter",
                "保留延期议题",
                "保留响应延期或解决延期命中的议题。",
                openIssues.size(),
                delayed,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "legacy-priority-filter",
                "限定统计紧急程度",
                "只统计紧急程度命中 P1、P2 或 P3 的议题，未设定紧急程度不进入表格数量。",
                delayed.size(),
                legacyPriorityIssues,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "condition-filter",
                "应用页面筛选",
                "应用当前页面条件筛选和顶部查询参数。",
                legacyPriorityIssues.size(),
                filtered,
                this::toRuleFlowSample)));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
        "#" + issue.iid() + " " + issue.projectName(),
        issue.title()
            + " | 模块: "
            + String.join("、", issue.displayModuleNames())
            + " | 紧急程度: "
            + issue.displayPriorityLevel()
            + " | "
            + issue.delayType());
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(parseFilterGroup(filters, buildDefinition()));
    return loadSources(filters, effectiveFilterGroup);
  }

  private List<IssueSource> loadSources(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    CustomerIssueSqlScopeSupport.SqlScope scope =
        CustomerIssueSqlScopeSupport.withExtraPredicate(
            CustomerIssueSqlScopeSupport.boardScope(
                withoutReservedFilters(filters), filterGroup, milestoneCatalogService),
            """
            lower(coalesce(issue_state, 'opened')) <> 'closed'
            and (coalesce(is_response_delayed, false) = true or coalesce(is_resolve_delayed, false) = true)
            and replace(coalesce(illegal_reason, '') || ',' || coalesce(illegal_reasons, ''), ' ', '') not like ?
            """,
            List.of("%" + GITLAB_API_ERROR + "%"));
    try {
      return issueFactQueryService.query(
          FACT_SQL,
          scope.filters(),
          scope.predicate(),
          scope.args(),
          this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue delay facts", error);
      return List.of();
    }
  }

  private List<StatisticFilterOption> loadMilestoneOptions() {
    return milestoneCatalogService.listMilestones().stream()
        .map(value -> new StatisticFilterOption(value, value))
        .toList();
  }

  private List<String> loadLegacyModuleCatalog(StatisticFilterGroup filterGroup) {
    try {
      Set<String> modules = new LinkedHashSet<>();
      issueFactQueryService
          .query(
              MODULE_CATALOG_SQL,
              List.of(LEGACY_CC_PRODUCT_PROJECT_ID),
              (rs, rowNum) -> splitLegacyModuleNames(rs.getString("module_names")))
          .forEach(values -> values.stream()
              .filter(moduleName -> StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, filterGroup))
              .forEach(modules::add));
      return List.copyOf(modules);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue delay module catalog", error);
      return List.of();
    }
  }

  private PersonFilterOptions loadPersonOptions() {
    try {
      List<PersonFilterOptions> rows =
          issueFactQueryService.query(
              """
              select
                (select string_agg(value, E'\n') from (
                   select distinct nullif(btrim(author_name), '') as value
                     from issue_fact
                    where deleted = false and project_id = ?
                 ) t where value is not null) as author_names,
                (select string_agg(value, E'\n') from (
                   select distinct nullif(btrim(assignee_name), '') as value
                     from issue_fact
                    where deleted = false and project_id = ?
                 ) t where value is not null) as assignee_names
              """,
              java.util.List.<Object>of(LEGACY_CC_PRODUCT_PROJECT_ID, LEGACY_CC_PRODUCT_PROJECT_ID),
              (rs, rowNum) ->
                  new PersonFilterOptions(
                      personOptions(rs.getString("author_names")),
                      personOptions(rs.getString("assignee_names"))));
      return rows.isEmpty() ? PersonFilterOptions.empty() : rows.get(0);
    } catch (DataAccessException error) {
      log.debug("Failed to load customer issue person filter options for {}", BOARD_KEY, error);
      return PersonFilterOptions.empty();
    }
  }

  private List<StatisticFilterOption> personOptions(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return List.of();
    }
    return java.util.Arrays.stream(rawValue.split("\\R"))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .map(value -> new StatisticFilterOption(value, value))
        .toList();
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
        StatisticSourceValueSupport.text(rs.getString("priority_level")),
        StatisticSourceValueSupport.text(rs.getString("bug_status")),
        StatisticSourceValueSupport.text(rs.getString("category")),
        StatisticSourceValueSupport.text(rs.getString("milestone_title")),
        StatisticSourceValueSupport.text(rs.getString("author_name")),
        StatisticSourceValueSupport.text(rs.getString("assignee_name")),
        splitLegacyModuleNames(rs.getString("module_names")),
        StatisticSourceValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("is_excluded"),
        rs.getBoolean("delay_issue"),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        StatisticSourceValueSupport.text(rs.getString("illegal_reason")),
        StatisticSourceValueSupport.split(rs.getString("illegal_reasons")),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at_source")));
  }

  private static List<String> splitLegacyModuleNames(String raw) {
    return StatisticSourceValueSupport.split(raw, "\\s*(?:,|，|、|&)\\s*").stream()
        .filter(moduleName -> !moduleName.startsWith("未设定"))
        .toList();
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey)
        || TOTAL_ROW_KEY.equals(rowKey)
        || issue.displayModuleNames().contains(rowKey);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "resp_delay_p1" -> issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P1);
      case "resp_delay_p2" -> issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P2);
      case "resp_delay_p3" -> issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P3);
      case "resp_delay_sum" -> issue -> issue.responseDelayed() && issue.hasLegacyPriorityBucket();
      case "fix_delay_p1" -> issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P1);
      case "fix_delay_p2" -> issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P2);
      case "fix_delay_p3" -> issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P3);
      case "fix_delay_sum" -> issue -> issue.resolveDelayed() && issue.hasLegacyPriorityBucket();
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.displayModuleNames()));
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "state" -> SortSupport.nullableString(IssueSource::issueState);
          case "priorityLevel" -> SortSupport.nullableString(IssueSource::displayPriorityLevel);
          case "delayType" -> SortSupport.nullableString(IssueSource::delayType);
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
          case "milestoneTitle" -> SortSupport.nullableString(IssueSource::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(IssueSource::createdAt);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueSource::assigneeName);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName(), issue.id(), issue.labels());
    record.put("moduleNames", String.join("、", issue.displayModuleNames()));
    record.put("title", issue.title());
    record.put("state", issue.issueState());
    record.put("priorityLevel", issue.displayPriorityLevel());
    record.put("delayType", issue.delayType());
    record.put("bugStatus", issue.bugStatus());
    record.put("milestoneTitle", issue.milestoneTitle());
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    return record;
  }

  private boolean matchesFilterGroup(IssueSource issue, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(issue, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private boolean matchesCondition(IssueSource issue, StatisticFilterCondition condition) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if ("moduleName".equals(condition.fieldKey())) {
      return matchesCandidates(issue.displayModuleNames(), condition);
    }
    String candidate =
        switch (condition.fieldKey()) {
          case "projectName" -> issue.projectName();
          case "milestoneTitle" -> issue.milestoneTitle();
          case "priorityLevel" -> issue.priorityCandidate(condition.operator());
          case "issueState" -> issue.issueState();
          case "authorName" -> issue.authorName();
          case "assigneeName" -> issue.assigneeName();
          default -> "";
        };
    return matchesCandidate(candidate, condition);
  }

  private boolean matchesCandidates(List<String> candidates, StatisticFilterCondition condition) {
    List<String> safeCandidates = candidates == null ? List.of() : candidates;
    String value = trim(condition.value());
    return switch (condition.operator()) {
      case "eq" -> value == null || safeCandidates.stream().anyMatch(candidate -> normalizedEquals(candidate, value));
      case "ne" -> value == null || safeCandidates.stream().noneMatch(candidate -> normalizedEquals(candidate, value));
      case "contains" ->
          value == null || safeCandidates.stream().anyMatch(candidate -> normalize(candidate).contains(normalize(value)));
      case "isEmpty" -> safeCandidates.stream().noneMatch(StringUtils::hasText);
      case "isNotEmpty" -> safeCandidates.stream().anyMatch(StringUtils::hasText);
      default -> true;
    };
  }

  private boolean matchesCandidate(String candidate, StatisticFilterCondition condition) {
    String value = trim(condition.value());
    return switch (condition.operator()) {
      case "eq" -> value == null || normalizedEquals(candidate, value);
      case "ne" -> value == null || !normalizedEquals(candidate, value);
      case "contains" -> value == null || normalize(candidate).contains(normalize(value));
      case "isEmpty" -> !StringUtils.hasText(candidate);
      case "isNotEmpty" -> StringUtils.hasText(candidate);
      default -> true;
    };
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean normalizedEquals(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record AggregateBucket(String rowLabel, String rowKey, List<IssueSource> issues) {
    AggregateBucket(String rowLabel) {
      this(rowLabel, rowLabel, new ArrayList<>());
    }

    AggregateBucket(String rowLabel, String rowKey) {
      this(rowLabel, rowKey, new ArrayList<>());
    }

    AggregateBucket acceptAll(List<IssueSource> sourceIssues) {
      issues.addAll(sourceIssues);
      return this;
    }

    void accept(IssueSource issue) {
      issues.add(issue);
    }

    StatisticRowData toRowData() {
      long respP1 = count(issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P1));
      long respP2 = count(issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P2));
      long respP3 = count(issue -> issue.responseDelayed() && issue.matchesLegacyPriority(P3));
      long respSum = count(issue -> issue.responseDelayed() && issue.hasLegacyPriorityBucket());
      long fixP1 = count(issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P1));
      long fixP2 = count(issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P2));
      long fixP3 = count(issue -> issue.resolveDelayed() && issue.matchesLegacyPriority(P3));
      long fixSum = count(issue -> issue.resolveDelayed() && issue.hasLegacyPriorityBucket());
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              countCell("resp_delay_p1", respP1),
              countCell("resp_delay_p2", respP2),
              countCell("resp_delay_p3", respP3),
              countCell("resp_delay_sum", respSum),
              countCell("fix_delay_p1", fixP1),
              countCell("fix_delay_p2", fixP2),
              countCell("fix_delay_p3", fixP3),
              countCell("fix_delay_sum", fixSum)));
    }

    private long count(Predicate<IssueSource> predicate) {
      return issues.stream().filter(predicate).count();
    }

    private StatisticCellData countCell(String key, long numericValue) {
      return new StatisticCellData(
          key,
          numericValue,
          CustomerIssueDelayIssuesBoardService.count(numericValue),
          true,
          "issue-list",
          Map.of("rowKey", rowKey));
    }
  }

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
      String priorityLevel,
      String bugStatus,
      String category,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      List<String> moduleNames,
      List<String> labels,
      boolean excluded,
      boolean delayIssue,
      boolean responseDelayed,
      boolean resolveDelayed,
      String illegalReason,
      List<String> illegalReasons,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }

    boolean open() {
      return !"closed".equalsIgnoreCase(issueState) && !"CLOSED".equalsIgnoreCase(issueState);
    }

    List<String> displayModuleNames() {
      return moduleNames.isEmpty() ? List.of(EMPTY_MODULE_LABEL) : moduleNames;
    }

    String rawPriorityLevel() {
      return priorityLevel == null ? "" : priorityLevel;
    }

    String priorityCandidate(String operator) {
      if ("isEmpty".equals(operator) || "isNotEmpty".equals(operator)) {
        return rawPriorityLevel();
      }
      return priorityBucket();
    }

    String displayPriorityLevel() {
      return StringUtils.hasText(priorityLevel) ? priorityLevel : "未设定紧急程度";
    }

    String priorityBucket() {
      String normalized = normalize(priorityLevel);
      if (normalized.contains("p1")) {
        return P1;
      }
      if (normalized.contains("p2")) {
        return P2;
      }
      if (normalized.contains("p3")) {
        return P3;
      }
      return "";
    }

    boolean hasLegacyPriorityBucket() {
      return matchesLegacyPriority(P1) || matchesLegacyPriority(P2) || matchesLegacyPriority(P3);
    }

    boolean matchesLegacyPriority(String expectedPriority) {
      return normalize(priorityLevel).contains(normalize(expectedPriority));
    }

    String delayType() {
      if (responseDelayed && resolveDelayed) {
        return "响应延期 / 解决延期";
      }
      if (responseDelayed) {
        return "响应延期";
      }
      if (resolveDelayed) {
        return "解决延期";
      }
      return delayIssue ? "申请延期" : "-";
    }

    boolean hasGitLabApiError() {
      if (matchesGitLabApiError(illegalReason)) {
        return true;
      }
      return illegalReasons != null && illegalReasons.stream().anyMatch(CustomerIssueDelayIssuesBoardService::matchesGitLabApiError);
    }
  }

  private static boolean matchesGitLabApiError(String value) {
    return GITLAB_API_ERROR.equals(normalizeApiError(value));
  }

  private static String normalizeApiError(String value) {
    return value == null ? "" : value.replace(" ", "").trim();
  }

  private record RuleFlowSnapshot(
      List<IssueSource> rowSources,
      List<IssueSource> finalSources,
      List<StatisticRuleFlowStep> flowSteps) {}

  private record PersonFilterOptions(
      List<StatisticFilterOption> authorNames,
      List<StatisticFilterOption> assigneeNames) {
    static PersonFilterOptions empty() {
      return new PersonFilterOptions(List.of(), List.of());
    }
  }
}
