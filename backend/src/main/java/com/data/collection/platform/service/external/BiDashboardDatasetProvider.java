package com.data.collection.platform.service.external;

import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.entity.external.BiDashboardPayload;
import com.data.collection.platform.entity.external.BiDashboardPayload.BoardCell;
import com.data.collection.platform.entity.external.BiDashboardPayload.BoardColumn;
import com.data.collection.platform.entity.external.BiDashboardPayload.BoardRow;
import com.data.collection.platform.entity.external.BiDashboardPayload.BoardTable;
import com.data.collection.platform.entity.external.BiDashboardPayload.CodeSubmissionPoint;
import com.data.collection.platform.entity.external.BiDashboardPayload.CodeSubmissionTrend;
import com.data.collection.platform.entity.external.BiDashboardPayload.FixUserRow;
import com.data.collection.platform.entity.external.BiDashboardPayload.QualityMetric;
import com.data.collection.platform.entity.external.BiDashboardPayload.QualityTargets;
import com.data.collection.platform.entity.external.BiDashboardPayload.ReviewCategoryRow;
import com.data.collection.platform.entity.external.BiDashboardPayload.ReviewDensityRow;
import com.data.collection.platform.entity.external.BiDashboardPayload.ReviewDistributions;
import com.data.collection.platform.entity.external.BiDashboardPayload.ReviewTypeRow;
import com.data.collection.platform.entity.external.ExternalDatasetDescriptor;
import com.data.collection.platform.entity.external.ExternalDatasetField;
import com.data.collection.platform.entity.external.ExternalDatasetParameter;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.QualityBoardRdService;
import com.data.collection.platform.service.statistics.AbstractStatisticBoardService;
import com.data.collection.platform.service.statistics.StatisticBoardRegistry;
import com.data.collection.platform.service.statistics.SystemTestDefectSummaryBoardService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Aggregates exactly the data regions rendered by the independent BI dashboard. */
@Service
public class BiDashboardDatasetProvider implements ExternalDatasetProvider<BiDashboardPayload> {
  public static final String DATASET_KEY = "bi-dashboard";
  private static final String SCHEMA_VERSION = "1.0";
  private static final long CROWN_CAD_PROJECT_ID = 9L;
  private static final Set<String> PHASE_COLUMNS = Set.of("level1", "level2", "level3", "total");

  private final JdbcTemplate jdbcTemplate;
  private final QualityBoardRdService qualityBoardRdService;
  private final SystemTestDefectSummaryBoardService summaryBoardService;
  private final StatisticBoardRegistry statisticBoardRegistry;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CodeReviewMatchModeSwitchService codeReviewSwitchService;

  public BiDashboardDatasetProvider(
      JdbcTemplate jdbcTemplate,
      QualityBoardRdService qualityBoardRdService,
      SystemTestDefectSummaryBoardService summaryBoardService,
      StatisticBoardRegistry statisticBoardRegistry,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      CodeReviewMatchModeSwitchService codeReviewSwitchService) {
    this.jdbcTemplate = jdbcTemplate;
    this.qualityBoardRdService = qualityBoardRdService;
    this.summaryBoardService = summaryBoardService;
    this.statisticBoardRegistry = statisticBoardRegistry;
    this.phaseScopeResolver = phaseScopeResolver;
    this.codeReviewSwitchService = codeReviewSwitchService;
  }

  @Override
  public String datasetKey() {
    return DATASET_KEY;
  }

  @Override
  public ExternalDatasetDescriptor descriptor() {
    return new ExternalDatasetDescriptor(
        DATASET_KEY,
        "BI研发质量看板全量数据",
        "独立BI看板首期全部展示数据；只读、按产品版本统一取数，不包含下钻、明细或文件导出。",
        SCHEMA_VERSION,
        List.of(new ExternalDatasetParameter(
            "productVersion", "string", true, "产品版本名称，例如 CC2026R4。", "CC2026R4"),
            new ExternalDatasetParameter(
                "codeGranularity", "string", false, "代码趋势粒度：day 或 week。", "day"),
            new ExternalDatasetParameter(
                "codeSource", "string", false, "代码趋势范围：all、cc 或 dgm。", "all"),
            new ExternalDatasetParameter(
                "repositoryName", "string", false, "代码趋势仓库名称，可选精确过滤。", "CrownCAD")),
        List.of(
            new ExternalDatasetField("productVersion", "string", false, "产品版本。"),
            new ExternalDatasetField("availableProductVersions[]", "string", false, "可选择的启用产品版本。"),
            new ExternalDatasetField("testingPhases[]", "string", false, "产品版本下的系统测试阶段。"),
            new ExternalDatasetField("qualityTargets.metrics[]", "object", false, "质量目标指标。"),
            new ExternalDatasetField("moduleFixRates[]", "object", false, "各模块系统测试修复率。"),
            new ExternalDatasetField("reviewDistributions.byType[]", "object", false, "评审类型问题分布。"),
            new ExternalDatasetField("reviewDistributions.byCategory[]", "object", false, "评审问题类别分布。"),
            new ExternalDatasetField("reviewDistributions.densities[]", "object", false, "需求/设计评审整体和模块缺陷密度。"),
            new ExternalDatasetField("phaseFixes", "object", false, "系统测试各轮次缺陷修复情况。"),
            new ExternalDatasetField("severityDistribution", "object", false, "系统测试一级/二级/三级缺陷分布。"),
            new ExternalDatasetField("defectCauseDistribution", "object", false, "系统测试缺陷原因大类、明细及模块分布。"),
            new ExternalDatasetField("delayedDefects", "object", false, "申请延期原因及严重程度交叉数据。"),
            new ExternalDatasetField("fixUsers[]", "object", false, "按实际修复人统计缺陷数。"),
            new ExternalDatasetField("codeSubmissionTrend", "object", false, "新增代码和累计新增代码趋势。")));
  }

  @Override
  public BiDashboardPayload load(Map<String, String> parameters) {
    String productVersion = required(parameters, "productVersion");
    String granularity = normalizeGranularity(parameters == null ? null : parameters.get("codeGranularity"));
    String codeSource = normalizeCodeSource(parameters == null ? null : parameters.get("codeSource"));
    String repositoryName = optional(parameters == null ? null : parameters.get("repositoryName"));
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(productVersion);
    if (phases.isEmpty()) {
      throw new IllegalArgumentException("产品版本不存在或没有启用的系统测试阶段: " + productVersion);
    }
    QualityBoardRdOverviewResponse overview = qualityBoardRdService.getOverview(productVersion);
    StatisticBoardResponse summary = board("system-test-defect-summary", productVersion);
    return new BiDashboardPayload(
        productVersion,
        phaseScopeResolver.listEnabledLegacyCrownCadParentNames(),
        phases,
        qualityTargets(overview, summary),
        summaryBoardService.loadExternalModuleFixRates(productVersion).modules(),
        reviewDistributions(productVersion),
        loadPhaseFixes(productVersion, phases),
        severityTable(summary),
        boardTable(board("system-test-defect-cause", productVersion), Set.of()),
        boardTable(board("system-test-delay-analysis", productVersion), PHASE_COLUMNS),
        fixUsers(productVersion),
        codeSubmissionTrend(productVersion, granularity, codeSource, repositoryName));
  }

  private StatisticBoardResponse board(String key, String productVersion) {
    AbstractStatisticBoardService service = statisticBoardRegistry.getRequired(key);
    return service.loadBoard(Map.of(
        "projectId", Long.toString(CROWN_CAD_PROJECT_ID),
        "projectName", productVersion,
        "testingPhase", productVersion));
  }

  private QualityTargets qualityTargets(
      QualityBoardRdOverviewResponse overview, StatisticBoardResponse summary) {
    StatisticRowData total = summary.rows().stream()
        .filter(row -> "__total__".equals(row.rowKey()))
        .findFirst()
        .orElse(null);
    List<QualityMetric> metrics = new ArrayList<>();
    metrics.add(metric("demand-review-density", "需求评审缺陷密度", overview.demandReviewReportDensity(), "", "[0.20, 0.60]", inRange(overview.demandReviewReportDensity(), 0.2, 0.6)));
    metrics.add(metric("design-review-density", "设计评审缺陷密度", overview.designReviewReportDensity(), "", "[0.20, 0.60]", inRange(overview.designReviewReportDensity(), 0.2, 0.6)));
    metrics.add(metric("product-code-review-density", "产品代码走查缺陷密度", overview.codeWalkThroughDefectDensityCc(), "KLOC", "[2.00, 10.00]", inRange(overview.codeWalkThroughDefectDensityCc(), 2, 10)));
    metrics.add(metric("kernel-code-review-density", "内核代码走查缺陷密度", overview.codeWalkThroughDefectDensityDgm(), "KLOC", "[2.00, 10.00]", inRange(overview.codeWalkThroughDefectDensityDgm(), 2, 10)));
    metrics.add(metric("level1-fix-rate", "一级缺陷修复率", cellNumber(total, "level1_rate"), "%", "100.00%", equalsPercent(cellNumber(total, "level1_rate"), 100)));
    metrics.add(metric("p1-fix-rate", "P1缺陷修复率", cellNumber(total, "p1_fix_rate"), "%", "不低于 90.00%", atLeast(cellNumber(total, "p1_fix_rate"), 90)));
    metrics.add(metric("p2-fix-rate", "P2缺陷修复率", cellNumber(total, "p2_fix_rate"), "%", "不低于 80.00%", atLeast(cellNumber(total, "p2_fix_rate"), 80)));
    return new QualityTargets(metrics);
  }

  private QualityMetric metric(String key, String label, Double value, String unit, String target, Boolean achieved) {
    return new QualityMetric(key, label, decimal(value), unit, target, achieved);
  }

  private QualityMetric metric(String key, String label, BigDecimal value, String unit, String target, Boolean achieved) {
    return new QualityMetric(key, label, value, unit, target, achieved);
  }

  private ReviewDistributions reviewDistributions(String productVersion) {
    String project = productVersion.toLowerCase();
    List<ReviewTypeRow> byType = jdbcTemplate.query(
        """
        with valid as (
          select r.review_type
            from review_visible_records r
            join review_visible_problem_items p on p.review_record_id = r.id and p.deleted = false
           where r.deleted = false and lower(r.project_name) = ?
             and coalesce(btrim(p.problem_status), '') not in ('已拒绝', '未评审', '无问题')
             and coalesce(btrim(p.problem_category), '') <> '无问题'
        )
        select coalesce(review_type, ''), count(*) from valid group by review_type order by review_type
        """,
        (rs, n) -> new ReviewTypeRow(rs.getString(1), rs.getLong(2), null), project);
    long total = byType.stream().mapToLong(ReviewTypeRow::problemCount).sum();
    byType = byType.stream().map(row -> new ReviewTypeRow(row.reviewType(), row.problemCount(), share(row.problemCount(), total))).toList();
    List<ReviewCategoryRow> byCategory = jdbcTemplate.query(
        """
        select coalesce(r.review_type, ''), coalesce(p.problem_category, ''), count(*)
          from review_visible_records r
          join review_visible_problem_items p on p.review_record_id = r.id and p.deleted = false
         where r.deleted = false and lower(r.project_name) = ?
           and coalesce(btrim(p.problem_status), '') not in ('已拒绝', '未评审', '无问题')
           and coalesce(btrim(p.problem_category), '') <> '无问题'
         group by r.review_type, p.problem_category
         order by r.review_type, p.problem_category
        """,
        (rs, n) -> new ReviewCategoryRow(rs.getString(1), rs.getString(2), rs.getLong(3), null), project);
    Map<String, Long> typeTotals = byCategory.stream().collect(java.util.stream.Collectors.groupingBy(ReviewCategoryRow::reviewType, java.util.stream.Collectors.summingLong(ReviewCategoryRow::problemCount)));
    byCategory = byCategory.stream().map(row -> new ReviewCategoryRow(row.reviewType(), row.problemCategory(), row.problemCount(), share(row.problemCount(), typeTotals.getOrDefault(row.reviewType(), 0L)))).toList();
    List<ReviewDensityRow> densities = jdbcTemplate.query(
        """
        with records as (
          select r.id, r.review_type, r.module_name, greatest(coalesce(r.review_scale_pages, 0), 0) pages,
                 count(p.id) filter (where coalesce(btrim(p.problem_status), '') not in ('已拒绝', '未评审', '无问题')
                   and coalesce(btrim(p.problem_category), '') <> '无问题') defects
            from review_visible_records r
            left join review_visible_problem_items p on p.review_record_id = r.id and p.deleted = false
           where r.deleted = false and lower(r.project_name) = ?
           group by r.id, r.review_type, r.module_name, r.review_scale_pages
        )
        select coalesce(review_type, ''), coalesce(module_name, ''), sum(defects), sum(pages),
               round((sum(defects)::numeric / nullif(sum(pages), 0)), 2)
          from records
         where review_type in ('需求说明书评审', '设计说明书评审')
         group by review_type, module_name
         order by review_type, module_name
        """,
        (rs, n) -> new ReviewDensityRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getBigDecimal(5)), project);
    return new ReviewDistributions(byType, byCategory, densities);
  }

  private List<FixUserRow> fixUsers(String productVersion) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(productVersion);
    if (phases.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    String sql = """
        select btrim(fix_user) as person_name,
               sum(case when severity_level = 'LEVEL1' then 1 else 0 end) as level1_count,
               sum(case when severity_level = 'LEVEL2' then 1 else 0 end) as level2_count,
               sum(case when severity_level = 'LEVEL3' then 1 else 0 end) as level3_count,
               sum(case when coalesce(is_fixed, false)
                              or coalesce(bug_status, '') like '%%已修复%%'
                              or coalesce(bug_status, '') like '%%待合并%%'
                              or coalesce(bug_status, '') like '%%未更新%%'
                              or coalesce(bug_status, '') like '%%未复现%%'
                        then 1 else 0 end) as fixed_count,
               count(*) as total_count
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and coalesce(category, '') not like '%%功能屏蔽%%'
           and coalesce(category, '') not like '%%建议%%'
           and coalesce(bug_status, '') not like '%%已拒绝%%'
           and nullif(btrim(fix_user), '') is not null
           and btrim(fix_user) <> '无合法评论'
           and btrim(fix_user) not like '未设定%%'
           and severity_level in ('LEVEL1', 'LEVEL2', 'LEVEL3')
         group by person_name
         order by total_count desc, person_name
        """.formatted(placeholders);
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> {
          long level1 = rs.getLong("level1_count");
          long level2 = rs.getLong("level2_count");
          long level3 = rs.getLong("level3_count");
          long total = rs.getLong("total_count");
          long fixed = Math.min(total, Math.max(0, rs.getLong("fixed_count")));
          return new FixUserRow(
              rs.getString("person_name"), level1, level2, level3, fixed, total - fixed, total);
        },
        args.toArray());
  }

  private CodeSubmissionTrend codeSubmissionTrend(
      String productVersion, String granularity, String source, String repositoryName) {
    List<CodeSubmissionPoint> points = codeReviewSwitchService.isCodeReviewCompatibilityReadEnabled()
        ? loadMatchCodeTrend(productVersion, granularity, source, repositoryName)
        : loadFormalCodeTrend(productVersion, granularity, source, repositoryName);
    return new CodeSubmissionTrend(granularity, points);
  }

  private List<CodeSubmissionPoint> loadFormalCodeTrend(
      String productVersion, String granularity, String source, String repositoryName) {
    String bucket = "week".equals(granularity) ? "week" : "day";
    String sourcePredicate = switch (source) {
      case "cc" -> "and business_source = 'cc'";
      case "dgm" -> "and business_source = 'dgm'";
      default -> "and business_source in ('cc', 'dgm')";
    };
    String repositoryPredicate = StringUtils.hasText(repositoryName)
        ? "and lower(coalesce(repository_name, '')) = ?" : "";
    String projectPredicate = "and (lower(coalesce(project_name, '')) like ?"
        + " or lower(coalesce(project_name, '')) like ?)";
    String projectPattern = "%" + productVersion.toLowerCase() + "%";
    String legacyProjectPattern = "%" + legacyProjectName(productVersion).toLowerCase() + "%";
    List<Object> args = new ArrayList<>();
    args.add(bucket);
    args.add(projectPattern);
    args.add(legacyProjectPattern);
    if (StringUtils.hasText(repositoryName)) {
      args.add(repositoryName.toLowerCase());
    }
    List<CodeSubmissionPoint> raw = jdbcTemplate.query(
        """
        with ranked as (
          select business_source source_instance,
                 date_trunc(?, merged_at_source)::date period, merge_request_id,
                 greatest(coalesce(added_lines, 0), 0) added_lines,
                 row_number() over (partition by business_source, merge_request_id order by id) rn
            from code_review_formal_records
           where 1 = 1
             %s %s %s
             and lower(coalesce(target_branch, '')) = 'dev'
             and upper(coalesce(merge_request_state, '')) = 'MERGED' and merged_at_source is not null
        )
        select case when source_instance = 'dgm' then 'dgm' else 'cc' end,
               period, sum(case when rn = 1 then added_lines else 0 end), count(distinct merge_request_id)
          from ranked group by source_instance, period order by source_instance, period
        """.formatted(sourcePredicate, projectPredicate, repositoryPredicate),
        (rs, n) -> new CodeSubmissionPoint(
            rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getLong(3), 0, rs.getLong(4)),
        args.toArray());
    return cumulative(raw);
  }

  private List<CodeSubmissionPoint> loadMatchCodeTrend(
      String productVersion, String granularity, String source, String repositoryName) {
    String bucket = "week".equals(granularity) ? "week" : "day";
    String sourcePredicate = switch (source) {
      case "cc" -> "and lower(coalesce(source_instance, '')) <> 'dgm'";
      case "dgm" -> "and lower(coalesce(source_instance, '')) = 'dgm'";
      default -> "";
    };
    String repositoryPredicate = StringUtils.hasText(repositoryName)
        ? "and lower(coalesce(repository_name, '')) = ?" : "";
    String projectPredicate = "and (lower(coalesce(project_name, '')) like ?"
        + " or lower(coalesce(project_name, '')) like ?)";
    List<Object> args = new ArrayList<>(List.of(
        bucket,
        "%" + productVersion.toLowerCase() + "%",
        "%" + legacyProjectName(productVersion).toLowerCase() + "%"));
    if (StringUtils.hasText(repositoryName)) {
      args.add(repositoryName.toLowerCase());
    }
    List<CodeSubmissionPoint> raw = jdbcTemplate.query(
        """
        with ranked as (
          select case when lower(coalesce(source_instance, '')) = 'dgm' then 'dgm' else 'cc' end source_instance,
                 date_trunc(?, merged_at_source)::date period, merge_request_iid, greatest(coalesce(added_lines, 0), 0) added_lines,
                 row_number() over (partition by source_instance, merge_request_iid order by id) rn
            from code_review_match_mode_records
           where 1 = 1 %s %s %s
             and lower(coalesce(target_branch, '')) = 'dev'
             and upper(coalesce(merge_request_state, '')) = 'MERGED' and merged_at_source is not null
        )
        select source_instance, period, sum(case when rn = 1 then added_lines else 0 end), count(distinct merge_request_iid)
          from ranked group by source_instance, period order by source_instance, period
        """.formatted(projectPredicate, sourcePredicate, repositoryPredicate),
        (rs, n) -> new CodeSubmissionPoint(
            rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getLong(3), 0, rs.getLong(4)),
        args.toArray());
    return cumulative(raw);
  }

  private List<CodeSubmissionPoint> cumulative(List<CodeSubmissionPoint> points) {
    Map<String, Long> totals = new LinkedHashMap<>();
    return points.stream().map(point -> {
      long cumulative = totals.merge(point.source(), point.newLines(), Long::sum);
      return new CodeSubmissionPoint(point.source(), point.period(), point.newLines(), cumulative, point.mergeRequestCount());
    }).toList();
  }

  private BoardTable boardTable(StatisticBoardResponse response, Set<String> allowedColumns) {
    List<StatisticColumnLeaf> leaves = response.definition().columnGroups().stream()
        .flatMap(group -> group.leafColumns().stream())
        .filter(column -> allowedColumns.isEmpty() || allowedColumns.contains(column.key()))
        .toList();
    List<BoardColumn> columns = leaves.stream().map(column -> new BoardColumn(column.key(), column.label(), column.metricType())).toList();
    List<BoardRow> rows = response.rows().stream().map(row -> {
      Map<String, BoardCell> cells = new LinkedHashMap<>();
      for (var cell : row.cells()) {
        if (allowedColumns.isEmpty() || allowedColumns.contains(cell.columnKey())) {
          cells.put(cell.columnKey(), new BoardCell(cell.numericValue(), cell.displayValue()));
        }
      }
      return new BoardRow(row.rowKey(), row.rowLabel(), cells);
    }).toList();
    return new BoardTable(response.definition().boardKey(), response.definition().title(), columns, rows);
  }

  private BoardTable loadPhaseFixes(String productVersion, List<String> phases) {
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    List<PhaseFixRow> loaded = jdbcTemplate.query(
        """
        select testing_phase,
               sum(case when severity_level = 'LEVEL1' then 1 else 0 end) as level1_count,
               sum(case when severity_level = 'LEVEL2' then 1 else 0 end) as level2_count,
               sum(case when severity_level = 'LEVEL3' then 1 else 0 end) as level3_count,
               sum(case when coalesce(is_fixed, false) then 1 else 0 end) as fixed_count,
               count(*) as total_count
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and coalesce(category, '') not like '%%功能屏蔽%%'
           and coalesce(category, '') not like '%%建议%%'
           and coalesce(bug_status, '') not like '%%已拒绝%%'
           and severity_level in ('LEVEL1', 'LEVEL2', 'LEVEL3')
         group by testing_phase
        """.formatted(placeholders),
        (rs, rowNum) -> new PhaseFixRow(
            rs.getString("testing_phase"),
            rs.getLong("level1_count"),
            rs.getLong("level2_count"),
            rs.getLong("level3_count"),
            rs.getLong("fixed_count"),
            rs.getLong("total_count")),
        args.toArray());
    Map<String, PhaseFixRow> byPhase = loaded.stream()
        .collect(java.util.stream.Collectors.toMap(
            row -> row.phase().toLowerCase(), row -> row, (left, right) -> left, LinkedHashMap::new));
    List<BoardRow> rows = phases.stream().map(phase -> {
      PhaseFixRow row = byPhase.getOrDefault(phase.toLowerCase(), PhaseFixRow.empty(phase));
      Map<String, BoardCell> cells = new LinkedHashMap<>();
      cells.put("level1", countCell(row.level1()));
      cells.put("level2", countCell(row.level2()));
      cells.put("level3", countCell(row.level3()));
      cells.put("total", countCell(row.total()));
      cells.put("fixed", countCell(row.fixed()));
      cells.put("unfixed", countCell(Math.max(0, row.total() - row.fixed())));
      return new BoardRow(phase, phase, cells);
    }).toList();
    List<BoardColumn> columns = List.of(
        new BoardColumn("level1", "一级缺陷", "count"),
        new BoardColumn("level2", "二级缺陷", "count"),
        new BoardColumn("level3", "三级缺陷", "count"),
        new BoardColumn("total", "缺陷总数", "count"),
        new BoardColumn("fixed", "已修复", "count"),
        new BoardColumn("unfixed", "未修复", "count"));
    return new BoardTable("system-test-phase-statistics", "系统测试各轮次缺陷修复情况", columns, rows);
  }

  private BoardTable severityTable(StatisticBoardResponse response) {
    List<BoardColumn> columns = List.of(
        new BoardColumn("level1", "一级缺陷", "count"),
        new BoardColumn("level2", "二级缺陷", "count"),
        new BoardColumn("level3", "三级缺陷", "count"),
        new BoardColumn("level1Unfixed", "未修复一级缺陷", "count"),
        new BoardColumn("level2Unfixed", "未修复二级缺陷", "count"),
        new BoardColumn("level3Unfixed", "未修复三级缺陷", "count"));
    List<BoardRow> rows = response.rows().stream().map(row -> {
      Map<String, BoardCell> source = row.cells().stream()
          .collect(java.util.stream.Collectors.toMap(
              cell -> cell.columnKey(), cell -> new BoardCell(cell.numericValue(), cell.displayValue()),
              (left, right) -> left, LinkedHashMap::new));
      Map<String, BoardCell> cells = new LinkedHashMap<>();
      cells.put("level1", source.getOrDefault("level1_total", countCell(0)));
      cells.put("level2", source.getOrDefault("level2_total", countCell(0)));
      cells.put("level3", source.getOrDefault("level3_total", countCell(0)));
      cells.put("level1Unfixed", countCell(unfixed(source, "level1_total", "level1_fixed")));
      cells.put("level2Unfixed", countCell(unfixed(source, "level2_total", "level2_fixed")));
      cells.put("level3Unfixed", countCell(unfixed(source, "level3_total", "level3_fixed")));
      return new BoardRow(row.rowKey(), row.rowLabel(), cells);
    }).toList();
    return new BoardTable("system-test-severity", "系统测试缺陷级别分布", columns, rows);
  }

  private long unfixed(Map<String, BoardCell> cells, String totalKey, String fixedKey) {
    return Math.max(0, cells.getOrDefault(totalKey, countCell(0)).numericValue()
        - cells.getOrDefault(fixedKey, countCell(0)).numericValue());
  }

  private BoardCell countCell(long value) {
    return new BoardCell(value, Long.toString(value));
  }

  private record PhaseFixRow(
      String phase, long level1, long level2, long level3, long fixed, long total) {
    private static PhaseFixRow empty(String phase) {
      return new PhaseFixRow(phase, 0, 0, 0, 0, 0);
    }
  }

  private BigDecimal cellNumber(StatisticRowData row, String key) {
    if (row == null) return null;
    return row.cells().stream()
        .filter(cell -> key.equals(cell.columnKey()))
        .map(cell -> parsePercent(cell.displayValue()))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private BigDecimal parsePercent(String value) {
    if (!StringUtils.hasText(value) || "/".equals(value)) return null;
    try { return new BigDecimal(value.replace("%", "").trim()); } catch (NumberFormatException ignored) { return null; }
  }

  private BigDecimal share(long count, long total) {
    return total == 0 ? null : BigDecimal.valueOf(count * 100D / total).setScale(2, RoundingMode.HALF_UP);
  }

  private BigDecimal decimal(Double value) { return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP); }
  private Boolean inRange(Double value, double min, double max) { return value == null ? null : value >= min && value <= max; }
  private Boolean equalsPercent(BigDecimal value, double expected) { return value == null ? null : value.compareTo(BigDecimal.valueOf(expected)) == 0; }
  private Boolean atLeast(BigDecimal value, double expected) { return value == null ? null : value.compareTo(BigDecimal.valueOf(expected)) >= 0; }
  private String required(Map<String, String> parameters, String key) { String value = parameters == null ? null : parameters.get(key); if (!StringUtils.hasText(value)) throw new IllegalArgumentException(key + " 不能为空"); return value.trim(); }
  private String normalizeGranularity(String value) { return "week".equalsIgnoreCase(value) ? "week" : "day"; }
  private String normalizeCodeSource(String value) {
    return switch (value == null ? "" : value.trim().toLowerCase()) {
      case "cc", "product" -> "cc";
      case "dgm", "kernel" -> "dgm";
      default -> "all";
    };
  }
  private String legacyProjectName(String productVersion) {
    return productVersion.toUpperCase().replaceFirst("^CC(\\d{4})(R\\d)$", "CrownCAD $1 $2");
  }
  private String optional(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
}
