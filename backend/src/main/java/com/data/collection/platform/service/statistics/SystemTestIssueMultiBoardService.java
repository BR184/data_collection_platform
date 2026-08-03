package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.SystemTestIssueMultiBoardResponse;
import com.data.collection.platform.service.OptionItemResponseFactory;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.TextQuerySupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private static final List<String> MAJOR_CAUSES = SystemTestIssueMetricDimensionSupport.MAJOR_CAUSES;
  private static final List<String> DELAY_CAUSES = SystemTestIssueMetricDimensionSupport.DELAY_CAUSES;

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseCatalogService phaseCatalogService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final ObjectMapper objectMapper;

  public SystemTestIssueMultiBoardService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseCatalogService phaseCatalogService,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseCatalogService = phaseCatalogService;
    this.phaseScopeResolver = phaseScopeResolver;
    this.objectMapper = objectMapper;
  }

  public SystemTestIssueMultiBoardResponse getBoard(Long projectId, String testingPhase) {
    ScopeContext scope = resolveScope(projectId, testingPhase);
    List<IssueRow> rows = loadRows(scope);
    List<IssueRow> exactRows = exactPhaseRows(rows, scope);
    List<SystemTestIssueMultiBoardResponse.Chart> charts = buildCharts(rows, exactRows, scope);
    return new SystemTestIssueMultiBoardResponse(
        new SystemTestIssueMultiBoardResponse.Scope(
            scope.projectId(),
            scope.projectName(),
            scope.testingPhase(),
            scope.expandedTestingPhases(),
            scope.label()),
        loadProjectOptions(),
        loadTestingPhaseOptions(scope.projectId()),
        buildRules(scope),
        buildSummaryCards(rows),
        charts);
  }

  public byte[] exportChart(Long projectId, String testingPhase, String chartKey) {
    ScopeContext scope = resolveScope(projectId, testingPhase);
    List<IssueRow> rows = loadRows(scope);
    List<IssueRow> exactRows = exactPhaseRows(rows, scope);
    SystemTestIssueMultiBoardResponse.Chart chart = buildCharts(rows, exactRows, scope).stream()
        .filter(item -> item.key().equals(chartKey))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知的议题多元看板图表：" + chartKey));
    return SystemTestIssueMultiBoardWorkbookExporter.export(
        chart, rowsForChart(chartKey, rows, exactRows));
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
        new SystemTestIssueMultiBoardResponse.SummaryCard(
            "total", "系统测试缺陷", String.valueOf(total), "default", "summary-total"),
        new SystemTestIssueMultiBoardResponse.SummaryCard(
            "open", "未关闭缺陷", String.valueOf(open), "warning", "summary-open"),
        new SystemTestIssueMultiBoardResponse.SummaryCard(
            "fixed", "已修复/未更新", String.valueOf(fixed), "success", "summary-fixed"),
        new SystemTestIssueMultiBoardResponse.SummaryCard(
            "delay", "申请延期", String.valueOf(delay), "danger", "summary-delay"));
  }

  private List<SystemTestIssueMultiBoardResponse.Rule> buildRules(ScopeContext scope) {
    List<SystemTestIssueMultiBoardResponse.Rule> rules = new ArrayList<>();
    rules.add(rule("summary-total", "系统测试缺陷", "COUNT(常规系统测试缺陷)", scope,
        "排除建议类后，统计当前项目与阶段范围内的常规系统测试缺陷。"));
    rules.add(rule("summary-open", "未关闭缺陷", "COUNT(issue_state != closed)", scope,
        "在常规系统测试缺陷中统计议题状态未关闭的记录。"));
    rules.add(rule("summary-fixed", "已修复/未更新", "COUNT(is_fixed = true)", scope,
        "按事实层统一修复状态规则统计已修复、待合并或未更新记录。"));
    rules.add(rule("summary-delay", "申请延期", "COUNT(delay_issue = true)", scope,
        "按延期标识与延期原因规则统计申请延期记录。"));
    for (ChartDefinition definition : CHART_DEFINITIONS) {
      rules.add(rule(
          definition.key(),
          definition.title(),
          definition.formula(),
          scope,
          definition.description()));
    }
    return List.copyOf(rules);
  }

  private SystemTestIssueMultiBoardResponse.Rule rule(
      String key,
      String title,
      String formula,
      ScopeContext scope,
      String description) {
    return new SystemTestIssueMultiBoardResponse.Rule(
        key,
        title,
        formula,
        scope.label(),
        null,
        description);
  }

  private List<SystemTestIssueMultiBoardResponse.Chart> buildCharts(
      List<IssueRow> containsRows, List<IssueRow> exactRows, ScopeContext scope) {
    List<IssueRow> rows = containsRows;
    List<IssueRow> regularRows = rows.stream().filter(IssueRow::isRegularMetricIssue).toList();
    List<IssueRow> exactRegularRows =
        exactRows.stream().filter(IssueRow::isRegularMetricIssue).toList();
    return List.of(
        severityPie(rows, scope),
        phaseSeverity(exactRows, scope),
        moduleSeverity(exactRows, scope),
        majorCausePie(regularRows, scope),
        causeDetail(regularRows, scope),
        moduleRepairRate(regularRows, scope),
        openSeverityPie(exactRegularRows, scope),
        fixUserSeverity(rows, scope),
        extensionModulePie(regularRows, scope),
        delayCause(rows, scope),
        rollbackModulePie(regularRows, scope));
  }

  private SystemTestIssueMultiBoardResponse.Chart severityPie(List<IssueRow> rows, ScopeContext scope) {
    List<SystemTestIssueMultiBoardResponse.Point> points = SEVERITIES.stream()
        .map(severity -> point(
            "severity-level",
            severity.label(),
            count(rows, row -> severity.matches(row)),
            scope,
            List.of(condition("metricSeverity", severity.filterValue()))))
        .toList();
    return pointChart("severity-level", "缺陷严重程度分析", "按议题严重程度统计当前范围缺陷数量。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart openSeverityPie(List<IssueRow> rows, ScopeContext scope) {
    List<IssueRow> openRows = rows.stream().filter(IssueRow::open).toList();
    List<SystemTestIssueMultiBoardResponse.Point> points = REGULAR_SEVERITIES.stream()
        .map(severity -> point(
            "open-issue",
            severity.label(),
            count(openRows, row -> severity.matches(row)),
            scope,
            List.of(
                condition("metricSeverity", severity.filterValue()),
                condition("openIssue", "true"))))
        .toList();
    return pointChart("open-issue", "未关闭缺陷占比", "按严重程度统计当前范围内未关闭缺陷。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart majorCausePie(List<IssueRow> rows, ScopeContext scope) {
    List<SystemTestIssueMultiBoardResponse.Point> points = MAJOR_CAUSES.stream()
        .map(cause -> point(
            "major-cause",
            cause,
            count(rows, row -> majorCause(row).equals(cause)),
            scope,
            List.of(
                condition("regularMetric", "true"),
                condition("majorCause", cause))))
        .toList();
    return pointChart("major-cause", "缺陷原因占比分析", "按六类缺陷原因统计占比。", "pie", points, scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart extensionModulePie(List<IssueRow> rows, ScopeContext scope) {
    Map<String, Long> totals = new LinkedHashMap<>();
    rows.stream().filter(IssueRow::delay).forEach(row -> modules(row).forEach(module -> totals.merge(module, 1L, Long::sum)));
    return pointChart(
        "extension-module",
        "申请延期模块分析",
        "按模块统计申请延期缺陷数量。",
        "pie",
        topPoints(
            "extension-module",
            totals,
            12,
            scope,
            "moduleName",
            List.of(
                condition("regularMetric", "true"),
                condition("delayIssue", "true"))),
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
        topPoints(
            "rollback-module",
            totals,
            12,
            scope,
            "moduleName",
            List.of(
                condition("regularMetric", "true"),
                condition("rollback", "true"))),
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
        severitySeries(
            "phase-severity", phases, rows, IssueRow::testingPhase, "testingPhase", scope, List.of()),
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
        severitySeriesByModules("module-severity", modules, rows, scope),
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
        severitySeries(
            "fix-user-severity",
            users,
            rows.stream().filter(row -> StringUtils.hasText(row.fixUser())).toList(),
            IssueRow::fixUser,
            "fixUser",
            scope,
            List.of()),
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart causeDetail(List<IssueRow> rows, ScopeContext scope) {
    List<String> categories = DefectCauseMetricCatalog.METRICS.stream().map(DefectCauseMetricCatalog.Metric::label).toList();
    List<SystemTestIssueMultiBoardResponse.Series> series = REGULAR_SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            DefectCauseMetricCatalog.METRICS.stream()
                .map(metric -> point(
                    "cause-detail",
                    metric.label(),
                    count(rows, row -> severity.matches(row) && matchesCauseMetric(row, metric)),
                    scope,
                    List.of(
                        condition("regularMetric", "true"),
                        condition("metricSeverity", severity.filterValue()),
                        condition("causeMetric", metric.key()))))
                .toList()))
        .toList();
    return seriesChart(
        "cause-detail",
        "缺陷原因分析",
        "按缺陷原因明细拆分严重程度。",
        "stackedBar",
        categories,
        series,
        scope);
  }

  private SystemTestIssueMultiBoardResponse.Chart delayCause(List<IssueRow> rows, ScopeContext scope) {
    List<IssueRow> delayRows = rows.stream().filter(IssueRow::delay).toList();
    List<SystemTestIssueMultiBoardResponse.Series> series = SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            DELAY_CAUSES.stream()
                .map(cause -> point(
                    "delay-cause",
                    cause,
                    count(delayRows, row -> severity.matches(row) && delayCauses(row).contains(cause)),
                    scope,
                    List.of(
                        condition("metricSeverity", severity.filterValue()),
                        condition("delayIssue", "true"),
                        condition("delayCause", cause))))
                .toList()))
        .toList();
    return seriesChart(
        "delay-cause",
        "申请延期缺陷原因分析",
        "按延期原因拆分各严重程度缺陷数量。",
        "stackedBar",
        DELAY_CAUSES,
        series,
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
    List<SystemTestIssueMultiBoardResponse.Point> points = new ArrayList<>();
    for (int index = 0; index < modules.size(); index++) {
      points.add(point(
          "module-repair-rate",
          modules.get(index),
          data.get(index),
          scope,
          List.of(
              condition("regularMetric", "true"),
              condition("moduleName", modules.get(index)))));
    }
    return seriesChart(
        "module-repair-rate",
        "模块修复率",
        "按模块统计已修复缺陷占比，保留两位小数。",
        "bar",
        modules,
        List.of(new SystemTestIssueMultiBoardResponse.Series("修复率(%)", List.copyOf(points))),
        scope);
  }

  private List<SystemTestIssueMultiBoardResponse.Series> severitySeries(
      String chartKey,
      List<String> categories,
      List<IssueRow> rows,
      Function<IssueRow, String> classifier,
      String dimensionField,
      ScopeContext scope,
      List<StatisticFilterCondition> commonConditions) {
    return SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            categories.stream()
                .map(category -> point(
                    chartKey,
                    category,
                    count(rows, row -> severity.matches(row) && category.equals(classifier.apply(row))),
                    scope,
                    concatConditions(
                        commonConditions,
                        condition("metricSeverity", severity.filterValue()),
                        condition(dimensionField, category))))
                .toList()))
        .toList();
  }

  private List<SystemTestIssueMultiBoardResponse.Series> severitySeriesByModules(
      String chartKey,
      List<String> categories,
      List<IssueRow> rows,
      ScopeContext scope) {
    return SEVERITIES.stream()
        .map(severity -> new SystemTestIssueMultiBoardResponse.Series(
            severity.label(),
            categories.stream()
                .map(category -> point(
                    chartKey,
                    category,
                    count(rows, row -> severity.matches(row) && modules(row).contains(category)),
                    scope,
                    List.of(
                        condition("metricSeverity", severity.filterValue()),
                        condition("moduleName", category))))
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
        definition.key(),
        definition.detailViewKey(),
        scopeParams(scope),
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
      ScopeContext scope) {
    ChartDefinition definition = definition(key);
    return new SystemTestIssueMultiBoardResponse.Chart(
        key,
        title,
        description,
        chartType,
        definition.key(),
        definition.detailViewKey(),
        scopeParams(scope),
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
               created_at_source,
               updated_at_source,
               coalesce(milestone_title, '') as milestone_title,
               coalesce(author_name, '') as author_name,
               coalesce(assignee_name, '') as assignee_name,
               coalesce(priority_level, '') as priority_level,
               coalesce(function_name, '') as function_name,
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
      SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
          SystemTestPhaseMembershipPolicy.sqlPredicate(
              scope.expandedTestingPhases(),
              SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER);
      sql.append(" and (").append(phasePredicate.sql()).append(")");
      args.addAll(phasePredicate.args());
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
        rs.getTimestamp("created_at_source") == null
            ? null
            : rs.getTimestamp("created_at_source").toLocalDateTime(),
        rs.getTimestamp("updated_at_source") == null
            ? null
            : rs.getTimestamp("updated_at_source").toLocalDateTime(),
        rs.getString("milestone_title"),
        rs.getString("author_name"),
        rs.getString("assignee_name"),
        rs.getString("priority_level"),
        rs.getString("function_name"),
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

  private List<IssueRow> exactPhaseRows(List<IssueRow> rows, ScopeContext scope) {
    if (scope.expandedTestingPhases().isEmpty()) {
      return rows;
    }
    return rows.stream()
        .filter(
            row ->
                SystemTestPhaseMembershipPolicy.matches(
                    row.testingPhase(),
                    scope.expandedTestingPhases(),
                    SystemTestPhaseMembershipPolicy.MatchMode.EXACT_MEMBER))
        .toList();
  }

  private List<IssueRow> rowsForChart(
      String chartKey, List<IssueRow> containsRows, List<IssueRow> exactRows) {
    return EXACT_PHASE_CHART_KEYS.contains(chartKey) ? exactRows : containsRows;
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

  private List<SystemTestIssueMultiBoardResponse.Point> topPoints(
      String chartKey,
      Map<String, Long> totals,
      int limit,
      ScopeContext scope,
      String dimensionField,
      List<StatisticFilterCondition> commonConditions) {
    return totals.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
        .limit(limit)
        .map(entry -> point(
            chartKey,
            entry.getKey(),
            entry.getValue(),
            scope,
            concatConditions(commonConditions, condition(dimensionField, entry.getKey()))))
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
    return SystemTestIssueMetricDimensionSupport.majorCause(row.reasonCategory(), row.labelNames());
  }

  private List<String> delayCauses(IssueRow row) {
    return SystemTestIssueMetricDimensionSupport.delayCauses(
        row.delayCause(), row.delayReason(), row.labelNames());
  }

  private boolean matchesCauseMetric(IssueRow row, DefectCauseMetricCatalog.Metric metric) {
    return SystemTestIssueMetricDimensionSupport.matchesCauseMetric(
        metric.key(), row.reasonCategory(), row.labelNames());
  }

  private long count(List<IssueRow> rows, RowPredicate predicate) {
    return rows.stream().filter(predicate::test).count();
  }

  private SystemTestIssueMultiBoardResponse.Point point(
      String chartKey,
      String name,
      long value,
      ScopeContext scope,
      List<StatisticFilterCondition> conditions) {
    return point(chartKey, name, decimal(value), scope, conditions);
  }

  private SystemTestIssueMultiBoardResponse.Point point(
      String chartKey,
      String name,
      BigDecimal value,
      ScopeContext scope,
      List<StatisticFilterCondition> conditions) {
    List<StatisticFilterCondition> safeConditions = conditions == null ? List.of() : List.copyOf(conditions);
    Map<String, String> detailParams = new LinkedHashMap<>(scopeParams(scope));
    if (!safeConditions.isEmpty()) {
      detailParams.put("filterGroup", serializeFilterGroup(safeConditions));
    }
    String conditionKey = safeConditions.stream()
        .map(condition -> condition.fieldKey() + "=" + condition.value())
        .collect(Collectors.joining("|"));
    String pointKey = chartKey + ":" + (conditionKey.isEmpty() ? name : conditionKey);
    return new SystemTestIssueMultiBoardResponse.Point(
        name,
        value,
        pointKey,
        "system-test-issue-records",
        Map.copyOf(detailParams));
  }

  private StatisticFilterCondition condition(String fieldKey, String value) {
    return new StatisticFilterCondition(fieldKey, "eq", value, null);
  }

  private List<StatisticFilterCondition> concatConditions(
      List<StatisticFilterCondition> base,
      StatisticFilterCondition... additions) {
    List<StatisticFilterCondition> result = new ArrayList<>();
    if (base != null) {
      result.addAll(base);
    }
    if (additions != null) {
      result.addAll(List.of(additions));
    }
    return List.copyOf(result);
  }

  private Map<String, String> scopeParams(ScopeContext scope) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("projectId", String.valueOf(scope.projectId()));
    params.put("projectName", scope.projectName());
    if (StringUtils.hasText(scope.testingPhase())) {
      params.put("testingPhase", scope.testingPhase());
    }
    return Map.copyOf(params);
  }

  private String serializeFilterGroup(List<StatisticFilterCondition> conditions) {
    try {
      return objectMapper.writeValueAsString(new StatisticFilterGroup("AND", conditions));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("生成系统测试看板点位筛选条件失败", error);
    }
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
          new ChartDefinition(
              "severity-level",
              "缺陷严重程度分析",
              "system-test-defect-summary",
              "COUNT(缺陷) GROUP BY 严重程度",
              "常规缺陷按一级、二级、三级统计，建议类独立成组。"),
          new ChartDefinition(
              "phase-severity",
              "缺陷阶段分析",
              "system-test-phase-statistics",
              "COUNT(缺陷) GROUP BY 测试阶段, 严重程度",
              "父阶段先展开为配置的子轮次，再按阶段与严重程度统计。"),
          new ChartDefinition(
              "module-severity",
              "缺陷模块分析",
              "system-test-defect-summary",
              "COUNT(缺陷) GROUP BY 模块, 严重程度",
              "多模块议题在每个所属模块各计一次，展示缺陷数最高的模块。"),
          new ChartDefinition(
              "major-cause",
              "缺陷原因占比分析",
              "system-test-defect-cause",
              "COUNT(常规缺陷) GROUP BY 六类主原因",
              "按需求、设计、编码、打包、依赖和精度六类原因归类。"),
          new ChartDefinition(
              "cause-detail",
              "缺陷原因分析",
              "system-test-defect-cause",
              "COUNT(常规缺陷) GROUP BY 原因明细, 严重程度",
              "按评论事实归一后的原因明细和严重程度交叉统计。"),
          new ChartDefinition(
              "module-repair-rate",
              "模块修复率",
              "system-test-defect-summary",
              "已修复缺陷数 / 模块常规缺陷总数 × 100%",
              "分子与分母均使用当前项目、阶段和模块的相同常规缺陷范围。"),
          new ChartDefinition(
              "open-issue",
              "未关闭缺陷占比",
              "system-test-defect-summary",
              "COUNT(issue_state != closed) GROUP BY 严重程度",
              "只统计常规缺陷中议题状态未关闭的记录。"),
          new ChartDefinition(
              "fix-user-severity",
              "修复人-缺陷数量统计",
              "system-test-defect-summary",
              "COUNT(缺陷) GROUP BY 修复人, 严重程度",
              "修复人取合法修复状态评论作者，空值不生成伪分组。"),
          new ChartDefinition(
              "extension-module",
              "申请延期模块分析",
              "system-test-delay-analysis",
              "COUNT(delay_issue = true) GROUP BY 模块",
              "仅统计常规缺陷中的申请延期记录，多模块分别计数。"),
          new ChartDefinition(
              "delay-cause",
              "申请延期缺陷原因分析",
              "system-test-delay-analysis",
              "COUNT(delay_issue = true) GROUP BY 延期原因, 严重程度",
              "按七类延期原因与严重程度交叉统计。"),
          new ChartDefinition(
              "rollback-module",
              "回退模块缺陷占比",
              "system-test-defect-summary",
              "COUNT(回退类常规缺陷) GROUP BY 模块",
              "回退按事实标记、标题或标签规则识别，多模块分别计数。"));

  private static final Set<String> EXACT_PHASE_CHART_KEYS =
      Set.of("phase-severity", "module-severity", "open-issue");

  private record ScopeContext(
      Long projectId,
      String projectName,
      String testingPhase,
      List<String> expandedTestingPhases,
      String label) {}

  private record ChartDefinition(
      String key,
      String title,
      String detailViewKey,
      String formula,
      String description) {}

  private record Severity(String key, String label) {
    String filterValue() {
      return key.toUpperCase(java.util.Locale.ROOT);
    }

    boolean matches(IssueRow row) {
      return filterValue().equals(SystemTestIssueMetricDimensionSupport.metricSeverity(
          row.excluded(), row.exclusionReason(), row.severityLevel(), row.category()));
    }
  }

  record IssueRow(
      Long projectId,
      String projectName,
      Long issueId,
      Long issueIid,
      String title,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      String priorityLevel,
      String functionName,
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
      return SystemTestIssueMetricDimensionSupport.rollback(regression, title, labelNames);
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
