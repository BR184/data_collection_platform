package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.SystemTestIssueMultiBoardResponse;
import com.data.collection.platform.service.ExcelExportStyles;
import com.data.collection.platform.service.OptionItemResponseFactory;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.TextQuerySupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIssueMultiBoardService {
  private static final long DEFAULT_PROJECT_ID = SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID;
  private static final List<Severity> SEVERITIES =
      List.of(
          new Severity("level1", "一级缺陷"),
          new Severity("level2", "二级缺陷"),
          new Severity("level3", "三级缺陷"),
          new Severity("suggestion", "建议类缺陷"));
  private static final List<Severity> REGULAR_SEVERITIES = SEVERITIES.subList(0, 3);
  private static final List<String> MAJOR_CAUSES =
      List.of("需求阶段", "设计阶段", "编码问题", "打包问题", "依赖问题", "精度问题");
  private static final List<String> DELAY_CAUSES =
      List.of("技术卡点", "方案卡点", "资源卡点", "数据异常", "算法问题", "机制问题", "计算效率");

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;

  public SystemTestIssueMultiBoardService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseCatalogService phaseCatalogService,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseCatalogService = phaseCatalogService;
    this.phaseScopeResolver = phaseScopeResolver;
  }

  public SystemTestIssueMultiBoardResponse getBoard(Long projectId, String testingPhase) {
    ScopeContext scope = resolveScope(projectId, testingPhase);
    List<IssueRow> rows = loadRows(scope);
    List<SystemTestIssueMultiBoardResponse.Chart> charts = buildCharts(rows, scope);
    return new SystemTestIssueMultiBoardResponse(
        new SystemTestIssueMultiBoardResponse.Scope(
            scope.projectId(),
            scope.projectName(),
            scope.testingPhase(),
            scope.expandedTestingPhases(),
            scope.label()),
        loadProjectOptions(),
        loadTestingPhaseOptions(scope.projectId()),
        buildSummaryCards(rows),
        charts);
  }

  public byte[] exportChart(Long projectId, String testingPhase, String chartKey) {
    ScopeContext scope = resolveScope(projectId, testingPhase);
    SystemTestIssueMultiBoardResponse.Chart chart = buildCharts(loadRows(scope), scope).stream()
        .filter(item -> item.key().equals(chartKey))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知的议题多元看板图表：" + chartKey));
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(safeSheetName(chart.title()));
      CellStyle header = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle body = ExcelExportStyles.createBodyStyle(workbook);
      writeChartSheet(sheet, header, body, chart);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("生成议题多元看板导出失败", error);
    }
  }

  public String exportFilename(Long projectId, String testingPhase, String chartKey) {
    ScopeContext scope = resolveScope(projectId, testingPhase);
    String title = CHART_DEFINITIONS.stream()
        .filter(definition -> definition.key().equals(chartKey))
        .findFirst()
        .map(ChartDefinition::title)
        .orElse("议题多元看板");
    String phasePrefix = TextQuerySupport.trimToNull(scope.testingPhase()) == null ? "全部阶段" : scope.testingPhase();
    return sanitizeFilename(scope.projectName() + "-" + phasePrefix + "-" + title + ".xlsx");
  }

  private List<SystemTestIssueMultiBoardResponse.SummaryCard> buildSummaryCards(List<IssueRow> rows) {
    List<IssueRow> regularRows = rows.stream().filter(IssueRow::isRegularMetricIssue).toList();
    long total = regularRows.size();
    long open = regularRows.stream().filter(IssueRow::open).count();
    long fixed = regularRows.stream().filter(IssueRow::fixed).count();
    long delay = regularRows.stream().filter(IssueRow::delay).count();
    return List.of(
        new SystemTestIssueMultiBoardResponse.SummaryCard("total", "系统测试缺陷", String.valueOf(total), "default"),
        new SystemTestIssueMultiBoardResponse.SummaryCard("open", "未关闭缺陷", String.valueOf(open), "warning"),
        new SystemTestIssueMultiBoardResponse.SummaryCard("fixed", "已修复/未更新", String.valueOf(fixed), "success"),
        new SystemTestIssueMultiBoardResponse.SummaryCard("delay", "申请延期", String.valueOf(delay), "danger"));
  }

  private List<SystemTestIssueMultiBoardResponse.Chart> buildCharts(List<IssueRow> rows, ScopeContext scope) {
    List<IssueRow> regularRows = rows.stream().filter(IssueRow::isRegularMetricIssue).toList();
    return List.of(
        severityPie(rows, scope),
        phaseSeverity(rows, scope),
        moduleSeverity(rows, scope),
        majorCausePie(regularRows, scope),
        causeDetail(regularRows, scope),
        moduleRepairRate(regularRows, scope),
        openSeverityPie(regularRows, scope),
        fixUserSeverity(rows, scope),
        extensionModulePie(regularRows, scope),
        delayCause(rows, scope),
        rollbackModulePie(regularRows, scope));
  }

  private SystemTestIssueMultiBoardResponse.Chart severityPie(List<IssueRow> rows, ScopeContext scope) {
    List<SystemTestIssueMultiBoardResponse.Point> points = SEVERITIES.stream()
        .map(severity -> point(severity.label(), count(rows, row -> severity.matches(row))))
        .toList();
    return pointChart("severity-level", "缺陷严重程度分析", "按议题严重程度统计当前范围缺陷数量。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart openSeverityPie(List<IssueRow> rows, ScopeContext scope) {
    List<IssueRow> openRows = rows.stream().filter(IssueRow::open).toList();
    List<SystemTestIssueMultiBoardResponse.Point> points = REGULAR_SEVERITIES.stream()
        .map(severity -> point(severity.label(), count(openRows, row -> severity.matches(row))))
        .toList();
    return pointChart("open-issue", "未关闭缺陷占比", "按严重程度统计当前范围内未关闭缺陷。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart majorCausePie(List<IssueRow> rows, ScopeContext scope) {
    List<SystemTestIssueMultiBoardResponse.Point> points = MAJOR_CAUSES.stream()
        .map(cause -> point(cause, count(rows, row -> majorCause(row).equals(cause))))
        .toList();
    return pointChart("major-cause", "缺陷原因占比分析", "按老平台六类缺陷原因统计占比。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart extensionModulePie(List<IssueRow> rows, ScopeContext scope) {
    Map<String, Long> totals = new LinkedHashMap<>();
    rows.stream().filter(IssueRow::delay).forEach(row -> modules(row).forEach(module -> totals.merge(module, 1L, Long::sum)));
    return pointChart(
        "extension-module",
        "申请延期模块分析",
        "按模块统计申请延期缺陷数量。",
        "pie",
        topPoints(totals, 12),
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart rollbackModulePie(List<IssueRow> rows, ScopeContext scope) {
    Map<String, Long> totals = new LinkedHashMap<>();
    rows.stream().filter(IssueRow::rollback).forEach(row -> modules(row).forEach(module -> totals.merge(module, 1L, Long::sum)));
    return pointChart(
        "rollback-module",
        "回退模块缺陷占比",
        "按模块统计回退类缺陷数量。",
        "pie",
        topPoints(totals, 12),
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart phaseSeverity(List<IssueRow> rows, ScopeContext scope) {
    List<String> phases = orderedPhases(rows, scope);
    return seriesChart(
        "phase-severity",
        "缺陷阶段分析",
        "按测试阶段统计各严重程度缺陷数量。",
        "stackedBar",
        phases,
        severitySeries(phases, rows, IssueRow::testingPhase),
        "/question-metrics/phase-statistics",
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart moduleSeverity(List<IssueRow> rows, ScopeContext scope) {
    List<String> modules = topModules(rows, 12);
    return seriesChart(
        "module-severity",
        "缺陷模块分析",
        "按模块统计各严重程度缺陷数量。",
        "stackedBar",
        modules,
        severitySeriesByModules(modules, rows),
        "/question-metrics/home",
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart fixUserSeverity(List<IssueRow> rows, ScopeContext scope) {
    List<String> users = rows.stream()
        .map(IssueRow::fixUser)
        .filter(StringUtils::hasText)
        .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
        .entrySet()
        .stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
        .limit(12)
        .map(Map.Entry::getKey)
        .toList();
    return seriesChart(
        "fix-user-severity",
        "修复人-缺陷数量统计",
        "按合法修复状态评论作者统计缺陷数量。",
        "stackedBar",
        users,
        severitySeries(users, rows.stream().filter(row -> StringUtils.hasText(row.fixUser())).toList(), IssueRow::fixUser),
        "/question-metrics/issues",
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart causeDetail(List<IssueRow> rows, ScopeContext scope) {
    List<String> categories = DefectCauseMetricCatalog.METRICS.stream().map(DefectCauseMetricCatalog.Metric::label).toList();
    List<SystemTestIssueMultiBoardResponse.Series> series = REGULAR_SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            DefectCauseMetricCatalog.METRICS.stream()
                .map(metric -> decimal(count(rows, row -> severity.matches(row) && matchesCauseMetric(row, metric))))
                .toList()))
        .toList();
    return seriesChart(
        "cause-detail",
        "缺陷原因分析",
        "按缺陷原因明细拆分严重程度。",
        "stackedBar",
        categories,
        series,
        "/question-metrics/defect-cause",
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart delayCause(List<IssueRow> rows, ScopeContext scope) {
    List<IssueRow> delayRows = rows.stream().filter(IssueRow::delay).toList();
    List<SystemTestIssueMultiBoardResponse.Series> series = SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            DELAY_CAUSES.stream()
                .map(cause -> decimal(count(delayRows, row -> severity.matches(row) && delayCause(row).equals(cause))))
                .toList()))
        .toList();
    return seriesChart(
        "delay-cause",
        "申请延期缺陷原因分析",
        "按延期原因拆分各严重程度缺陷数量。",
        "stackedBar",
        DELAY_CAUSES,
        series,
        "/question-metrics/delay-analysis",
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart moduleRepairRate(List<IssueRow> rows, ScopeContext scope) {
    List<String> modules = topModules(rows, 12);
    List<BigDecimal> data = modules.stream()
        .map(module -> {
          long total = rows.stream().filter(row -> modules(row).contains(module)).count();
          long fixed = rows.stream().filter(row -> row.fixed() && modules(row).contains(module)).count();
          return rate(fixed, total);
        })
        .toList();
    return seriesChart(
        "module-repair-rate",
        "模块修复率",
        "按模块统计已修复缺陷占比，保留两位小数。",
        "bar",
        modules,
        List.of(new SystemTestIssueMultiBoardResponse.Series("修复率(%)", data)),
        "/question-metrics/home",
        scope);
  }

  private List<SystemTestIssueMultiBoardResponse.Series> severitySeries(
      List<String> categories,
      List<IssueRow> rows,
      Function<IssueRow, String> classifier) {
    return SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            categories.stream()
                .map(category -> decimal(count(rows, row -> severity.matches(row) && category.equals(classifier.apply(row)))))
                .toList()))
        .toList();
  }

  private List<SystemTestIssueMultiBoardResponse.Series> severitySeriesByModules(List<String> categories, List<IssueRow> rows) {
    return SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            categories.stream()
                .map(category -> decimal(count(rows, row -> severity.matches(row) && modules(row).contains(category))))
                .toList()))
        .toList();
  }

  private SystemTestIssueMultiBoardResponse.Chart pointChart(
      String key,
      String title,
      String description,
      String chartType,
      List<SystemTestIssueMultiBoardResponse.Point> points,
      ScopeContext scope) {
    ChartDefinition definition = definition(key);
    return new SystemTestIssueMultiBoardResponse.Chart(
        key,
        title,
        description,
        chartType,
        definition.detailPath(),
        title,
        List.of(),
        List.of(),
        points.stream().filter(point -> point.value().compareTo(BigDecimal.ZERO) > 0).toList(),
        Map.of("scope", scope.label()));
  }

  private SystemTestIssueMultiBoardResponse.Chart seriesChart(
      String key,
      String title,
      String description,
      String chartType,
      List<String> categories,
      List<SystemTestIssueMultiBoardResponse.Series> series,
      String detailPath,
      ScopeContext scope) {
    return new SystemTestIssueMultiBoardResponse.Chart(
        key,
        title,
        description,
        chartType,
        detailPath,
        title,
        categories,
        series,
        List.of(),
        Map.of("scope", scope.label()));
  }

  private ScopeContext resolveScope(Long projectId, String testingPhase) {
    long normalizedProjectId = projectId == null ? DEFAULT_PROJECT_ID : projectId;
    String projectName = loadProjectName(normalizedProjectId);
    String normalizedPhase = TextQuerySupport.trimToNull(testingPhase);
    if (normalizedPhase == null) {
      normalizedPhase = phaseCatalogService.listParentNames(normalizedProjectId).stream().findFirst().orElse(null);
    }
    List<String> expanded = normalizedPhase == null ? List.of() : phaseScopeResolver.resolvePhases(normalizedProjectId, normalizedPhase);
    if (normalizedPhase != null && expanded.isEmpty()) {
      expanded = List.of(normalizedPhase);
    }
    String label = projectName + " / " + (normalizedPhase == null ? "全部阶段" : normalizedPhase);
    return new ScopeContext(normalizedProjectId, projectName, normalizedPhase, expanded, label);
  }

  private List<IssueRow> loadRows(ScopeContext scope) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder(
        """
        select project_id,
               coalesce(project_name, '') as project_name,
               issue_id,
               issue_iid,
               coalesce(title, '') as title,
               coalesce(issue_state, '') as issue_state,
               coalesce(testing_phase, '') as testing_phase,
               coalesce(severity_level, '') as severity_level,
               coalesce(bug_status, '') as bug_status,
               coalesce(category, '') as category,
               coalesce(reason_category, '') as reason_category,
               coalesce(delay_cause, '') as delay_cause,
               coalesce(delay_reason, '') as delay_reason,
               coalesce(module_names, '') as module_names,
               coalesce(fix_user, '') as fix_user,
               coalesce(label_names, '') as label_names,
               coalesce(is_excluded, false) as is_excluded,
               coalesce(exclusion_reason, '') as exclusion_reason,
               coalesce(is_fixed, false) as is_fixed,
               coalesce(delay_issue, false) as delay_issue,
               coalesce(is_regression, false) as is_regression
          from issue_fact
         where deleted = false
           and project_id = ?
           and ((%s) or (%s))
        """.formatted(
            SystemTestSuggestionMetricSupport.regularMetricSql(null),
            SystemTestSuggestionMetricSupport.suggestionMetricSql(null)));
    args.add(scope.projectId());
    if (!scope.expandedTestingPhases().isEmpty()) {
      sql.append(" and testing_phase in (");
      sql.append(scope.expandedTestingPhases().stream().map(ignored -> "?").collect(Collectors.joining(", ")));
      sql.append(")");
      args.addAll(scope.expandedTestingPhases());
    }
    sql.append(" order by issue_iid asc");
    return jdbcTemplate.query(sql.toString(), this::mapIssueRow, args.toArray());
  }

  private IssueRow mapIssueRow(ResultSet rs, int rowNum) throws SQLException {
    return new IssueRow(
        rs.getLong("project_id"),
        rs.getString("project_name"),
        rs.getLong("issue_id"),
        rs.getLong("issue_iid"),
        rs.getString("title"),
        rs.getString("issue_state"),
        rs.getString("testing_phase"),
        rs.getString("severity_level"),
        rs.getString("bug_status"),
        rs.getString("category"),
        rs.getString("reason_category"),
        rs.getString("delay_cause"),
        rs.getString("delay_reason"),
        rs.getString("module_names"),
        rs.getString("fix_user"),
        rs.getString("label_names"),
        rs.getBoolean("is_excluded"),
        rs.getString("exclusion_reason"),
        rs.getBoolean("is_fixed"),
        rs.getBoolean("delay_issue"),
        rs.getBoolean("is_regression"));
  }

  private List<OptionItemResponse> loadProjectOptions() {
    return jdbcTemplate.query(
        """
        select project_id, max(coalesce(project_name, '')) as project_name
          from issue_fact
         where deleted = false
         group by project_id
         order by case when project_id = ? then 0 else 1 end, max(coalesce(project_name, '')) asc
        """,
        (rs, rowNum) -> new OptionItemResponse(
            StringUtils.hasText(rs.getString("project_name")) ? rs.getString("project_name") : String.valueOf(rs.getLong("project_id")),
            String.valueOf(rs.getLong("project_id"))),
        DEFAULT_PROJECT_ID);
  }

  private List<OptionItemResponse> loadTestingPhaseOptions(Long projectId) {
    List<String> parents = phaseCatalogService.listParentNames(projectId);
    if (!parents.isEmpty()) {
      return OptionItemResponseFactory.fromValuesPreservingOrder(parents, TextQuerySupport::trimToNull);
    }
    List<String> phases = jdbcTemplate.queryForList(
        """
        select distinct testing_phase
          from issue_fact
         where deleted = false
           and project_id = ?
           and nullif(btrim(testing_phase), '') is not null
         order by testing_phase asc
        """,
        String.class,
        projectId);
    return OptionItemResponseFactory.fromValuesPreservingOrder(phases, TextQuerySupport::trimToNull);
  }

  private String loadProjectName(long projectId) {
    List<String> names = jdbcTemplate.queryForList(
        """
        select coalesce(project_name, '')
          from issue_fact
         where deleted = false
           and project_id = ?
           and nullif(btrim(project_name), '') is not null
         limit 1
        """,
        String.class,
        projectId);
    return names.isEmpty() ? String.valueOf(projectId) : names.get(0);
  }

  private List<String> orderedPhases(List<IssueRow> rows, ScopeContext scope) {
    if (!scope.expandedTestingPhases().isEmpty()) {
      return scope.expandedTestingPhases();
    }
    List<String> configured = phaseCatalogService.listTestingPhases(scope.projectId());
    Set<String> actual = rows.stream().map(IssueRow::testingPhase).filter(StringUtils::hasText).collect(Collectors.toCollection(LinkedHashSet::new));
    List<String> ordered = configured.stream().filter(actual::contains).toList();
    if (!ordered.isEmpty()) {
      return ordered;
    }
    return List.copyOf(actual);
  }

  private List<String> topModules(List<IssueRow> rows, int limit) {
    Map<String, Long> totals = new LinkedHashMap<>();
    rows.forEach(row -> modules(row).forEach(module -> totals.merge(module, 1L, Long::sum)));
    return totals.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
        .limit(limit)
        .map(Map.Entry::getKey)
        .toList();
  }

  private List<SystemTestIssueMultiBoardResponse.Point> topPoints(Map<String, Long> totals, int limit) {
    return totals.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
        .limit(limit)
        .map(entry -> point(entry.getKey(), entry.getValue()))
        .toList();
  }

  private List<String> modules(IssueRow row) {
    String value = row.moduleNames();
    if (!StringUtils.hasText(value)) {
      return List.of();
    }
    return List.of(value.split("\\s*,\\s*")).stream()
        .map(TextQuerySupport::trimToNull)
        .filter(item -> item != null && !item.startsWith("未设定") && !item.startsWith("未标注"))
        .distinct()
        .toList();
  }

  private String majorCause(IssueRow row) {
    String text = row.reasonCategory() + " " + row.labelNames();
    for (String cause : MAJOR_CAUSES) {
      if ("需求阶段".equals(cause) && containsAny(text, "需求阶段", "需求问题")) {
        return cause;
      }
      if ("设计阶段".equals(cause) && containsAny(text, "设计阶段", "设计问题")) {
        return cause;
      }
      if ("编码问题".equals(cause) && containsAny(text, "编码问题", "编码规范", "编码逻辑")) {
        return cause;
      }
      if (text.contains(cause)) {
        return cause;
      }
    }
    return "";
  }

  private String delayCause(IssueRow row) {
    String text = row.delayCause() + " " + row.delayReason() + " " + row.labelNames();
    return DELAY_CAUSES.stream().filter(text::contains).findFirst().orElse("");
  }

  private boolean matchesCauseMetric(IssueRow row, DefectCauseMetricCatalog.Metric metric) {
    return DefectCauseMetricCatalog.containsAny(row.reasonCategory(), metric.tokens())
        || DefectCauseMetricCatalog.containsAny(row.labelNames(), metric.tokens());
  }

  private long count(List<IssueRow> rows, RowPredicate predicate) {
    return rows.stream().filter(predicate::test).count();
  }

  private SystemTestIssueMultiBoardResponse.Point point(String name, long value) {
    return new SystemTestIssueMultiBoardResponse.Point(name, decimal(value));
  }

  private BigDecimal decimal(long value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal rate(long numerator, long denominator) {
    if (denominator <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  private boolean containsAny(String text, String... tokens) {
    if (!StringUtils.hasText(text)) {
      return false;
    }
    for (String token : tokens) {
      if (text.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private void writeChartSheet(
      Sheet sheet,
      CellStyle header,
      CellStyle body,
      SystemTestIssueMultiBoardResponse.Chart chart) {
    if (!chart.points().isEmpty()) {
      writeRow(sheet, 0, header, List.of("名称", "数值"));
      int rowIndex = 1;
      for (SystemTestIssueMultiBoardResponse.Point point : chart.points()) {
        writeRow(sheet, rowIndex++, body, List.of(point.name(), point.value()));
      }
      ExcelExportStyles.applyHeaderRows(sheet, 1);
      ExcelExportStyles.autoSizeColumns(sheet, 2);
      return;
    }

    List<String> headers = new ArrayList<>();
    headers.add("维度");
    headers.addAll(chart.series().stream().map(SystemTestIssueMultiBoardResponse.Series::name).toList());
    writeRow(sheet, 0, header, headers);
    for (int index = 0; index < chart.categories().size(); index++) {
      List<Object> values = new ArrayList<>();
      values.add(chart.categories().get(index));
      for (SystemTestIssueMultiBoardResponse.Series series : chart.series()) {
        values.add(index < series.data().size() ? series.data().get(index) : BigDecimal.ZERO);
      }
      writeRow(sheet, index + 1, body, values);
    }
    ExcelExportStyles.applyHeaderRows(sheet, 1);
    ExcelExportStyles.autoSizeColumns(sheet, headers.size());
  }

  private void writeRow(Sheet sheet, int rowIndex, CellStyle style, List<?> values) {
    Row row = sheet.createRow(rowIndex);
    for (int column = 0; column < values.size(); column++) {
      Cell cell = row.createCell(column);
      Object value = values.get(column);
      if (value instanceof Number number) {
        cell.setCellValue(number.doubleValue());
      } else {
        cell.setCellValue(value == null ? "" : String.valueOf(value));
      }
      cell.setCellStyle(style);
    }
  }

  private String safeSheetName(String title) {
    String normalized = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
    if (normalized.length() > 31) {
      normalized = normalized.substring(0, 31);
    }
    return StringUtils.hasText(normalized) ? normalized : "议题多元看板";
  }

  private String sanitizeFilename(String filename) {
    return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
  }

  private ChartDefinition definition(String key) {
    return CHART_DEFINITIONS.stream()
        .filter(item -> item.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static final List<ChartDefinition> CHART_DEFINITIONS =
      List.of(
          new ChartDefinition("severity-level", "缺陷严重程度分析", "/question-metrics/home"),
          new ChartDefinition("phase-severity", "缺陷阶段分析", "/question-metrics/phase-statistics"),
          new ChartDefinition("module-severity", "缺陷模块分析", "/question-metrics/home"),
          new ChartDefinition("major-cause", "缺陷原因占比分析", "/question-metrics/defect-cause"),
          new ChartDefinition("cause-detail", "缺陷原因分析", "/question-metrics/defect-cause"),
          new ChartDefinition("module-repair-rate", "模块修复率", "/question-metrics/home"),
          new ChartDefinition("open-issue", "未关闭缺陷占比", "/question-metrics/home"),
          new ChartDefinition("fix-user-severity", "修复人-缺陷数量统计", "/question-metrics/issues"),
          new ChartDefinition("extension-module", "申请延期模块分析", "/question-metrics/delay-analysis"),
          new ChartDefinition("delay-cause", "申请延期缺陷原因分析", "/question-metrics/delay-analysis"),
          new ChartDefinition("rollback-module", "回退模块缺陷占比", "/question-metrics/home"));

  private record ScopeContext(
      Long projectId,
      String projectName,
      String testingPhase,
      List<String> expandedTestingPhases,
      String label) {}

  private record ChartDefinition(String key, String title, String detailPath) {}

  private record Severity(String key, String label) {
    boolean matches(IssueRow row) {
      String value = row.severityLevel();
      return switch (key) {
        case "level1" -> row.isRegularMetricIssue()
            && (value.contains("一级") || value.equalsIgnoreCase("LEVEL1") || value.equalsIgnoreCase("LEVEL 1"));
        case "level2" -> row.isRegularMetricIssue()
            && (value.contains("二级") || value.equalsIgnoreCase("LEVEL2") || value.equalsIgnoreCase("LEVEL 2"));
        case "level3" -> row.isRegularMetricIssue()
            && (value.contains("三级") || value.equalsIgnoreCase("LEVEL3") || value.equalsIgnoreCase("LEVEL 3"));
        case "suggestion" -> row.isSuggestion();
        default -> false;
      };
    }
  }

  private record IssueRow(
      Long projectId,
      String projectName,
      Long issueId,
      Long issueIid,
      String title,
      String issueState,
      String testingPhase,
      String severityLevel,
      String bugStatus,
      String category,
      String reasonCategory,
      String delayCause,
      String delayReason,
      String moduleNames,
      String fixUser,
      String labelNames,
      boolean excluded,
      String exclusionReason,
      boolean fixed,
      boolean delay,
      boolean regression) {
    boolean open() {
      return !"closed".equalsIgnoreCase(issueState);
    }

    boolean rollback() {
      return regression || title.contains("回退") || labelNames.contains("回退");
    }

    /*
     * 多元看板只在明确包含“建议类缺陷”系列的图表中恢复建议类；修复率、未关闭占比、
     * 回退、缺陷原因等常规图表仍排除建议类。老平台建议类为 0 是为了避免污染严重程度
     * 和汇总指标的折中，新平台按领导要求保留建议类独立展示。后续不要直接按“对齐老平台”
     * 把这里改回 0，应先确认这段业务决策。
     */
    boolean isSuggestion() {
      return SystemTestSuggestionMetricSupport.isSuggestionColumnIssue(excluded, exclusionReason, severityLevel, category);
    }

    boolean isRegularMetricIssue() {
      return SystemTestSuggestionMetricSupport.isRegularMetricIssue(excluded, exclusionReason, severityLevel, category);
    }
  }

  @FunctionalInterface
  private interface RowPredicate {
    boolean test(IssueRow row);
  }
}
