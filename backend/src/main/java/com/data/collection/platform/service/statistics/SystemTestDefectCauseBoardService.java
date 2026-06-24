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
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseFilterGroupExpander;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public class SystemTestDefectCauseBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport, StatisticBoardWorkbookExportSupport {
  private static final String BOARD_KEY = "system-test-defect-cause";
  private static final String RULE_VERSION = "system-test-defect-cause@2026-04-22-v1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "共计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
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

  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final FactBuildService factBuildService;
  private final IssueFactQueryService issueFactQueryService;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;

  public SystemTestDefectCauseBoardService(
      JsonUtils jsonUtils,
      GitlabMirrorSyncService gitlabMirrorSyncService,
      RealtimeWorkspaceService realtimeWorkspaceService,
      FactBuildService factBuildService,
      IssueFactQueryService issueFactQueryService,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseCatalogService phaseCatalogService,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    super(jsonUtils);
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.factBuildService = factBuildService;
    this.issueFactQueryService = issueFactQueryService;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseCatalogService = phaseCatalogService;
    this.phaseScopeResolver = phaseScopeResolver;
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
        "基于 issue_fact 的模块维度缺陷原因分析。",
        "",
        "",
        "模块",
        List.of(StatisticFilterFieldFactory.select("testingPhase", "测试阶段", 220, phaseOptions)),
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
    long startedAt = System.currentTimeMillis();
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    StatisticBoardDefinition definition = buildDefinition(phaseOptions);

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.scopedSources()) {
      for (String moduleName : issue.moduleNames()) {
        buckets.computeIfAbsent(moduleName, AggregateBucket::new);
      }
    }
    for (IssueSource issue : snapshot.reasonSources()) {
      for (String moduleName : issue.moduleNames()) {
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
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, loadPhaseOptions());
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters()), effectiveFilterGroup).reasonSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "缺陷原因分析明细",
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
    return realtimeWorkspaceService.requestRefresh(BOARD_KEY, this::refreshMirrorForRealtimeView);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    List<StatisticFilterOption> phaseOptions = loadPhaseOptions();
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition(phaseOptions));
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultTestingPhase(filterGroup, phaseOptions);
    effectiveFilterGroup = SystemTestPhaseFilterGroupExpander.expand(effectiveFilterGroup, phaseScopeResolver);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
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
        "当前统计优先使用 issue_fact.reason_category 已解析事实字段，必要时再回退 GitLab 评论原文解析，按老平台缺陷原因模板字段匹配原因个数。",
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
                "先用当前系统测试范围内的模块全集生成行，再将命中缺陷原因的议题按 module_names 展开并归类聚合。",
                snapshot.reasonSources().size(),
                moduleCount,
                snapshot.reasonSources(),
                this::toRuleFlowSample
            )),
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
      var sheet = workbook.createSheet("缺陷原因统计表");
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
      throw new IllegalStateException("缺陷原因统计表导出失败", e);
    }
  }

  @Override
  public String exportFilename() {
    return "缺陷原因统计表.xlsx";
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
      CellStyle style = "__total__".equals(rowData.rowKey()) || "__ratio__".equals(rowData.rowKey())
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

  private RuleFlowSnapshot buildRuleFlowSnapshot(
      List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial.stream().filter(IssueSource::inSystemTestScope).toList();
    List<IssueSource> valid = scoped.stream().filter(issue -> !issue.excluded()).toList();
      List<IssueSource> phaseFiltered =
        valid.stream().filter(issue -> SystemTestPhaseFilterSupport.matches(issue, filterGroup, phaseScopeResolver)).toList();
    List<IssueSource> withReason =
        phaseFiltered.stream().filter(IssueSource::hasDefectCause).toList();
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
    issueLinkSupport.putIssueFields(record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName());
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

  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.remove("testingPhase");
    Long projectId = StatisticSourceValueSupport.parseLong(queryFilters.get("projectId"));
    try {
      List<IssueSource> facts = ensureFactsReady(projectId, queryFilters);
      return facts.isEmpty() ? List.of() : facts;
    } catch (DataAccessException e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private void refreshMirrorForRealtimeView() {
    try {
      gitlabMirrorSyncService.refreshTablesOnDemand(REALTIME_REFRESH_TABLES, BOARD_KEY);
      factBuildService.rebuildIssueFacts(false);
    } catch (Exception e) {
      log.warn("On-demand mirror refresh for {} failed", BOARD_KEY, e);
    }
  }

  private List<IssueSource> ensureFactsReady(Long projectId, Map<String, String> filters) {
    List<IssueSource> facts = loadSourcesFromFact(projectId, filters);
    if (!facts.isEmpty()) {
      return facts;
    }
    log.info("System test defect cause board returned empty result without triggering synchronous rebuild");
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
    String selectedTestingPhase = SystemTestPhaseFilterSupport.selectedTestingPhase(effectiveFilterGroup);
    if (StringUtils.hasText(selectedTestingPhase)) {
      applied.put("testingPhase", selectedTestingPhase);
    }
    return applied;
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
      return new StatisticRowData(
          rowKey,
          rowLabel,
          cells);
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
        cells.add(new StatisticCellData(metric.key(), numerator, display, false, null, Map.of("rowKey", "__ratio__")));
      }
      return new StatisticRowData("__ratio__", "比例", cells);
    }

    private StatisticCellData cell(String key, long numericValue, boolean drilldown) {
      Map<String, String> detailParams = new LinkedHashMap<>();
      detailParams.put("rowKey", rowKey);
      detailParams.put("level1", count(countByMetricAndSeverity(key, "LEVEL1")));
      detailParams.put("level2", count(countByMetricAndSeverity(key, "LEVEL2")));
      detailParams.put("level3", count(countByMetricAndSeverity(key, "LEVEL3")));
      detailParams.put("suggestion", count(issues.stream().filter(issue -> issue.matchesMetric(key) && issue.isSuggestion()).count()));
      return new StatisticCellData(
          key,
          numericValue,
          count(numericValue),
          drilldown && numericValue > 0,
          drilldown && numericValue > 0 ? "issue-list" : null,
          detailParams);
    }

    private long countByMetricAndSeverity(String metricKey, String severity) {
      return issues.stream()
          .filter(issue -> issue.matchesMetric(metricKey))
          .filter(issue -> issue.isSeverity(severity))
          .count();
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
      String text = reasonCategory;
      if (!StringUtils.hasText(text)) {
        text = DefectCauseMetricCatalog.latestReasonText(reasonText);
      }
      for (DefectCauseMetricCatalog.Metric metric : CAUSE_METRICS) {
        if (DefectCauseMetricCatalog.containsAny(text, metric.tokens())) {
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
