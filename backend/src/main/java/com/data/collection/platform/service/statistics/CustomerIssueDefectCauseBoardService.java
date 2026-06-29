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
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.RealtimeIncrementalRefreshService;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDefectCauseBoardService extends AbstractStatisticBoardService
    implements
        RealtimeStatisticBoardSupport,
        RuleExplainableStatisticBoardSupport,
        StatisticBoardWorkbookExportSupport,
        StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-defect-cause";
  private static final String RULE_VERSION = "customer-issue-defect-cause@2026-06-17-v2";
  private static final String MILESTONE_FIELD = "milestoneTitle";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "共计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private static final List<DefectCauseMetricCatalog.Metric> CAUSE_METRICS =
      DefectCauseMetricCatalog.METRICS;
  private static final String MILESTONE_OPTION_SQL = """
      select issue_id as id, issue_iid as iid, source_instance, title, project_id, project_name,
             coalesce(author_name,'') as author_name, created_at_source as created_at,
             coalesce(assignee_name,'') as assignee_name,
             updated_at_source as updated_at, closed_at_source as closed_at,
             coalesce(milestone_title,'') as milestone_title, coalesce(issue_state,'opened') as issue_state,
             coalesce(bug_status,'') as bug_status,
             coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(reason_category,'') as reason_category,
             coalesce(raw_payload,'') as reason_text,
             coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names,
             coalesce(is_excluded, false) as is_excluded
        from issue_fact
       where deleted = false
      """;
  private static final String FACT_SQL = """
      select issue_id as id, issue_iid as iid, source_instance, title, project_id, project_name,
             coalesce(author_name,'') as author_name, created_at_source as created_at,
             coalesce(assignee_name,'') as assignee_name,
             updated_at_source as updated_at, closed_at_source as closed_at,
             coalesce(milestone_title,'') as milestone_title, coalesce(issue_state,'opened') as issue_state,
             coalesce(bug_status,'') as bug_status,
             coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(reason_category,'') as reason_category,
             coalesce(raw_payload,'') as reason_text,
             coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names,
             coalesce(is_excluded, false) as is_excluded
        from issue_fact
       where deleted = false
      """;
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.customerIssue(
          "标题",
          "模块",
          List.of(
              new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true, "tag"),
              StatisticIssueDetailColumns.state("状态")),
          List.of(StatisticIssueDetailColumns.author("创建人")),
          List.of(StatisticIssueDetailColumns.project("所属项目")));

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueDefectCauseBoardService(
      JsonUtils jsonUtils,
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
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
    return buildDefinition(loadMilestoneOptions());
  }

  private StatisticBoardDefinition buildDefinition(List<StatisticFilterOption> milestoneOptions) {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题缺陷原因分析",
        "基于 issue_fact 的模块维度客户问题缺陷原因分析，按老平台原因模板字段统计。",
        "",
        "",
        "模块",
        List.of(
            StatisticFilterFieldFactory.text(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, "测试阶段", 200),
            StatisticFilterFieldFactory.select(MILESTONE_FIELD, "里程碑", 220, milestoneOptions)),
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
        "当前没有可展示的客户问题缺陷原因分析结果。");
  }

  private StatisticColumnGroup group(String key, String label, List<String> metricKeys) {
    List<StatisticColumnLeaf> columns = metricKeys.stream().map(this::leafByMetricKey).toList();
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
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    List<StatisticFilterOption> milestoneOptions = loadMilestoneOptions();
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    StatisticBoardDefinition definition = buildDefinition(milestoneOptions);
    Map<String, String> snapshotFilters = customerSnapshotFilters(filters, effectiveFilterGroup);
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, effectiveFilterGroup, definition),
        () -> buildBoardResponse(filters, effectiveFilterGroup, definition));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.scopedSources()) {
      for (String moduleName : issue.moduleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        buckets.computeIfAbsent(moduleName, AggregateBucket::new);
      }
    }
    for (IssueSource issue : snapshot.reasonSources()) {
      for (String moduleName : issue.moduleNames()) {
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
    if (!buckets.isEmpty()) {
      AggregateBucket totalBucket = new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY).acceptAll(snapshot.reasonSources());
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
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    List<StatisticFilterOption> milestoneOptions = loadMilestoneOptions();
    StatisticBoardDefinition definition = buildDefinition(milestoneOptions);
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(emptyFilterGroup(), phaseScopeResolver);
    Map<String, String> filters = customerSnapshotFilters(Map.of(), effectiveFilterGroup);
    snapshotService.save(
        snapshotRequest(filters, effectiveFilterGroup, definition),
        buildBoardResponse(filters, effectiveFilterGroup, definition));
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    String selectedPhase = CustomerIssueTestingPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    String selectedMilestone = selectedMilestone(effectiveFilterGroup);
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;testingPhase=" + (StringUtils.hasText(selectedPhase) ? selectedPhase : "none")
            + ";milestone=" + (StringUtils.hasText(selectedMilestone) ? selectedMilestone : "none"),
        filters,
        definition,
        effectiveFilterGroup);
  }

  private Map<String, String> customerSnapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    Map<String, String> payload = new LinkedHashMap<>(withoutReservedFilters(filters));
    payload.put("projectId", "325");
    String selectedPhase = CustomerIssueTestingPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    if (StringUtils.hasText(selectedPhase)) {
      payload.put(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, selectedPhase);
    }
    String selectedMilestone = selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(selectedMilestone)) {
      payload.put(MILESTONE_FIELD, selectedMilestone);
    }
    return payload;
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters()), effectiveFilterGroup).reasonSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题缺陷原因分析明细",
        "展示当前模块与缺陷原因命中的 issue_fact 明细。",
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
    List<StatisticFilterOption> milestoneOptions = loadMilestoneOptions();
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(milestoneOptions));
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    long moduleCount =
        snapshot.scopedSources().stream().flatMap(issue -> issue.moduleNames().stream()).distinct().count();
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题缺陷原因分析规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact.raw_payload 中保留的 GitLab 评论文本，按老平台缺陷原因模板字段匹配原因个数。",
        "统计范围为 CC_Product 自 2026-01-01 以来创建且携带里程碑的客户问题；同一议题关联多个模块或多个缺陷原因时会分别计数。",
        java.util.stream.Stream.concat(
                snapshot.flowSteps().stream(),
                java.util.stream.Stream.of(StatisticRuleFlowSupport.step(
                "group-by-module",
                "按模块聚合",
                "先用当前客户问题范围内的模块全集生成行，再将命中缺陷原因的议题按 module_names 展开并归类聚合。",
                snapshot.reasonSources().size(),
                moduleCount,
                snapshot.reasonSources(),
                this::toRuleFlowSample
            )))
            .toList(),
        CAUSE_METRICS.stream()
            .map(metric -> new StatisticRuleMetricDefinition(
                metric.key(),
                metric.label(),
                "按老平台缺陷原因模板字段匹配：" + String.join(" / ", metric.tokens()),
                metric.label() + "数量 = 当前模块内命中该字段映射的缺陷原因个数",
                null))
            .toList(),
        null);
  }

  @Override
  public byte[] exportBoardWorkbook(Map<String, String> filters) {
    StatisticBoardResponse response = loadBoard(filters);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("客户问题缺陷原因统计表");
      ExportStyles styles = new ExportStyles(workbook);
      writeWorkbookHeader(sheet, styles);
      writeWorkbookRows(sheet, response.rows(), styles);
      sheet.createFreezePane(1, 2);
      sheet.setColumnWidth(0, 22 * 256);
      for (int index = 1; index <= CAUSE_METRICS.size(); index++) {
        sheet.setColumnWidth(index, 18 * 256);
      }
      workbook.write(outputStream);
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("客户问题缺陷原因统计表导出失败", e);
    }
  }

  @Override
  public String exportFilename() {
    return "客户问题缺陷原因统计表.xlsx";
  }

  @Override
  public String exportFilename(Map<String, String> filters) {
    String scope = resolvedPhaseOrMilestoneForExport(filters);
    if (StringUtils.hasText(scope)) {
      return scope + "-客户问题缺陷原因统计表.xlsx";
    }
    return exportFilename();
  }

  private void writeWorkbookHeader(org.apache.poi.ss.usermodel.Sheet sheet, ExportStyles styles) {
    Row groupRow = sheet.createRow(0);
    Row leafRow = sheet.createRow(1);
    createCell(groupRow, 0, "模块", styles.header);
    createCell(leafRow, 0, "模块", styles.header);
    sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));

    int columnIndex = 1;
    String currentGroup = "";
    int groupStart = 1;
    for (int index = 0; index < CAUSE_METRICS.size(); index++) {
      DefectCauseMetricCatalog.Metric metric = CAUSE_METRICS.get(index);
      if (!metric.groupLabel().equals(currentGroup)) {
        if (StringUtils.hasText(currentGroup)) {
          mergeHeaderGroup(sheet, groupRow, groupStart, columnIndex - 1, currentGroup, styles.header);
        }
        currentGroup = metric.groupLabel();
        groupStart = columnIndex;
      }
      createCell(leafRow, columnIndex, metric.label(), styles.header);
      columnIndex++;
    }
    if (StringUtils.hasText(currentGroup)) {
      mergeHeaderGroup(sheet, groupRow, groupStart, columnIndex - 1, currentGroup, styles.header);
    }
  }

  private void mergeHeaderGroup(
      org.apache.poi.ss.usermodel.Sheet sheet,
      Row groupRow,
      int start,
      int end,
      String label,
      CellStyle style) {
    createCell(groupRow, start, label, style);
    for (int column = start + 1; column <= end; column++) {
      createCell(groupRow, column, "", style);
    }
    if (end > start) {
      sheet.addMergedRegion(new CellRangeAddress(0, 0, start, end));
    }
  }

  private void writeWorkbookRows(
      org.apache.poi.ss.usermodel.Sheet sheet,
      List<StatisticRowData> rows,
      ExportStyles styles) {
    int rowIndex = 2;
    for (StatisticRowData rowData : rows) {
      Row row = sheet.createRow(rowIndex++);
      CellStyle style = TOTAL_ROW_KEY.equals(rowData.rowKey()) || "__ratio__".equals(rowData.rowKey())
          ? styles.summary
          : styles.body;
      createCell(row, 0, rowData.rowLabel(), style);
      Map<String, StatisticCellData> cells = new LinkedHashMap<>();
      for (StatisticCellData cell : rowData.cells()) {
        cells.put(cell.columnKey(), cell);
      }
      int columnIndex = 1;
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        StatisticCellData cell = cells.get(metric.key());
        createCell(row, columnIndex++, cell == null ? "" : cell.displayValue(), style);
      }
    }
  }

  private void createCell(Row row, int column, String value, CellStyle style) {
    var cell = row.createCell(column);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream()
            .filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext()))
            .filter(issue -> StringUtils.hasText(issue.milestoneTitle()))
            .toList();
    List<IssueSource> valid = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> milestoneFiltered =
        valid.stream().filter(issue -> matchesMilestone(issue, filterGroup)).toList();
    List<IssueSource> phaseFiltered =
        milestoneFiltered.stream().filter(issue -> matchesTestingPhase(issue, filterGroup)).toList();
    List<IssueSource> withReason = phaseFiltered.stream().filter(IssueSource::hasDefectCause).toList();
    return new RuleFlowSnapshot(
        phaseFiltered,
        withReason,
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
                "按客户问题 scope profile 收口 issue_fact：限定 CC_Product、自 2026-01-01 以来创建且携带里程碑。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "exclude-filter",
                "剔除排除数据",
                "复刻老平台客户问题默认排除口径：剔除申请否决且关闭、需求如此且关闭的数据。",
                scoped.size(),
                valid,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "milestone-filter",
                "应用里程碑筛选",
                "根据页面上的里程碑筛选进一步收敛范围；未选择时按老平台默认使用里程碑列表第一项。",
                valid.size(),
                milestoneFiltered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "testing-phase-filter",
                "应用测试阶段切换",
                "根据页面顶部选择的测试阶段父级收口客户问题里程碑；未选择时保留当前里程碑范围。",
                milestoneFiltered.size(),
                phaseFiltered,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "reason-category-filter",
                "保留已识别原因",
                "只保留评论文本中命中老平台缺陷原因字段的议题，原因个数按字段命中数计算。",
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
    return !StringUtils.hasText(rowKey) || TOTAL_ROW_KEY.equals(rowKey) || issue.moduleNames().contains(rowKey);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    if (!StringUtils.hasText(columnKey) || "__ratio__".equals(columnKey)) {
      return issue -> true;
    }
    return issue -> issue.matchesMetric(columnKey);
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "reasonCategory" -> SortSupport.nullableString(issue -> String.join("、", issue.causeLabels()));
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
    record.put("reasonCategory", String.join("、", issue.causeLabels()));
    record.put("moduleNames", String.join("、", issue.moduleNames()));
    record.put("projectName", issue.projectName());
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    record.put("bugStatus", issue.bugStatus());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("createdAt", issue.createdAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.createdAt()));
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove(MILESTONE_FIELD);
    Long projectId = StatisticSourceValueSupport.parseLong(queryFilters.get("projectId"));
    try {
      List<IssueSource> facts = ensureFactsReady(projectId, queryFilters);
      return facts.isEmpty() ? List.of() : facts;
    } catch (DataAccessException e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult refreshMirrorForRealtimeView() {
    return realtimeIncrementalRefreshService.requestIncrementalRefresh(BOARD_KEY, REALTIME_REFRESH_TABLES);
  }

  private List<IssueSource> ensureFactsReady(Long projectId, Map<String, String> filters) {
    List<IssueSource> facts = loadSourcesFromFact(projectId, filters);
    if (!facts.isEmpty()) {
      return facts;
    }
    log.info("Customer issue defect cause board returned empty result without triggering synchronous rebuild");
    return List.of();
  }

  private List<IssueSource> loadSourcesFromFact(Long projectId, Map<String, String> filters) {
    Map<String, String> mergedFilters = new LinkedHashMap<>();
    if (filters != null) {
      mergedFilters.putAll(filters);
    }
    if (projectId != null) {
      mergedFilters.put("projectId", String.valueOf(projectId));
    }
    return issueFactQueryService.query(FACT_SQL, mergedFilters, this::mapIssueFact);
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        rs.getLong("id"),
        rs.getInt("iid"),
        StatisticSourceValueSupport.text(rs.getString("source_instance"), "default"),
        StatisticSourceValueSupport.text(rs.getString("title"), ""),
        rs.getLong("project_id"),
        StatisticSourceValueSupport.text(rs.getString("project_name"), "未命名项目"),
        StatisticSourceValueSupport.text(rs.getString("milestone_title"), ""),
        StatisticSourceValueSupport.text(rs.getString("author_name"), ""),
        StatisticSourceValueSupport.text(rs.getString("assignee_name"), ""),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at")),
        StatisticSourceValueSupport.text(rs.getString("issue_state"), "opened"),
        StatisticSourceValueSupport.text(rs.getString("bug_status"), ""),
        StatisticSourceValueSupport.text(rs.getString("testing_phase"), ""),
        StatisticSourceValueSupport.text(rs.getString("system_test_label"), ""),
        StatisticSourceValueSupport.text(rs.getString("reason_category"), ""),
        StatisticSourceValueSupport.text(rs.getString("reason_text"), ""),
        StatisticSourceValueSupport.split(rs.getString("module_names")),
        StatisticSourceValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("is_excluded"));
  }

  private List<StatisticFilterOption> loadMilestoneOptions() {
    try {
      return issueFactQueryService.query(MILESTONE_OPTION_SQL, Map.of(), this::mapIssueFact).stream()
          .filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext()))
          .filter(issue -> !issue.excluded())
          .map(IssueSource::milestoneTitle)
          .filter(StringUtils::hasText)
          .distinct()
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .map(value -> new StatisticFilterOption(value, value))
          .toList();
    } catch (DataAccessException e) {
      log.debug("Failed to load milestone options for {}", BOARD_KEY, e);
      return List.of();
    }
  }

  private String selectedMilestone(StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null) {
      return "";
    }
    return filterGroup.conditions().stream()
        .filter(condition -> MILESTONE_FIELD.equals(condition.fieldKey()))
        .filter(condition -> "eq".equals(condition.operator()))
        .map(StatisticFilterCondition::value)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse("");
  }

  private String resolvedPhaseOrMilestoneForExport(Map<String, String> filters) {
    List<StatisticFilterOption> milestoneOptions = loadMilestoneOptions();
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(milestoneOptions));
    StatisticFilterGroup effectiveFilterGroup =
        CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
    String milestone = selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(milestone)) {
      return milestone;
    }
    return CustomerIssueTestingPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
  }

  private boolean matchesMilestone(IssueSource issue, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    List<StatisticFilterCondition> milestoneConditions =
        filterGroup.conditions().stream()
            .filter(condition -> MILESTONE_FIELD.equals(condition.fieldKey()))
            .filter(condition -> StringUtils.hasText(condition.value()))
            .toList();
    if (milestoneConditions.isEmpty()) {
      return true;
    }
    boolean useOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : milestoneConditions) {
      boolean matched = matchesMilestoneCondition(issue, condition);
      if (useOr && matched) {
        return true;
      }
      if (!useOr && !matched) {
        return false;
      }
    }
    return !useOr;
  }

  private boolean matchesMilestoneCondition(IssueSource issue, StatisticFilterCondition condition) {
    return switch (condition.operator()) {
      case "ne" -> !condition.value().equals(issue.milestoneTitle());
      case "eq" -> condition.value().equals(issue.milestoneTitle());
      default -> true;
    };
  }

  private boolean matchesTestingPhase(IssueSource issue, StatisticFilterGroup filterGroup) {
    return CustomerIssueTestingPhaseFilterSupport.matches(
        issue.milestoneTitle(), issue.testingPhase(), filterGroup, phaseScopeResolver);
  }

  private Map<String, String> appliedFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    Map<String, String> applied = new LinkedHashMap<>(withoutReservedFilters(filters));
    String milestone = selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(milestone)) {
      applied.put(MILESTONE_FIELD, milestone);
    }
    return applied;
  }

  private DefectCauseMetricCatalog.Metric metric(String key) {
    return DefectCauseMetricCatalog.get(key);
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
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
      List<StatisticCellData> cells = new ArrayList<>();
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        cells.add(cell(metric.key(), countByMetric(metric.key()), true));
      }
      return new StatisticRowData(rowKey, rowLabel, cells);
    }

    private long countByMetric(String metricKey) {
      return issues.stream().filter(issue -> issue.matchesMetric(metricKey)).count();
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
      return new StatisticCellData(
          key,
          numericValue,
          count(numericValue),
          drilldown && numericValue > 0,
          drilldown && numericValue > 0 ? "issue-list" : null,
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
      String assigneeName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt,
      String issueState,
      String bugStatus,
      String testingPhase,
      String systemTestLabel,
      String reasonCategory,
      String reasonText,
      List<String> moduleNames,
      List<String> labels,
      boolean excluded) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
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
      return matchedMetricKeys().contains(metricKey);
    }

    List<String> causeLabels() {
      return CAUSE_METRICS.stream()
          .filter(metric -> matchedMetricKeys().contains(metric.key()))
          .map(DefectCauseMetricCatalog.Metric::label)
          .toList();
    }

    private Set<String> matchedMetricKeys() {
      Set<String> matched = new LinkedHashSet<>();
      String text = DefectCauseMetricCatalog.latestReasonText(reasonText);
      if (!StringUtils.hasText(text)) {
        text = reasonCategory;
      }
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        if (DefectCauseMetricCatalog.containsAny(text, metric.tokens())) {
          matched.add(metric.key());
        }
      }
      return matched;
    }

    private boolean contains(String source, String token) {
      return StringUtils.hasText(source) && source.contains(token);
    }
  }

  private record RuleFlowSnapshot(
      List<IssueSource> scopedSources,
      List<IssueSource> reasonSources,
      List<StatisticRuleFlowStep> flowSteps) {}

  private static final class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;
    private final CellStyle summary;

    private ExportStyles(Workbook workbook) {
      header = workbook.createCellStyle();
      header.setAlignment(HorizontalAlignment.CENTER);
      header.setVerticalAlignment(VerticalAlignment.CENTER);
      header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      setBorders(header);

      body = workbook.createCellStyle();
      body.setAlignment(HorizontalAlignment.CENTER);
      body.setVerticalAlignment(VerticalAlignment.CENTER);
      setBorders(body);

      summary = workbook.createCellStyle();
      summary.setAlignment(HorizontalAlignment.CENTER);
      summary.setVerticalAlignment(VerticalAlignment.CENTER);
      summary.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
      summary.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      setBorders(summary);
    }

    private void setBorders(CellStyle style) {
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
    }
  }
}
