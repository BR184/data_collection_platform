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
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
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
  private static final String RULE_VERSION = "customer-issue-by-function@2026-06-26-v2";
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final String EMPTY_MODULE_LABEL = IssueDisplayValueSupport.EMPTY_MODULE_LABEL;
  private static final String ROW_KEY_SEPARATOR = "||";
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
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.customerIssue(
          "标题",
          "模块",
          List.of(
              new StatisticDetailColumn("functionName", "功能", 180, 180, true, "tag"),
              StatisticIssueDetailColumns.severity("severityLevel", "严重程度", 140),
              new StatisticDetailColumn("priorityLevel", "优先级", 120, 120, true, "tag"),
              StatisticIssueDetailColumns.state("状态"),
              new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true, "tag")),
          List.of(),
          List.of(StatisticIssueDetailColumns.project("所属项目")));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueByFunctionBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseScopeResolver = phaseScopeResolver;
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
        "基于 issue_fact.function_name 的客户问题模块/功能维度缺陷数量统计。",
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
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    Map<String, String> snapshotFilters = customerSnapshotFilters(filters, effectiveFilterGroup);
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, effectiveFilterGroup, buildDefinition()),
        () -> buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters, StatisticFilterGroup effectiveFilterGroup) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    Map<String, List<AggregateBucket>> bucketsByModule = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.finalSources()) {
      for (String moduleName : issue.displayModuleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        String rowKey = rowKey(moduleName, issue.functionName());
        String rowLabel = moduleName + " / " + issue.functionName();
        bucketsByModule
            .computeIfAbsent(moduleName, ignored -> new ArrayList<>())
            .stream()
            .filter(bucket -> bucket.rowKey().equals(rowKey))
            .findFirst()
            .orElseGet(() -> {
              AggregateBucket bucket = new AggregateBucket(rowLabel, rowKey);
              bucketsByModule.get(moduleName).add(bucket);
              return bucket;
            })
            .accept(issue);
      }
    }
    bucketsByModule.replaceAll((moduleName, buckets) ->
        buckets.stream()
            .sorted(
                Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(bucket -> bucket.issues().size(), Comparator.reverseOrder()))
            .toList());
    StatisticBoardDefinition definition = buildDefinition(bucketsByModule.keySet().stream().toList());
    List<StatisticRowData> rows = toLegacyPivotRows(bucketsByModule);
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
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(emptyFilterGroup(), phaseScopeResolver);
    Map<String, String> filters = customerSnapshotFilters(Map.of(), effectiveFilterGroup);
    snapshotService.save(
        snapshotRequest(filters, effectiveFilterGroup, buildDefinition()),
        buildBoardResponse(filters, effectiveFilterGroup));
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters()), effectiveFilterGroup).finalSources().stream()
            .filter(issue -> matchesDetailRequest(issue, request))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题功能缺陷明细",
        "展示当前模块/功能与指标命中的 issue_fact 明细。",
        DETAIL_COLUMNS,
        pageSlice.records().stream().map(this::toDetailRecord).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending");
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot =
        buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题按功能展示缺陷数量规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact，先使用 CustomerIssueScopeProfile 限定客户问题范围，再保留已识别出功能名的议题。",
        "功能名来自 issue 标题开头的全角书名号片段，例如【草图约束】；同一条议题如属于多个模块，会分别计入对应模块/功能行，总计行按议题本身统计。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "legacy-pivot", "模块功能列", "主表按老平台 IssueShowByFunction.vue 展示：每个模块是一个列组，下面固定“功能”和“问题数量”两列。", "问题数量 = count(CC_Product issue_fact where module_name and function_name match)", null)),
        null);
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
    String selectedPhase = CustomerIssueTestingPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;testingPhase=" + (StringUtils.hasText(selectedPhase) ? selectedPhase : "none"),
        filters,
        definition,
        effectiveFilterGroup);
  }

  private Map<String, String> customerSnapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    payload.put("projectId", String.valueOf(LEGACY_CC_PRODUCT_PROJECT_ID));
    String selectedPhase = CustomerIssueTestingPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    if (StringUtils.hasText(selectedPhase)) {
      payload.put(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, selectedPhase);
    }
    return payload;
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
                "从 issue_fact 读取已归一化的议题事实。",
                initial.size(),
                initial,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "复用 CustomerIssueScopeProfile 收口客户问题范围。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "exclude-filter",
                "剔除排除数据",
                "按客户问题公共排除规则剔除 issue_fact.is_excluded = true 的议题。",
                scoped.size(),
                visible,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "testing-phase-filter",
                "应用测试阶段切换",
                "根据页面顶部选择的测试阶段父级收口客户问题里程碑；未选择时按老平台默认使用阶段列表第一项。",
                visible.size(),
                phaseFiltered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "function-filter",
                "保留已识别功能",
                "只保留 issue_fact.function_name 非空的议题。",
                phaseFiltered.size(),
                withFunction,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "module-function-expand",
                "按模块/功能展开",
                "将客户问题议题展开到 module_names + function_name 组合；未设定模块的议题归入“未设定模块”。",
                withFunction.size(),
                withFunction.stream().mapToLong(issue -> issue.displayModuleNames().size()).sum(),
                withFunction,
                this::toRuleFlowSample
            )));
  }

  private boolean matchesTestingPhase(IssueSource issue, StatisticFilterGroup filterGroup) {
    return CustomerIssueTestingPhaseFilterSupport.matches(
        issue.milestoneTitle(), issue.testingPhase(), filterGroup, phaseScopeResolver);
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 功能: " + issue.functionName());
  }
  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.putIfAbsent("projectId", String.valueOf(LEGACY_CC_PRODUCT_PROJECT_ID));
    try {
      return issueFactQueryService.query(FACT_SQL, queryFilters, this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue by function facts", error);
      return List.of();
    }
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
        StatisticSourceValueSupport.split(rs.getString("module_names")),
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

  private boolean matchesDetailRequest(IssueSource issue, StatisticDetailRequest request) {
    CellAddress address = parseCellAddress(request.rowKey(), request.columnKey(), request.filters());
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
          case "reasonCategory" -> SortSupport.nullableString(IssueSource::reasonCategory);
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
    record.put("reasonCategory", StringUtils.hasText(issue.reasonCategory()) ? issue.reasonCategory() : "未归因");
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    return record;
  }

  private String rowKey(String moduleName, String functionName) {
    return moduleName + ROW_KEY_SEPARATOR + functionName;
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
        long countValue = bucket == null ? 0 : bucket.issues().size();
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
            countValue > 0 ? Map.of("rowKey", rowKey) : Map.of()));
      }
      rows.add(new StatisticRowData(String.valueOf(rowIndex + 1), String.valueOf(rowIndex + 1), cells));
    }
    return rows;
  }

  private CellAddress parseCellAddress(String rowKey, String columnKey, Map<String, String> filters) {
    String moduleName = moduleNameFromCountColumnKey(columnKey);
    Integer rowIndex = parseOneBasedIndex(rowKey);
    if (!StringUtils.hasText(moduleName) || rowIndex == null) {
      return null;
    }
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    List<AggregateBucket> buckets = bucketsForModule(loadSources(filters), effectiveFilterGroup, moduleName);
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

  private String functionNameFromRowKey(String rowKey) {
    if (!StringUtils.hasText(rowKey)) {
      return "";
    }
    int separatorIndex = rowKey.indexOf(ROW_KEY_SEPARATOR);
    return separatorIndex < 0 ? "" : rowKey.substring(separatorIndex + ROW_KEY_SEPARATOR.length());
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

  private record AggregateBucket(String rowLabel, String rowKey, List<IssueSource> issues) {
    AggregateBucket(String rowLabel, String rowKey) {
      this(rowLabel, rowKey, new ArrayList<>());
    }

    void accept(IssueSource issue) {
      issues.add(issue);
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
