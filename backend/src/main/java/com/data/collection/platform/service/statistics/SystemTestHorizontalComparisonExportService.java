package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.ExcelExportStyles;
import com.data.collection.platform.service.ReviewDataMatchModeRecordRepository;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Set;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestHorizontalComparisonExportService {
  private static final String FILTER_GROUP_PARAM = "filterGroup";
  private static final String EXPORT_SHEET_NAME = "系统测试数据分析";
  private static final int HEADER_DEPTH = 3;
  private static final List<ExportColumn> EXPORT_COLUMNS = buildExportColumns();
  private static final List<String> LEGACY_FIXED_STATUS_TOKENS = List.of("已修复", "待合并", "未更新");
  private static final List<String> LEGACY_RESOLVED_STATUS_TOKENS = List.of("已修复/完成", "未复现");
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final ReviewDataMatchModeRecordRepository matchModeReviewRepository;

  public SystemTestHorizontalComparisonExportService(
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      ReviewDataMatchModeRecordRepository matchModeReviewRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.phaseScopeResolver = phaseScopeResolver;
    this.matchModeSwitchService = matchModeSwitchService;
    this.matchModeReviewRepository = matchModeReviewRepository;
  }

  public String exportCsv(Map<String, String> filters) {
    return toCsv(loadRows(filters));
  }

  public byte[] exportWorkbook(Map<String, String> filters) {
    List<HorizontalRow> rows = loadRows(filters);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(EXPORT_SHEET_NAME);
      CellStyle headerStyle = ExcelExportStyles.createHeaderStyle(workbook);
      CellStyle bodyStyle = ExcelExportStyles.createBodyStyle(workbook);
      writeWorkbookRows(sheet, rows, headerStyle, bodyStyle);
      sheet.createFreezePane(1, HEADER_DEPTH);
      ExcelExportStyles.applyHeaderRows(sheet, HEADER_DEPTH);
      ExcelExportStyles.autoSizeColumns(sheet, EXPORT_COLUMNS.size());
      workbook.write(outputStream);
      return outputStream.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("系统测试横向对比导出失败", error);
    }
  }

  public String exportFilename(Map<String, String> filters) {
    ExportScope scope = ExportScope.from(filters, parseFilterGroup(filters));
    if (StringUtils.hasText(scope.testingPhase())) {
      return scope.testingPhase() + "-系统测试数据横向对比数据.xlsx";
    }
    if (StringUtils.hasText(scope.projectName())) {
      return scope.projectName() + "-系统测试数据横向对比数据.xlsx";
    }
    return "系统测试数据横向对比数据.xlsx";
  }

  private List<HorizontalRow> loadRows(Map<String, String> filters) {
    ExportScope scope = ExportScope.from(filters, parseFilterGroup(filters));
    //兼容模式-MatchMode：一次导出固定一次代码走查读模式，避免 CC/DGM 两段查询跨模式读取。
    CodeReviewDataReadMode codeReviewReadMode =
        matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()
            ? CodeReviewDataReadMode.MATCH_MODE
            : CodeReviewDataReadMode.FORMAL;
    Map<String, HorizontalRow> rows = new LinkedHashMap<>();
    addModuleRows(rows, loadModules(scope, codeReviewReadMode));
    mergeReview(rows, loadReviewMetrics(scope.reviewProjectName(), "需求说明书评审"), true);
    mergeReview(rows, loadReviewMetrics(scope.reviewProjectName(), "设计说明书评审"), false);
    mergeCodeReview(
        rows,
        loadCodeReviewMetrics(scope.codeReviewProjectName(), true, codeReviewReadMode),
        true);
    mergeCodeReview(
        rows,
        loadCodeReviewMetrics(scope.codeReviewProjectName(), false, codeReviewReadMode),
        false);
    mergeIssues(rows, loadIssueSources(scope), scope);
    if (StringUtils.hasText(scope.moduleName())) {
      rows.keySet().removeIf(moduleName -> !moduleName.equalsIgnoreCase(scope.moduleName()));
    }
    List<HorizontalRow> exportRows =
        rows.values().stream()
            .filter(row -> StringUtils.hasText(row.moduleName()))
            .filter(row -> !row.isEmpty())
            .toList();
    return exportRows;
  }

  private void writeWorkbookRows(
      Sheet sheet, List<HorizontalRow> rows, CellStyle headerStyle, CellStyle bodyStyle) {
    writeHeaderRows(sheet, headerStyle);
    int rowIndex = HEADER_DEPTH;
    for (HorizontalRow sourceRow : rows) {
      Row row = sheet.createRow(rowIndex++);
      for (int columnIndex = 0; columnIndex < EXPORT_COLUMNS.size(); columnIndex++) {
        createCell(row, columnIndex, EXPORT_COLUMNS.get(columnIndex).value(sourceRow), bodyStyle);
      }
    }
  }

  private void writeHeaderRows(Sheet sheet, CellStyle headerStyle) {
    for (int headerRowIndex = 0; headerRowIndex < HEADER_DEPTH; headerRowIndex++) {
      sheet.createRow(headerRowIndex);
    }
    for (int columnIndex = 0; columnIndex < EXPORT_COLUMNS.size(); columnIndex++) {
      ExportColumn column = EXPORT_COLUMNS.get(columnIndex);
      List<String> labels = column.normalizedHeader();
      for (int headerRowIndex = 0; headerRowIndex < HEADER_DEPTH; headerRowIndex++) {
        createCell(sheet.getRow(headerRowIndex), columnIndex, labels.get(headerRowIndex), headerStyle);
      }
    }
    mergeHeaderCells(sheet);
  }

  private void mergeHeaderCells(Sheet sheet) {
    for (int columnIndex = 0; columnIndex < EXPORT_COLUMNS.size(); columnIndex++) {
      int rowIndex = 0;
      while (rowIndex < HEADER_DEPTH - 1) {
        String value = cellValue(sheet, rowIndex, columnIndex);
        int endRow = rowIndex;
        while (endRow + 1 < HEADER_DEPTH && value.equals(cellValue(sheet, endRow + 1, columnIndex))) {
          endRow++;
        }
        if (endRow > rowIndex) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, endRow, columnIndex, columnIndex));
        }
        rowIndex = endRow + 1;
      }
    }
    for (int rowIndex = 0; rowIndex < HEADER_DEPTH; rowIndex++) {
      int columnIndex = 0;
      while (columnIndex < EXPORT_COLUMNS.size() - 1) {
        String value = cellValue(sheet, rowIndex, columnIndex);
        int endColumn = columnIndex;
        while (endColumn + 1 < EXPORT_COLUMNS.size() && value.equals(cellValue(sheet, rowIndex, endColumn + 1))) {
          endColumn++;
        }
        if (endColumn > columnIndex && shouldMergeHorizontally(sheet, rowIndex, columnIndex, endColumn)) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, columnIndex, endColumn));
        }
        columnIndex = endColumn + 1;
      }
    }
  }

  private boolean shouldMergeHorizontally(Sheet sheet, int rowIndex, int startColumn, int endColumn) {
    if (!StringUtils.hasText(cellValue(sheet, rowIndex, startColumn))) {
      return false;
    }
    for (int columnIndex = startColumn; columnIndex <= endColumn; columnIndex++) {
      if (rowIndex + 1 < HEADER_DEPTH && !StringUtils.hasText(cellValue(sheet, rowIndex + 1, columnIndex))) {
        return false;
      }
    }
    return true;
  }

  private String cellValue(Sheet sheet, int rowIndex, int columnIndex) {
    Cell cell = sheet.getRow(rowIndex).getCell(columnIndex);
    return cell == null ? "" : cell.getStringCellValue();
  }

  private void createCell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private StatisticFilterGroup parseFilterGroup(Map<String, String> filters) {
    if (filters == null || !StringUtils.hasText(filters.get(FILTER_GROUP_PARAM))) {
      return new StatisticFilterGroup("AND", List.of());
    }
    StatisticFilterGroup parsed =
        jsonUtils.fromJson(filters.get(FILTER_GROUP_PARAM), new TypeReference<>() {});
    if (parsed == null || parsed.conditions() == null) {
      return new StatisticFilterGroup("AND", List.of());
    }
    return parsed;
  }

  private List<String> loadModules(
      ExportScope scope, CodeReviewDataReadMode codeReviewReadMode) {
    Set<String> modules = new LinkedHashSet<>();
    List<Object> reviewArgs = new ArrayList<>();
    StringBuilder reviewSql =
        new StringBuilder(
            """
            select module_name
              from review_records
             where deleted = false
               and nullif(btrim(module_name), '') is not null
            """);
    if (StringUtils.hasText(scope.reviewProjectName())) {
      reviewSql.append("\n   and lower(project_name) like ?");
      reviewArgs.add(like(scope.reviewProjectName()));
    }
    modules.addAll(
        jdbcTemplate.queryForList(
            reviewSql.append("\n group by module_name order by min(id)").toString(),
            String.class,
            reviewArgs.toArray()));

    for (IssueExportSource issue : loadIssueSources(scope.withoutModuleFilter())) {
      modules.addAll(issue.moduleNames());
    }

    modules.addAll(loadCodeReviewModules(scope.codeReviewProjectName(), codeReviewReadMode));
    if (StringUtils.hasText(scope.moduleName())) {
      modules.removeIf(module -> !module.equalsIgnoreCase(scope.moduleName()));
    }
    return modules.stream().filter(StringUtils::hasText).toList();
  }

  List<String> loadCodeReviewModules(
      String projectName, CodeReviewDataReadMode codeReviewReadMode) {
    Set<String> modules = new LinkedHashSet<>();
    for (boolean crownCad : List.of(true, false)) {
      HorizontalCodeReviewQueryScope queryScope =
          resolveCodeReviewQueryScope(projectName, crownCad, codeReviewReadMode);
      if (!queryScope.available()) {
        continue;
      }
      String sql =
          "select module_name from "
              + queryScope.tableName()
              + " where "
              + queryScope.whereClause()
              + " and nullif(btrim(module_name), '') is not null"
              + queryScope.moduleExclusionPredicate()
              + " group by module_name order by min(id)";
      modules.addAll(
          jdbcTemplate.queryForList(sql, String.class, queryScope.args().toArray()));
    }
    return List.copyOf(modules);
  }

  private void addModuleRows(Map<String, HorizontalRow> rows, List<String> modules) {
    for (String module : modules) {
      if (StringUtils.hasText(module)) {
        rows.computeIfAbsent(module, HorizontalRow::new);
      }
    }
  }

  private List<ReviewMetric> loadReviewMetrics(String reviewProjectName, String reviewType) {
    List<Object> args = new ArrayList<>();
    args.add(reviewType);
    StringBuilder sql =
        new StringBuilder(
            """
            select
              r.module_name,
              coalesce(sum(r.review_scale_pages), 0)::integer as review_pages,
              coalesce(sum(problem.problem_count), 0)::integer as defect_count,
              coalesce(sum(problem.doc_specification_count), 0)::integer as doc_specification_count,
              coalesce(sum(problem.integrity_count), 0)::integer as integrity_count,
              coalesce(sum(problem.functionality_count), 0)::integer as functionality_count,
              coalesce(sum(problem.feasibility_count), 0)::integer as feasibility_count,
              coalesce(sum(problem.total_workload_hours), 0)::numeric as workload_hours
            from review_records r
            left join lateral (
              select
                coalesce(sum(workload_hours), 0) as total_workload_hours,
                count(*) filter (
                  where problem_status not in ('已拒绝', '未评审', '无问题')
                    and problem_category <> '无问题'
                )::integer as problem_count,
                count(*) filter (
                  where problem_status not in ('已拒绝', '未评审', '无问题')
                    and problem_category = '文档规范'
                )::integer as doc_specification_count,
                count(*) filter (
                  where problem_status not in ('已拒绝', '未评审', '无问题')
                    and problem_category = '完整性'
                )::integer as integrity_count,
                count(*) filter (
                  where problem_status not in ('已拒绝', '未评审', '无问题')
                    and problem_category = '功能性'
                )::integer as functionality_count,
                count(*) filter (
                  where problem_status not in ('已拒绝', '未评审', '无问题')
                    and problem_category = '可行性'
                )::integer as feasibility_count
              from review_problem_items
              where review_record_id = r.id and deleted = false
            ) problem on true
            where r.deleted = false
              and r.review_type = ?
            """);
    if (StringUtils.hasText(reviewProjectName)) {
      sql.append("\n      and lower(r.project_name) like ?");
      args.add(like(reviewProjectName));
    }
    sql.append("\n    group by r.module_name");
    List<ReviewMetric> metrics = new ArrayList<>(jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new ReviewMetric(
                text(rs.getString("module_name")),
                rs.getInt("review_pages"),
                rs.getInt("defect_count"),
                rs.getInt("doc_specification_count"),
                rs.getInt("integrity_count"),
                rs.getInt("functionality_count"),
                rs.getInt("feasibility_count"),
                rs.getBigDecimal("workload_hours") == null ? 0D : rs.getBigDecimal("workload_hours").doubleValue()),
        args.toArray()));
    metrics.addAll(loadMatchModeReviewMetrics(reviewProjectName, reviewType));
    return aggregateReviewMetrics(metrics);
  }

  private List<CodeReviewMetric> loadCodeReviewMetrics(
      String projectName, boolean crownCad, CodeReviewDataReadMode codeReviewReadMode) {
    HorizontalCodeReviewQueryScope queryScope =
        resolveCodeReviewQueryScope(projectName, crownCad, codeReviewReadMode);
    if (!queryScope.available()) {
      return List.of();
    }
    StringBuilder sql =
        new StringBuilder(
            """
            select
              module_name,
              coalesce(sum(case when coalesce(defect_count, 0) = -1 then 0 else coalesce(defect_count, 0) end), 0)::integer as defect_count,
              coalesce(sum(added_lines), 0)::integer as added_lines,
              coalesce(sum(code_specification_count), 0)::integer as code_specification_count,
              coalesce(sum(code_logic_specification_count), 0)::integer as code_logic_specification_count,
              coalesce(sum(design_specification_count), 0)::integer as design_specification_count,
              coalesce(sum(performance_specification_count), 0)::integer as performance_specification_count,
              coalesce(sum(other_specification_count), 0)::integer as other_specification_count,
              coalesce(sum(review_duration_minutes), 0)::integer as review_duration_minutes,
              coalesce(avg(review_efficiency_per_hour), 0)::numeric as review_efficiency_per_hour,
              coalesce(avg(review_speed_loc_per_hour), 0)::numeric as review_speed_loc_per_hour
            from (
              select
                module_name,
                defect_count,
                case
                  when row_number() over(partition by %s order by id asc) = 1
                  then added_lines
                  else 0
                end as added_lines,
                code_specification_count,
                code_logic_specification_count,
                design_specification_count,
                performance_specification_count,
                other_specification_count,
                review_duration_minutes,
                review_efficiency_per_hour,
                review_speed_loc_per_hour
              from %s
              where %s
            """
                .formatted(
                    queryScope.mergeRequestIdentity(),
                    queryScope.tableName(),
                    queryScope.whereClause()));
    sql.append("\n    ) scoped")
        .append(queryScope.scopedModulePredicate())
        .append("\n    group by module_name");
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new CodeReviewMetric(
                text(rs.getString("module_name")),
                rs.getInt("defect_count"),
                rs.getInt("added_lines"),
                rs.getInt("code_specification_count"),
                rs.getInt("code_logic_specification_count"),
                rs.getInt("design_specification_count"),
                rs.getInt("performance_specification_count"),
                rs.getInt("other_specification_count"),
                rs.getInt("review_duration_minutes"),
                rs.getBigDecimal("review_efficiency_per_hour") == null
                    ? 0D
                    : rs.getBigDecimal("review_efficiency_per_hour").doubleValue(),
                rs.getBigDecimal("review_speed_loc_per_hour") == null
                    ? 0D
                    : rs.getBigDecimal("review_speed_loc_per_hour").doubleValue()),
        queryScope.args().toArray());
  }

  private List<ReviewMetric> loadMatchModeReviewMetrics(String reviewProjectName, String reviewType) {
    List<ReviewDataRecordRowResponse> records = matchModeReviewRepository.loadRecords().stream()
        .filter(record -> matchesText(record.projectName(), reviewProjectName))
        .filter(record -> matchesText(record.reviewType(), reviewType))
        .toList();
    if (records.isEmpty()) {
      return List.of();
    }
    List<ReviewMetric> metrics = new ArrayList<>();
    for (ReviewDataRecordRowResponse record : records) {
      //兼容模式-MatchMode：ReviewDataMatchModeRecordRepository 已按老平台 refreshAttribute 口径重算有效问题数；
      //横向对比直接使用记录级有效分类数，避免重新按全部 problem 明细统计而与正式 SQL 分叉。
      metrics.add(
          new ReviewMetric(
              text(record.moduleName()),
              positive(record.reviewScalePages()),
              positive(record.problemCount()),
              positive(record.docSpecificationCount()),
              positive(record.integrityCount()),
              positive(record.functionalityCount()),
              positive(record.feasibilityCount()),
              0D));
    }
    return metrics;
  }

  private List<ReviewMetric> aggregateReviewMetrics(List<ReviewMetric> metrics) {
    Map<String, ReviewMetricAccumulator> grouped = new LinkedHashMap<>();
    for (ReviewMetric metric : metrics) {
      if (!StringUtils.hasText(metric.moduleName())) {
        continue;
      }
      grouped.computeIfAbsent(metric.moduleName(), ReviewMetricAccumulator::new).add(metric);
    }
    return grouped.values().stream().map(ReviewMetricAccumulator::toMetric).toList();
  }

  private HorizontalCodeReviewQueryScope resolveCodeReviewQueryScope(
      String projectName, boolean crownCad, CodeReviewDataReadMode codeReviewReadMode) {
    String tableName;
    String mergeRequestIdentity;
    String moduleExclusionPredicate;
    String scopedModulePredicate;
    StringBuilder whereClause = new StringBuilder();
    List<Object> args = new ArrayList<>();
    if (codeReviewReadMode == CodeReviewDataReadMode.MATCH_MODE) {
      //兼容模式-MatchMode：模块目录和指标聚合共用本读源边界；删除兼容模式时整体删除此分支。
      //兼容模式-MatchMode：source_instance 仍需叠加 repository_name，避免 CC 旧库中的其他仓库串入 CrownCAD。
      tableName = "code_review_match_mode_records";
      mergeRequestIdentity = "merge_request_iid";
      moduleExclusionPredicate = " and module_name not in ('无需标注', '未标注模块名')";
      scopedModulePredicate =
          "\n    where nullif(btrim(coalesce(module_name, '')), '') is not null"
              + moduleExclusionPredicate;
      whereClause
          .append("lower(coalesce(source_instance, '')) = ?")
          .append(" and lower(btrim(coalesce(repository_name, ''))) = ?");
      args.add(crownCad ? "cc" : "dgm");
      args.add(crownCad ? "crowncad" : "dgm");
    } else {
      tableName = "code_review_formal_records";
      mergeRequestIdentity = "project_id, merge_request_id";
      moduleExclusionPredicate = "";
      scopedModulePredicate = "";
      whereClause
          .append("lower(coalesce(business_source, '')) = ?");
      args.add(crownCad ? "cc" : "dgm");
    }
    whereClause
        .append(" and lower(coalesce(target_branch, '')) = 'dev'")
        .append(" and upper(coalesce(merge_request_state, '')) = 'MERGED'");

    List<String> projectNames = codeReviewProjectNames(projectName, crownCad);
    if (!projectNames.isEmpty()) {
      whereClause
          .append(" and (")
          .append(String.join(
              " or ",
              projectNames.stream().map(ignored -> "lower(btrim(project_name)) = ?").toList()))
          .append(")");
      projectNames.forEach(value -> args.add(value.toLowerCase(Locale.ROOT)));
    }
    return new HorizontalCodeReviewQueryScope(
        true,
        tableName,
        mergeRequestIdentity,
        whereClause.toString(),
        List.copyOf(args),
        moduleExclusionPredicate,
        scopedModulePredicate);
  }

  private List<IssueExportSource> loadIssueSources(ExportScope scope) {
    List<String> resolvedPhases = resolvedTestingPhases(scope.testingPhase());
    if (resolvedPhases.isEmpty()) {
      return List.of();
    }
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    predicates.add("deleted = false");
    predicates.add(resolvedPhasePredicate(resolvedPhases, args));
    predicates.add("((" + SystemTestSuggestionMetricSupport.regularMetricSql(null) + ") or ("
        + SystemTestSuggestionMetricSupport.suggestionMetricSql(null) + "))");
    if (StringUtils.hasText(scope.projectName())) {
      predicates.add("lower(coalesce(project_name, '')) like ?");
      args.add(like(scope.projectName()));
    }
    if (StringUtils.hasText(scope.moduleName())) {
      predicates.add("lower(',' || replace(coalesce(module_names, ''), ', ', ',') || ',') like ?");
      args.add("%," + scope.moduleName().toLowerCase(Locale.ROOT) + ",%");
    }
    return jdbcTemplate.query(
        """
        select
          issue_iid,
          title,
          issue_state,
          closed_at_source,
          module_names,
          severity_level,
          priority_level,
          bug_status,
          category,
          is_excluded,
          exclusion_reason,
          reason_category,
          raw_payload,
          delay_issue,
          is_regression,
          is_crash,
          is_level1_other,
          label_names
        from issue_fact
        where
        """
            + String.join(" and ", predicates)
            + "\norder by id",
        this::mapIssueSource,
        args.toArray());
  }

  private List<String> resolvedTestingPhases(String selectedPhase) {
    String phase = StringUtils.hasText(selectedPhase)
        ? selectedPhase
        : phaseScopeResolver.listEnabledLegacyCrownCadParentNames().stream()
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("");
    if (!StringUtils.hasText(phase)) {
      return List.of();
    }
    return phaseScopeResolver.resolveLegacyCrownCadPhases(phase);
  }

  private String resolvedPhasePredicate(List<String> resolvedPhases, List<Object> args) {
    List<String> parts = new ArrayList<>();
    for (String phase : resolvedPhases) {
      String normalized = phase.toLowerCase(Locale.ROOT);
      parts.add("lower(coalesce(testing_phase, '')) = ?");
      args.add(normalized);
    }
    return "(" + String.join(" or ", parts) + ")";
  }

  private IssueExportSource mapIssueSource(ResultSet rs, int rowNum) throws SQLException {
    return new IssueExportSource(
        rs.getLong("issue_iid"),
        text(rs.getString("title")),
        text(rs.getString("issue_state")),
        rs.getTimestamp("closed_at_source") != null,
        splitValues(rs.getString("module_names")),
        text(rs.getString("severity_level")),
        text(rs.getString("priority_level")),
        text(rs.getString("bug_status")),
        text(rs.getString("category")),
        rs.getBoolean("is_excluded"),
        text(rs.getString("exclusion_reason")),
        text(rs.getString("reason_category")),
        text(rs.getString("raw_payload")),
        rs.getBoolean("delay_issue"),
        rs.getBoolean("is_regression"),
        rs.getBoolean("is_crash"),
        rs.getBoolean("is_level1_other"),
        splitValues(rs.getString("label_names")));
  }

  private void mergeReview(Map<String, HorizontalRow> rows, List<ReviewMetric> metrics, boolean demand) {
    for (ReviewMetric metric : metrics) {
      if (!StringUtils.hasText(metric.moduleName())) {
        continue;
      }
      HorizontalRow row = rows.computeIfAbsent(metric.moduleName(), HorizontalRow::new);
      if (demand) {
        row.demandReview = metric;
      } else {
        row.designReview = metric;
      }
    }
  }

  private void mergeCodeReview(
      Map<String, HorizontalRow> rows, List<CodeReviewMetric> metrics, boolean crownCad) {
    for (CodeReviewMetric metric : metrics) {
      if (!StringUtils.hasText(metric.moduleName())) {
        continue;
      }
      HorizontalRow row = rows.computeIfAbsent(metric.moduleName(), HorizontalRow::new);
      if (crownCad) {
        row.crownCadCodeReview = metric;
      } else {
        row.dgmCodeReview = metric;
      }
    }
  }

  private void mergeIssues(
      Map<String, HorizontalRow> rows, List<IssueExportSource> issues, ExportScope scope) {
    long overall = issues.stream().filter(IssueExportSource::isRegularMetricIssue).count();
    for (IssueExportSource issue : issues) {
      for (String moduleName : issue.moduleNames()) {
        if (StringUtils.hasText(scope.moduleName()) && !moduleName.equalsIgnoreCase(scope.moduleName())) {
          continue;
        }
        rows.computeIfAbsent(moduleName, HorizontalRow::new).issues.add(issue);
      }
    }
    long causeTotal =
        rows.values().stream()
            .mapToLong(row -> DefectCauseMetricCatalog.METRICS.stream().mapToLong(row::reasonCount).sum())
            .sum();
    for (HorizontalRow row : rows.values()) {
      row.issueOverallCount = overall;
      row.issueCauseTotal = causeTotal;
    }
  }

  private String toCsv(List<HorizontalRow> rows) {
    StringBuilder builder = new StringBuilder();
    StringJoiner header = new StringJoiner(",");
    EXPORT_COLUMNS.forEach(column -> header.add(csv(column.flatHeader())));
    builder.append(header).append('\n');
    for (HorizontalRow row : rows) {
      StringJoiner values = new StringJoiner(",");
      EXPORT_COLUMNS.forEach(column -> values.add(csv(column.value(row))));
      builder.append(values).append('\n');
    }
    return builder.toString();
  }

  private static String csv(String value) {
    if (value == null) {
      return "";
    }
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String like(String value) {
    String normalized = text(value);
    return StringUtils.hasText(normalized) ? "%" + normalized.toLowerCase(Locale.ROOT) + "%" : null;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean matchesText(String value, String expected) {
    return !StringUtils.hasText(expected)
        || text(value).toLowerCase(Locale.ROOT).contains(text(expected).toLowerCase(Locale.ROOT));
  }

  private static int positive(Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }

  private static List<String> codeReviewProjectNames(String projectName, boolean crownCad) {
    String normalized = text(projectName);
    if (!StringUtils.hasText(normalized)) {
      return List.of();
    }
    if (crownCad) {
      return List.of(normalized);
    }
    String legacyDgmName = normalized.replaceFirst("^CC(\\d{4})(R\\d)$", "CrownCAD $1 $2");
    if (legacyDgmName.equals(normalized)) {
      return List.of(normalized);
    }
    return List.of(normalized, legacyDgmName);
  }

  private static List<String> splitValues(String value) {
    String normalized = text(value);
    if (!StringUtils.hasText(normalized)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String item : normalized.split("[,，、&]")) {
      String trimmed = text(item);
      if (StringUtils.hasText(trimmed) && !values.contains(trimmed)) {
        values.add(trimmed);
      }
    }
    return values;
  }

  private static String count(long value) {
    return Long.toString(value);
  }

  private static String decimal(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static String codeReviewDensity(long defectCount, long lineCount) {
    if (lineCount <= 0) {
      return "0.00";
    }
    return decimal(defectCount * 1000D / lineCount);
  }

  private static String rate(long numerator, long denominator) {
    if (denominator <= 0) {
      return "/";
    }
    return decimal(numerator * 100D / denominator);
  }

  private static String ratio(long numerator, long denominator) {
    if (denominator <= 0) {
      return "0.00";
    }
    return decimal(numerator * 100D / denominator);
  }

  private static boolean containsAny(String value, List<String> tokens) {
    return tokens.stream().anyMatch(token -> StringUtils.hasText(value) && value.contains(token));
  }

  private static ReviewMetric emptyReview() {
    return new ReviewMetric("", 0, 0, 0, 0, 0, 0, 0D);
  }

  private static CodeReviewMetric emptyCodeReview() {
    return new CodeReviewMetric("", 0, 0, 0, 0, 0, 0, 0, 0, 0D, 0D);
  }

  private static List<ExportColumn> buildExportColumns() {
    List<ExportColumn> columns = new ArrayList<>();
    columns.add(column("模块名称", HorizontalRow::moduleName));
    addReviewColumns(columns, "需求评审数据", "需求评审", "需求文档页数", HorizontalRow::demandReview);
    addReviewColumns(columns, "设计评审数据", "设计评审", "设计文档页数", HorizontalRow::designReview);
    addCodeReviewColumns(columns, "代码走查数据(CrownCAD)", HorizontalRow::crownCadCodeReview);
    addCodeReviewColumns(columns, "代码走查数据(dgm)", HorizontalRow::dgmCodeReview);
    addCauseGroupColumns(columns, "需求问题");
    addCauseGroupColumns(columns, "设计问题");
    addCauseGroupColumns(columns, "编码规范");
    addCauseGroupColumns(columns, "打包问题");
    addCauseGroupColumns(columns, "依赖问题");
    addCauseGroupColumns(columns, "精度问题");
    columns.add(column("一级缺陷", "分类", "回退", row -> count(row.level1Regression())));
    columns.add(column("一级缺陷", "分类", "挂机", row -> count(row.level1Crash())));
    columns.add(column("一级缺陷", "分类", "其他", row -> count(row.level1Other())));
    columns.add(column("一级缺陷", "一级缺陷已修复数量", row -> count(row.level1Fixed())));
    columns.add(column("一级缺陷", "一级缺陷数量(个)", row -> count(row.level1())));
    columns.add(column("一级缺陷", "一级缺陷修复率（%）", row -> rate(row.level1Fixed(), row.level1())));
    columns.add(column("二级缺陷", "二级缺陷已修复数量", row -> count(row.level2Fixed())));
    columns.add(column("二级缺陷", "二级缺陷（个）", row -> count(row.level2())));
    columns.add(column("二级缺陷", "二级缺陷修复率(%)", row -> rate(row.level2Fixed(), row.level2())));
    columns.add(column("三级缺陷", "三级缺陷修复数量", row -> count(row.level3Fixed())));
    columns.add(column("三级缺陷", "三级缺陷(个)", row -> count(row.level3())));
    columns.add(column("三级缺陷", "三级缺陷修复率(%)", row -> rate(row.level3Fixed(), row.level3())));
    columns.add(column("建议类缺陷(个)", row -> count(row.suggestion())));
    columns.add(column("P1", "P1级别缺陷", row -> count(row.priorityCount("P1"))));
    columns.add(column("P1", "P1缺陷修复率(%)", row -> rate(row.priorityFixed("P1"), row.priorityCount("P1"))));
    columns.add(column("P1", "P1缺陷关闭率(%)", row -> rate(row.priorityClosed("P1"), row.priorityCount("P1"))));
    columns.add(column("P2", "P2级别缺陷", row -> count(row.priorityCount("P2"))));
    columns.add(column("P2", "P2缺陷修复率(%)", row -> rate(row.priorityFixed("P2"), row.priorityCount("P2"))));
    columns.add(column("P3", "P3级别缺陷", row -> count(row.priorityCount("P3"))));
    columns.add(column("P3", "P3缺陷修复率(%)", row -> rate(row.priorityFixed("P3"), row.priorityCount("P3"))));
    columns.add(column("模块总缺陷数(个)", row -> count(row.total())));
    columns.add(column("缺陷占比(%)", row -> rate(row.total(), row.issueOverallCount())));
    columns.add(column("延期缺陷占比(%)", row -> rate(row.delayIssueCount(), row.total())));
    columns.add(column("已修复/未更新", row -> count(row.fixed())));
    columns.add(column("修复率(%)", row -> rate(row.fixed(), row.total())));
    columns.add(column("关闭率(%)", row -> rate(row.closed(), row.total())));
    columns.add(column("未关闭缺陷数(个)", row -> count(row.open())));
    columns.add(column("申请延期(个)", row -> count(row.extension())));
    columns.add(column("复测未通过缺陷数(个)", row -> count(row.retestFailed())));
    columns.add(column("新发议题", "新发议题修复数量", row -> count(row.newFixed())));
    columns.add(column("新发议题", "新发议题数量", row -> count(row.newIssues())));
    columns.add(column("新发议题", "新发缺陷修复率(%)", row -> rate(row.newFixed(), row.newIssues())));
    columns.add(column("新发议题", "新发缺陷关闭率(%)", row -> rate(row.newClosed(), row.newIssues())));
    columns.add(column("遗留率", "一级缺陷遗留率(%)", row -> rate(row.level1() - row.level1Fixed(), row.level1())));
    columns.add(column("遗留率", "二级缺陷遗留数量", row -> count(row.level2Open())));
    columns.add(column("遗留率", "三级缺陷遗留数量", row -> count(row.level3Open())));
    columns.add(column("遗留率", "二三级缺陷遗留率(%)", row -> rate(row.level23Fixed(), row.total())));
    return List.copyOf(columns);
  }

  private static void addReviewColumns(
      List<ExportColumn> columns,
      String group,
      String labelPrefix,
      String pagesLabel,
      Function<HorizontalRow, ReviewMetric> metricGetter) {
    columns.add(column(group, labelPrefix + "缺陷密度(个/页)", row -> metricGetter.apply(row).density()));
    columns.add(column(group, pagesLabel, row -> count(metricGetter.apply(row).reviewPages())));
    columns.add(column(group, labelPrefix + "缺陷数", row -> count(metricGetter.apply(row).defectCount())));
    columns.add(column(group, "文档规范", row -> count(metricGetter.apply(row).docSpecification())));
    columns.add(column(group, "完整性", row -> count(metricGetter.apply(row).integrity())));
    columns.add(column(group, "功能性", row -> count(metricGetter.apply(row).functionality())));
    columns.add(column(group, "可行性", row -> count(metricGetter.apply(row).feasibility())));
  }

  private static void addCodeReviewColumns(
      List<ExportColumn> columns,
      String sourceGroup,
      Function<HorizontalRow, CodeReviewMetric> metricGetter) {
    columns.add(column("代码走查", sourceGroup, "代码走查缺陷密度(个/KLOC)", row -> metricGetter.apply(row).density()));
    columns.add(column("代码走查", sourceGroup, "代码走查行数", row -> count(metricGetter.apply(row).addedLines())));
    columns.add(column("代码走查", sourceGroup, "代码走查缺陷合计(个)", row -> count(metricGetter.apply(row).defectSum())));
    columns.add(column("代码走查", sourceGroup, "规范类缺陷数(个)", row -> count(metricGetter.apply(row).codeSpecification())));
    columns.add(column("代码走查", sourceGroup, "逻辑类缺陷数(个)", row -> count(metricGetter.apply(row).codeLogicSpecification())));
    columns.add(column("代码走查", sourceGroup, "设计类缺陷数(个)", row -> count(metricGetter.apply(row).designSpecification())));
    columns.add(column("代码走查", sourceGroup, "性能类缺陷数(个)", row -> count(metricGetter.apply(row).performanceSpecification())));
    columns.add(column("代码走查", sourceGroup, "其他类缺陷数(个)", row -> count(metricGetter.apply(row).otherSpecification())));
  }

  private static void addCauseGroupColumns(List<ExportColumn> columns, String groupLabel) {
    String exportGroupLabel = "编码规范".equals(groupLabel) ? "编码问题" : groupLabel;
    DefectCauseMetricCatalog.METRICS.stream()
        .filter(metric -> groupLabel.equals(metric.groupLabel()))
        .forEach(metric -> {
          columns.add(column(exportGroupLabel, metric.label(), "个数", row -> count(row.reasonCount(metric))));
          columns.add(column(exportGroupLabel, metric.label(), "占比(%)", row -> rate(row.reasonCount(metric), row.issueCauseTotal())));
        });
    columns.add(column(exportGroupLabel, "合计", row -> count(row.reasonGroupTotal(groupLabel))));
    columns.add(column(exportGroupLabel, "占比(%)", row -> rate(row.reasonGroupTotal(groupLabel), row.issueCauseTotal())));
  }

  private static ExportColumn column(String header, Function<HorizontalRow, String> valueGetter) {
    return new ExportColumn(List.of(header), valueGetter);
  }

  private static ExportColumn column(String group, String header, Function<HorizontalRow, String> valueGetter) {
    return new ExportColumn(List.of(group, header), valueGetter);
  }

  private static ExportColumn column(
      String group,
      String subgroup,
      String header,
      Function<HorizontalRow, String> valueGetter) {
    return new ExportColumn(List.of(group, subgroup, header), valueGetter);
  }

  private record ExportColumn(List<String> header, Function<HorizontalRow, String> valueGetter) {
    List<String> normalizedHeader() {
      if (header.size() == HEADER_DEPTH) {
        return header;
      }
      List<String> labels = new ArrayList<>(header);
      String last = labels.get(labels.size() - 1);
      while (labels.size() < HEADER_DEPTH) {
        labels.add(last);
      }
      return labels;
    }

    String flatHeader() {
      return String.join("-", header);
    }

    String value(HorizontalRow row) {
      return valueGetter.apply(row);
    }
  }

  private record HorizontalCodeReviewQueryScope(
      boolean available,
      String tableName,
      String mergeRequestIdentity,
      String whereClause,
      List<Object> args,
      String moduleExclusionPredicate,
      String scopedModulePredicate) {
    private static HorizontalCodeReviewQueryScope unavailable() {
      return new HorizontalCodeReviewQueryScope(
          false, "", "", "", List.of(), "", "");
    }
  }

  private record ExportScope(
      String projectName,
      String reviewProjectName,
      String codeReviewProjectName,
      String testingPhase,
      String moduleName) {
    static ExportScope from(Map<String, String> filters, StatisticFilterGroup filterGroup) {
      String project = firstConditionValue(filterGroup, "projectName");
      String phase = firstConditionValue(filterGroup, "testingPhase");
      String module = firstConditionValue(filterGroup, "moduleName");
      if (!StringUtils.hasText(project) && filters != null) {
        project = text(filters.get("projectName"));
      }
      if (!StringUtils.hasText(phase) && filters != null) {
        phase = text(filters.get("testingPhase"));
      }
      if (!StringUtils.hasText(module) && filters != null) {
        module = text(filters.get("moduleName"));
      }
      String reviewProject = StringUtils.hasText(project) ? project : phase;
      String codeReviewProject = StringUtils.hasText(project) ? project : phase;
      return new ExportScope(
          blankToNull(project),
          reviewProjectName(reviewProject),
          blankToNull(codeReviewProject),
          blankToNull(phase),
          blankToNull(module));
    }

    ExportScope withoutModuleFilter() {
      return new ExportScope(projectName, reviewProjectName, codeReviewProjectName, testingPhase, null);
    }

    private static String firstConditionValue(StatisticFilterGroup filterGroup, String fieldKey) {
      if (filterGroup == null || filterGroup.conditions() == null) {
        return "";
      }
      return filterGroup.conditions().stream()
          .filter(condition -> condition != null && fieldKey.equals(condition.fieldKey()))
          .filter(condition -> "eq".equals(condition.operator()) || "contains".equals(condition.operator()))
          .map(StatisticFilterCondition::value)
          .filter(StringUtils::hasText)
          .findFirst()
          .orElse("");
    }

    private static String reviewProjectName(String projectName) {
      return "CC2025R1".equals(projectName) ? "CC2025R1&R2" : blankToNull(projectName);
    }

    private static String blankToNull(String value) {
      return StringUtils.hasText(value) ? value.trim() : null;
    }
  }

  private record ReviewMetric(
      String moduleName,
      int reviewPages,
      int defectCount,
      int docSpecification,
      int integrity,
      int functionality,
      int feasibility,
      double workloadHours) {
    String density() {
      if (reviewPages <= 0) {
        return "0.00";
      }
      return decimal(defectCount * 1D / reviewPages);
    }

    String categoryRate(long value) {
      return ratio(value, defectCount);
    }

    String efficiency() {
      if (workloadHours <= 0D) {
        return "0.00";
      }
      return decimal(defectCount / workloadHours);
    }

    String speed() {
      if (workloadHours <= 0D) {
        return "0.00";
      }
      return decimal(reviewPages / workloadHours);
    }
  }

  private static final class ReviewMetricAccumulator {
    private final String moduleName;
    private int reviewPages;
    private int defectCount;
    private int docSpecification;
    private int integrity;
    private int functionality;
    private int feasibility;
    private double workloadHours;

    private ReviewMetricAccumulator(String moduleName) {
      this.moduleName = moduleName;
    }

    private void add(ReviewMetric metric) {
      reviewPages += metric.reviewPages();
      defectCount += metric.defectCount();
      docSpecification += metric.docSpecification();
      integrity += metric.integrity();
      functionality += metric.functionality();
      feasibility += metric.feasibility();
      workloadHours += metric.workloadHours();
    }

    private ReviewMetric toMetric() {
      return new ReviewMetric(
          moduleName,
          reviewPages,
          defectCount,
          docSpecification,
          integrity,
          functionality,
          feasibility,
          workloadHours);
    }
  }

  private record CodeReviewMetric(
      String moduleName,
      int defectCount,
      int addedLines,
      int codeSpecification,
      int codeLogicSpecification,
      int designSpecification,
      int performanceSpecification,
      int otherSpecification,
      int reviewDurationMinutes,
      double reviewEfficiencyPerHour,
      double reviewSpeedLocPerHour) {
    int defectSum() {
      return defectCount;
    }

    String density() {
      return codeReviewDensity(defectSum(), addedLines);
    }

    String categoryRate(long value) {
      return ratio(value, defectSum());
    }

    String workloadHours() {
      if (reviewDurationMinutes <= 0) {
        return "0.00";
      }
      return decimal(reviewDurationMinutes / 60D);
    }

    String efficiency() {
      if (reviewEfficiencyPerHour > 0D) {
        return decimal(reviewEfficiencyPerHour);
      }
      if (reviewDurationMinutes <= 0) {
        return "0.00";
      }
      return decimal(defectSum() / (reviewDurationMinutes / 60D));
    }

    String speed() {
      if (reviewSpeedLocPerHour > 0D) {
        return decimal(reviewSpeedLocPerHour);
      }
      if (reviewDurationMinutes <= 0) {
        return "0.00";
      }
      return decimal(addedLines / (reviewDurationMinutes / 60D));
    }
  }

  private record IssueExportSource(
      long issueIid,
      String title,
      String issueState,
      boolean closedAtPresent,
      List<String> moduleNames,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      boolean excluded,
      String exclusionReason,
      String reasonCategory,
      String rawPayload,
      boolean delayIssue,
      boolean regression,
      boolean crash,
      boolean level1Other,
      List<String> labels) {
    boolean isClosed() {
      return closedAtPresent || "closed".equalsIgnoreCase(issueState);
    }

    boolean isLevel1() {
      return isRegularMetricIssue() && "LEVEL1".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel2() {
      return isRegularMetricIssue() && "LEVEL2".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel3() {
      return isRegularMetricIssue() && "LEVEL3".equalsIgnoreCase(severityLevel);
    }

    /*
     * 横向对比跟随系统测试缺陷汇总的新领导口径：建议类要在“建议类缺陷”列单独展示，
     * 但不能流入严重程度、P1/P2/P3、总数、率和原因列。老平台建议列常为 0 是为了
     * 避免污染其它指标的折中，不是新平台应回退的目标；若未来要改，请先确认业务决策。
     */
    boolean isSuggestion() {
      return SystemTestSuggestionMetricSupport.isSuggestionColumnIssue(excluded, exclusionReason, severityLevel, category);
    }

    boolean isRegularMetricIssue() {
      return SystemTestSuggestionMetricSupport.isRegularMetricIssue(excluded, exclusionReason, severityLevel, category);
    }

    boolean isPriority(String priority) {
      return priority.equalsIgnoreCase(priorityLevel);
    }

    boolean isLegacyFixed() {
      return containsAny(bugStatus, LEGACY_FIXED_STATUS_TOKENS);
    }

    boolean isPriorityFixed() {
      return containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS) || isClosed();
    }

    boolean isPriorityClosedWithResolvedStatus() {
      return isClosed() && containsAny(bugStatus, LEGACY_RESOLVED_STATUS_TOKENS);
    }

    boolean isNewIssue() {
      return !bugStatus.contains("历史遗留");
    }

    boolean isNewClosed() {
      return isNewIssue() && isPriorityClosedWithResolvedStatus();
    }

    boolean hasExtensionLabel() {
      return bugStatus.contains("申请延期") || labels.contains("申请延期");
    }

    boolean isRetestFailed() {
      return bugStatus.contains("未修复");
    }

    boolean matchesReason(DefectCauseMetricCatalog.Metric metric) {
      return DefectCauseMetricCatalog.containsAny(reasonCategory, metric.tokens());
    }
  }

  private static final class HorizontalRow {
    private final String moduleName;
    private ReviewMetric demandReview = emptyReview();
    private ReviewMetric designReview = emptyReview();
    private CodeReviewMetric crownCadCodeReview = emptyCodeReview();
    private CodeReviewMetric dgmCodeReview = emptyCodeReview();
    private final List<IssueExportSource> issues = new ArrayList<>();
    private long issueOverallCount;
    private long issueCauseTotal;

    private HorizontalRow(String moduleName) {
      this.moduleName = moduleName;
    }

    String moduleName() {
      return moduleName;
    }

    boolean isEmpty() {
      return demandReview.defectCount() == 0
          && demandReview.reviewPages() == 0
          && designReview.defectCount() == 0
          && designReview.reviewPages() == 0
          && crownCadCodeReview.defectSum() == 0
          && crownCadCodeReview.addedLines() == 0
          && dgmCodeReview.defectSum() == 0
          && dgmCodeReview.addedLines() == 0
          && issues.isEmpty();
    }

    ReviewMetric demandReview() {
      return demandReview;
    }

    ReviewMetric designReview() {
      return designReview;
    }

    CodeReviewMetric crownCadCodeReview() {
      return crownCadCodeReview;
    }

    CodeReviewMetric dgmCodeReview() {
      return dgmCodeReview;
    }

    long issueCauseTotal() {
      return issueCauseTotal;
    }

    long issueOverallCount() {
      return issueOverallCount;
    }

    long reasonCount(DefectCauseMetricCatalog.Metric metric) {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(issue -> issue.matchesReason(metric))
          .count();
    }

    long reasonGroupTotal(String groupLabel) {
      return DefectCauseMetricCatalog.METRICS.stream()
          .filter(metric -> groupLabel.equals(metric.groupLabel()))
          .mapToLong(this::reasonCount)
          .sum();
    }

    long total() {
      return issues.stream().filter(IssueExportSource::isRegularMetricIssue).count();
    }

    long closed() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::isClosed)
          .count();
    }

    long open() {
      return total() - closed();
    }

    long fixed() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::isLegacyFixed)
          .count();
    }

    long level1() {
      return issues.stream().filter(IssueExportSource::isLevel1).count();
    }

    long level1Fixed() {
      return issues.stream().filter(issue -> issue.isLevel1() && issue.isLegacyFixed()).count();
    }

    long level1Regression() {
      return issues.stream().filter(issue -> issue.isLevel1() && issue.regression()).count();
    }

    long level1Crash() {
      return issues.stream().filter(issue -> issue.isLevel1() && issue.crash()).count();
    }

    long level1Other() {
      return issues.stream().filter(issue -> issue.isLevel1() && issue.level1Other()).count();
    }

    long level2() {
      return issues.stream().filter(IssueExportSource::isLevel2).count();
    }

    long level2Fixed() {
      return issues.stream().filter(issue -> issue.isLevel2() && issue.isLegacyFixed()).count();
    }

    long level2Open() {
      return issues.stream().filter(issue -> issue.isLevel2() && !issue.isLegacyFixed()).count();
    }

    long level3() {
      return issues.stream().filter(IssueExportSource::isLevel3).count();
    }

    long level3Fixed() {
      return issues.stream().filter(issue -> issue.isLevel3() && issue.isLegacyFixed()).count();
    }

    long level3Open() {
      return issues.stream().filter(issue -> issue.isLevel3() && !issue.isLegacyFixed()).count();
    }

    long level23Fixed() {
      return issues.stream().filter(issue -> (issue.isLevel2() || issue.isLevel3()) && issue.isLegacyFixed()).count();
    }

    long suggestion() {
      return issues.stream().filter(IssueExportSource::isSuggestion).count();
    }

    long priorityCount(String priority) {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(issue -> issue.isPriority(priority))
          .count();
    }

    long priorityFixed(String priority) {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(issue -> issue.isPriority(priority) && issue.isPriorityFixed())
          .count();
    }

    long priorityClosed(String priority) {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(issue -> issue.isPriority(priority) && issue.isClosed())
          .count();
    }

    long delayIssueCount() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::delayIssue)
          .count();
    }

    long extension() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::hasExtensionLabel)
          .count();
    }

    long retestFailed() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::isRetestFailed)
          .count();
    }

    long newIssues() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::isNewIssue)
          .count();
    }

    long newFixed() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(issue -> issue.isNewIssue() && issue.isLegacyFixed())
          .count();
    }

    long newClosed() {
      return issues.stream()
          .filter(IssueExportSource::isRegularMetricIssue)
          .filter(IssueExportSource::isNewClosed)
          .count();
    }

    String systemTestDefectDensity() {
      long addedLines = (long) crownCadCodeReview.addedLines() + dgmCodeReview.addedLines();
      return codeReviewDensity(total(), addedLines);
    }
  }
}
