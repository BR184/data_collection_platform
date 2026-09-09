package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
//兼容模式-MatchMode：老平台 Mongo 评审兼容表读取仓库。
// 后续彻底删除兼容模式时，本仓库和 review_data_match_mode_* 表可整体移除；正式评审表读写不依赖本类。
public class ReviewDataMatchModeRecordRepository {
  private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("ObjectId\\(['\"]?([^'\")]+)['\"]?\\)");
  private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("[\"']([^\"']+)[\"']");

  private static final String REPORT_COLUMNS = """
      id, legacy_id, project_name, title, module_name, source_type, doc_type, review_type_str,
      review_time, review_charger, review_experts, defect_value, defect_count_sum,
      review_defect_density, weighted_defect_density, review_efficiency, review_rate,
      doc_specification, integrity, functionality, feasibility, not_reach_stand_cause,
      problem_detail_ids, description_ids, create_time
      """;
  private static final String PROBLEM_COLUMNS = """
      id, legacy_id, reviewer, workload, review_type, position, problem_type, description,
      suggestion, liable_person, reason_for_not_accepting, problem_status, create_time, update_time
      """;
  private static final String DESCRIPTION_COLUMNS = """
      id, legacy_id, review_product, version, author, review_scale_pages, unit
      """;
  private static final String CONTENT_COLUMNS = """
      id, match_mode_report_legacy_id, content_order, reviewer_name, assignment_content,
      independent_workload_hours, independent_problem_count, meeting_workload_hours,
      meeting_problem_count
      """;

  private final JdbcTemplate jdbcTemplate;

  public ReviewDataMatchModeRecordRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  //兼容模式-MatchMode
  public List<ReviewDataRecordRowResponse> loadRecords() {
    return buildRows(loadPlatformOwnedLegacyIds()).records();
  }

  //兼容模式-MatchMode
  public List<Long> loadAllPublicRecordIds() {
    return loadReportRows().stream().map(row -> publicRecordId(row.id())).toList();
  }

  //兼容模式-MatchMode：一次加载完整交接源，避免按评审记录重复扫描全部问题与描述。
  public List<MatchModeRecordSource> loadAllRecordSources() {
    Map<String, ProblemRow> problemsByLegacyId = new LinkedHashMap<>();
    loadProblemRows().forEach(row -> problemsByLegacyId.put(row.legacyId(), row));
    Map<String, DescriptionRow> descriptionsByLegacyId = new LinkedHashMap<>();
    loadDescriptionRows().forEach(row -> descriptionsByLegacyId.put(row.legacyId(), row));
    Map<String, List<ContentRow>> contentsByReportLegacyId = new LinkedHashMap<>();
    for (ContentRow row : loadContentRows()) {
      contentsByReportLegacyId
          .computeIfAbsent(row.reportLegacyId(), ignored -> new ArrayList<>())
          .add(row);
    }
    List<MatchModeRecordSource> sources = new ArrayList<>();
    for (ReportRow report : loadReportRows()) {
      List<ProblemRow> problems = report.problemDetailIds().stream()
          .map(problemsByLegacyId::get)
          .filter(Objects::nonNull)
          .toList();
      List<DescriptionRow> descriptions = report.descriptionIds().stream()
          .map(descriptionsByLegacyId::get)
          .filter(Objects::nonNull)
          .toList();
      sources.add(new MatchModeRecordSource(
          report,
          problems,
          descriptions,
          contentsByReportLegacyId.getOrDefault(report.legacyId(), List.of())));
    }
    return List.copyOf(sources);
  }

  //兼容模式-MatchMode
  public Long findMaterializedRecordId(Long matchModeRecordId) {
    Long recordId = jdbcTemplate.query(
        """
        select review_record_id
          from review_data_match_mode_edit_links
         where match_mode_report_id = ?
        """,
        rs -> rs.next() ? rs.getLong("review_record_id") : null,
        storageId(matchModeRecordId));
    if (recordId != null) {
      return recordId;
    }
    String legacyId = findReportLegacyId(matchModeRecordId);
    if (legacyId == null) {
      return null;
    }
    return jdbcTemplate.query(
        """
        select review_record_id
          from review_data_match_mode_edit_links
         where match_mode_report_legacy_id = ?
        """,
        rs -> rs.next() ? rs.getLong("review_record_id") : null,
        legacyId);
  }

  //兼容模式-MatchMode
  public MatchModeRecordSource getRecordSourceOrThrow(Long matchModeRecordId) {
    ReportRow report = loadReportRow(storageId(matchModeRecordId));
    if (report == null) {
      throw matchModeRecordNotFound(matchModeRecordId);
    }
    return new MatchModeRecordSource(
        report,
        loadReportProblems(report),
        loadReportDescriptions(report),
        loadContentRowsByReportLegacyId(report.legacyId()));
  }

  //兼容模式-MatchMode
  public void linkMaterializedRecord(
      Long matchModeRecordId,
      String legacyId,
      Long reviewRecordId) {
    int updated =
        jdbcTemplate.update("""
            update review_data_match_mode_edit_links
               set match_mode_report_id = ?,
                   match_mode_report_legacy_id = ?,
                   review_record_id = ?,
                   authority = 'PLATFORM_OWNED',
                   updated_at = current_timestamp
             where match_mode_report_id = ?
                or match_mode_report_legacy_id = ?
            """,
            storageId(matchModeRecordId),
            legacyId,
            reviewRecordId,
            storageId(matchModeRecordId),
            legacyId);
    if (updated > 0) {
      return;
    }
    jdbcTemplate.update("""
          insert into review_data_match_mode_edit_links(
            match_mode_report_id, match_mode_report_legacy_id, review_record_id, authority,
            created_at, updated_at
          ) values (
            ?, ?, ?, 'PLATFORM_OWNED',
            current_timestamp, current_timestamp
          )
          on conflict (match_mode_report_legacy_id) do update
             set match_mode_report_id = excluded.match_mode_report_id,
                  review_record_id = excluded.review_record_id,
                  authority = 'PLATFORM_OWNED',
                  updated_at = current_timestamp
          """,
        storageId(matchModeRecordId),
        legacyId,
        reviewRecordId);
  }

  //兼容模式-MatchMode
  public void linkMaterializedProblem(
      Long matchModeProblemId,
      String legacyId,
      Long reviewRecordId,
      Long reviewProblemItemId) {
    int updated = jdbcTemplate.update("""
        update review_data_match_mode_problem_edit_links
           set match_mode_problem_id = ?,
               match_mode_problem_legacy_id = ?,
               review_record_id = ?,
               review_problem_item_id = ?,
               updated_at = current_timestamp
         where match_mode_problem_id = ?
            or match_mode_problem_legacy_id = ?
        """,
        storageId(matchModeProblemId),
        legacyId,
        reviewRecordId,
        reviewProblemItemId,
        storageId(matchModeProblemId),
        legacyId);
    if (updated > 0) {
      return;
    }
    jdbcTemplate.update("""
        insert into review_data_match_mode_problem_edit_links(
          match_mode_problem_id, match_mode_problem_legacy_id, review_record_id, review_problem_item_id,
          created_at, updated_at
        ) values (
          ?, ?, ?, ?, current_timestamp, current_timestamp
        )
        on conflict (match_mode_problem_legacy_id) do update
           set match_mode_problem_id = excluded.match_mode_problem_id,
               review_record_id = excluded.review_record_id,
               review_problem_item_id = excluded.review_problem_item_id,
               updated_at = current_timestamp
        """,
        storageId(matchModeProblemId),
        legacyId,
        reviewRecordId,
        reviewProblemItemId);
  }

  //兼容模式-MatchMode
  public Long findMaterializedProblemItemId(Long matchModeProblemItemId) {
    return jdbcTemplate.query(
        """
        select review_problem_item_id
          from review_data_match_mode_problem_edit_links
         where match_mode_problem_id = ?
            or match_mode_problem_legacy_id = (
              select legacy_id from review_data_match_mode_problem_details where id = ?
            )
         order by id
         limit 1
        """,
        rs -> rs.next() ? rs.getLong("review_problem_item_id") : null,
        storageId(matchModeProblemItemId),
        storageId(matchModeProblemItemId));
  }

  //兼容模式-MatchMode
  public ReviewDataRecordRowResponse getRecordOrThrow(Long recordId) {
    ReportRow report = loadReportRow(storageId(recordId));
    if (report == null) {
      throw matchModeRecordNotFound(recordId);
    }
    return buildRecordRow(report, loadReportProblems(report), loadReportDescriptions(report));
  }

  //兼容模式-MatchMode：同步每轮后老平台行键稳定，此处缺失通常意味着页面持有
  // 已被重新同步取代的旧引用——按业务语义返回 400 引导刷新，而不是数据库异常。
  private BizException matchModeRecordNotFound(Long matchModeRecordId) {
    return new BizException("该评审记录已被重新同步，请刷新列表后重试");
  }

  //兼容模式-MatchMode
  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    ReportRow report = loadReportRow(storageId(recordId));
    if (report == null) {
      return List.of();
    }
    Long publicRecordId = publicRecordId(storageId(recordId));
    return loadReportProblems(report).stream()
        .map(problem -> toProblemResponse(publicRecordId, problem))
        .toList();
  }

  //兼容模式-MatchMode
  public List<String> listRecordExperts(Long recordId) {
    ReportRow report = loadReportRow(storageId(recordId));
    return report == null ? List.of() : report.reviewExperts();
  }

  //兼容模式-MatchMode
  public Map<Long, List<String>> loadProblemStatusesByRecordIds(List<ReviewDataRecordRowResponse> records) {
    if (records == null || records.isEmpty()) {
      return Map.of();
    }
    List<Long> storageIds =
        records.stream().map(row -> storageId(row.id())).filter(Objects::nonNull).distinct().toList();
    if (storageIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, ReportRow> reportByStorageId = new LinkedHashMap<>();
    for (ReportRow report : loadReportRowsByIds(storageIds)) {
      reportByStorageId.put(report.id(), report);
    }
    List<String> allProblemLegacyIds =
        reportByStorageId.values().stream()
            .flatMap(report -> report.problemDetailIds().stream())
            .distinct()
            .toList();
    Map<String, ProblemRow> problemByLegacyId = new LinkedHashMap<>();
    for (ProblemRow problem : loadProblemRowsByLegacyIds(allProblemLegacyIds)) {
      problemByLegacyId.put(problem.legacyId(), problem);
    }
    Map<Long, List<String>> result = new LinkedHashMap<>();
    for (ReviewDataRecordRowResponse record : records) {
      ReportRow report = reportByStorageId.get(storageId(record.id()));
      List<String> statuses =
          report == null
              ? List.of()
              : report.problemDetailIds().stream()
                  .map(problemByLegacyId::get)
                  .filter(Objects::nonNull)
                  .map(ProblemRow::problemStatus)
                  .map(TextQuerySupport::normalizeDisplay)
                  .filter(value -> !value.isBlank())
                  .toList();
      result.put(record.id(), statuses);
    }
    return result;
  }

  //兼容模式-MatchMode
  public List<String> loadProjectNames() {
    return distinct(loadReportRows().stream().map(ReportRow::projectName).toList());
  }

  //兼容模式-MatchMode
  public List<String> loadModuleNames() {
    return distinct(loadReportRows().stream().map(row -> ReviewDataModuleNameSupport.normalize(row.moduleName())).toList());
  }

  //兼容模式-MatchMode
  public List<String> loadReviewOwners() {
    return distinct(loadReportRows().stream().map(ReportRow::reviewCharger).toList());
  }

  //兼容模式-MatchMode
  public List<String> loadReviewExperts() {
    List<String> values = new ArrayList<>();
    for (ReportRow row : loadReportRows()) {
      values.addAll(row.reviewExperts());
    }
    return distinct(values);
  }

  private MatchModeRows buildRows() {
    return buildRows(Set.of());
  }

  private MatchModeRows buildRows(Set<String> excludedLegacyIds) {
    //兼容模式-MatchMode：从老平台 report/problem/description 三张兼容表还原页面行。
    // report.docType 是老平台“文档类别”，需要优先映射到新平台行的 reviewType，供导出共用口径读取。
    // 指标字段不信任 Mongo 中保存的派生值；老平台 refreshAttribute 也是查询时基于问题明细重算。
    List<ProblemRow> problems = loadProblemRows();
    Map<String, ProblemRow> problemByLegacyId = new LinkedHashMap<>();
    for (ProblemRow problem : problems) {
      problemByLegacyId.put(problem.legacyId(), problem);
    }
    Map<String, DescriptionRow> descriptionByLegacyId = new LinkedHashMap<>();
    for (DescriptionRow description : loadDescriptionRows()) {
      descriptionByLegacyId.put(description.legacyId(), description);
    }

    List<ReviewDataRecordRowResponse> records = new ArrayList<>();
    Map<Long, List<ReviewDataProblemItemResponse>> problemItemsByRecordId = new LinkedHashMap<>();
    Map<Long, List<String>> expertsByRecordId = new LinkedHashMap<>();
    for (ReportRow report : loadReportRows()) {
      if (excludedLegacyIds.contains(report.legacyId())) {
        continue;
      }
      Long publicRecordId = publicRecordId(report.id());
      List<ProblemRow> reportProblems =
          report.problemDetailIds().stream()
              .map(problemByLegacyId::get)
              .filter(Objects::nonNull)
              .toList();
      List<DescriptionRow> reportDescriptions =
          report.descriptionIds().stream()
              .map(descriptionByLegacyId::get)
              .filter(Objects::nonNull)
              .toList();
      records.add(buildRecordRow(report, reportProblems, reportDescriptions));
      problemItemsByRecordId.put(
          publicRecordId,
          reportProblems.stream().map(problem -> toProblemResponse(publicRecordId, problem)).toList());
      expertsByRecordId.put(publicRecordId, report.reviewExperts());
    }
    return new MatchModeRows(records, problemItemsByRecordId, expertsByRecordId);
  }

  private ReviewDataRecordRowResponse buildRecordRow(
      ReportRow report,
      List<ProblemRow> reportProblems,
      List<DescriptionRow> reportDescriptions) {
    RecordMetrics metrics = metrics(report, reportProblems, reportDescriptions);
    String expertsSummary = String.join("、", report.reviewExperts());
    String reviewType = firstText(report.docType(), report.sourceType(), report.reviewTypeStr());
    return new ReviewDataRecordRowResponse(
        publicRecordId(report.id()),
        TextQuerySupport.normalizeDisplay(report.projectName()),
        TextQuerySupport.normalizeDisplay(report.title()),
        ReviewDataModuleNameSupport.normalize(report.moduleName()),
        reviewType,
        report.reviewTime() == null ? null : report.reviewTime().toLocalDate(),
        TextQuerySupport.normalizeDisplay(report.reviewCharger()),
        expertsSummary,
        metrics.reviewScalePages(),
        firstDescriptionText(reportDescriptions, DescriptionRow::reviewProduct),
        firstDescriptionText(reportDescriptions, DescriptionRow::author),
        firstDescriptionText(reportDescriptions, DescriptionRow::version),
        metrics.problemCount(),
        metrics.problemDensity(),
        metrics.reviewEfficiency(),
        metrics.reviewRate(),
        metrics.reviewCategorySummary(),
        metrics.docSpecificationCount(),
        metrics.integrityCount(),
        metrics.functionalityCount(),
        metrics.feasibilityCount(),
        metrics.independentReviewWorkload(),
        metrics.independentReviewProblemCount(),
        metrics.meetingReviewWorkload(),
        metrics.meetingReviewProblemCount(),
        TextQuerySupport.normalizeDisplay(report.notReachStandCause()),
        reachStandard(reviewType, metrics.problemDensity()),
        null,
        metrics.weightedDefectDensity(),
        report.createTime(),
        report.createTime(),
        false,
        null,
        null,
        null,
        null);
  }

  /** 单条评审记录的问题明细：按 report.problemDetailIds 的顺序还原，与全量组装口径一致。 */
  private List<ProblemRow> loadReportProblems(ReportRow report) {
    Map<String, ProblemRow> problemByLegacyId = new LinkedHashMap<>();
    for (ProblemRow problem : loadProblemRowsByLegacyIds(report.problemDetailIds())) {
      problemByLegacyId.put(problem.legacyId(), problem);
    }
    return report.problemDetailIds().stream()
        .map(problemByLegacyId::get)
        .filter(Objects::nonNull)
        .toList();
  }

  /** 单条评审记录的描述明细：按 report.descriptionIds 的顺序还原，与全量组装口径一致。 */
  private List<DescriptionRow> loadReportDescriptions(ReportRow report) {
    Map<String, DescriptionRow> descriptionByLegacyId = new LinkedHashMap<>();
    for (DescriptionRow description : loadDescriptionRowsByLegacyIds(report.descriptionIds())) {
      descriptionByLegacyId.put(description.legacyId(), description);
    }
    return report.descriptionIds().stream()
        .map(descriptionByLegacyId::get)
        .filter(Objects::nonNull)
        .toList();
  }

  private ReportRow loadReportRow(Long storageReportId) {
    List<ReportRow> rows = loadReportRowsByIds(List.of(storageReportId));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<ReportRow> loadReportRowsByIds(List<Long> storageReportIds) {
    if (storageReportIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "select " + REPORT_COLUMNS + " from review_data_match_mode_reports where id in "
            + placeholders(storageReportIds.size()),
        this::mapReportRow,
        storageReportIds.toArray());
  }

  private List<ProblemRow> loadProblemRowsByLegacyIds(List<String> legacyIds) {
    if (legacyIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "select " + PROBLEM_COLUMNS + " from review_data_match_mode_problem_details where legacy_id in "
            + placeholders(legacyIds.size()),
        this::mapProblemRow,
        legacyIds.toArray());
  }

  private List<DescriptionRow> loadDescriptionRowsByLegacyIds(List<String> legacyIds) {
    if (legacyIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(
        "select " + DESCRIPTION_COLUMNS + " from review_data_match_mode_descriptions where legacy_id in "
            + placeholders(legacyIds.size()),
        this::mapDescriptionRow,
        legacyIds.toArray());
  }

  private List<ContentRow> loadContentRowsByReportLegacyId(String reportLegacyId) {
    return jdbcTemplate.query(
        "select " + CONTENT_COLUMNS + " from review_data_match_mode_contents "
            + "where match_mode_report_legacy_id = ? order by content_order, id",
        this::mapContentRow,
        reportLegacyId);
  }

  private static String placeholders(int count) {
    return "(" + String.join(", ", java.util.Collections.nCopies(count, "?")) + ")";
  }

  private RecordMetrics metrics(ReportRow report, List<ProblemRow> problems, List<DescriptionRow> descriptions) {
    int effectiveProblemCount =
        problems.isEmpty() && report.defectCountSum() != null
            ? Math.max(0, report.defectCountSum())
            : (int) problems.stream().filter(this::isEffectiveProblem).count();
    double totalWorkload =
        problems.stream().map(ProblemRow::workload).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    int reviewScalePages = reviewScalePages(report, problems, descriptions);
    int docSpecificationCount = categoryCount(problems, "文档规范", report.docSpecification());
    int integrityCount = categoryCount(problems, "完整性", report.integrity());
    int functionalityCount = categoryCount(problems, "功能性", report.functionality());
    int feasibilityCount = categoryCount(problems, "可行性", report.feasibility());
    ReviewDataMetricCalculator.ReviewRecordMetrics calculated =
        ReviewDataMetricCalculator.recordMetrics(
            reviewScalePages,
            effectiveProblemCount,
            totalWorkload,
            docSpecificationCount,
            integrityCount,
            functionalityCount,
            feasibilityCount);
    return new RecordMetrics(
        reviewScalePages,
        effectiveProblemCount,
        calculated.problemDensity(),
        calculated.reviewEfficiency(),
        calculated.reviewRate(),
        reviewCategorySummary(report, problems),
        docSpecificationCount,
        integrityCount,
        functionalityCount,
        feasibilityCount,
        workloadByReviewType(problems, "独立评审"),
        problemCountByReviewType(problems, "独立评审"),
        workloadByReviewType(problems, "会议评审"),
        problemCountByReviewType(problems, "会议评审"),
        calculated.weightedDefectDensity());
  }

  private int reviewScalePages(ReportRow report, List<ProblemRow> problems, List<DescriptionRow> descriptions) {
    int descriptionPages =
        descriptions.stream()
            .map(DescriptionRow::reviewScalePages)
            .filter(Objects::nonNull)
            .mapToInt(value -> Math.max(0, value))
            .sum();
    if (descriptionPages > 0) {
      return descriptionPages;
    }
    //兼容模式-MatchMode：少数老 Mongo 记录若缺少 description 明细，只能用工作量和旧 reviewRate 反推评审规模；
    //该分支只作为源数据残缺兜底，正式模式和正常 Match mode 明细重算路径都不依赖它。
    double totalWorkload =
        problems.stream().map(ProblemRow::workload).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    if (totalWorkload > 0D && report.reviewRate() != null && report.reviewRate().doubleValue() > 0D) {
      return Math.max(0, (int) Math.round(report.reviewRate().doubleValue() * totalWorkload));
    }
    return 0;
  }

  private String firstDescriptionText(
      List<DescriptionRow> descriptions, java.util.function.Function<DescriptionRow, String> mapper) {
    for (DescriptionRow description : descriptions) {
      String normalized = TextQuerySupport.normalizeDisplay(mapper.apply(description));
      if (!normalized.isBlank()) {
        return normalized;
      }
    }
    return null;
  }

  private boolean isEffectiveProblem(ProblemRow problem) {
    return ReviewDataMetricCalculator.isEffectiveProblem(problem.problemStatus(), problem.problemType());
  }

  private String reviewCategorySummary(ReportRow report, List<ProblemRow> problems) {
    List<String> categories = distinct(problems.stream().map(ProblemRow::reviewType).toList());
    if (!categories.isEmpty()) {
      return String.join("、", categories);
    }
    return firstText(report.reviewTypeStr(), report.docType(), report.sourceType());
  }

  private int categoryCount(List<ProblemRow> problems, String category, Integer fallback) {
    if (problems.isEmpty()) {
      return fallback == null ? 0 : Math.max(0, fallback);
    }
    return (int) problems.stream()
        .filter(this::isEffectiveProblem)
        .filter(problem -> category.equals(TextQuerySupport.normalizeDisplay(problem.problemType())))
        .count();
  }

  private double workloadByReviewType(List<ProblemRow> problems, String reviewType) {
    return problems.stream()
        .filter(problem -> reviewType.equals(TextQuerySupport.normalizeDisplay(problem.reviewType())))
        .map(ProblemRow::workload)
        .filter(Objects::nonNull)
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  private int problemCountByReviewType(List<ProblemRow> problems, String reviewType) {
    return (int) problems.stream()
        .filter(this::isEffectiveProblem)
        .filter(problem -> reviewType.equals(TextQuerySupport.normalizeDisplay(problem.reviewType())))
        .count();
  }

  private ReviewDataProblemItemResponse toProblemResponse(Long publicRecordId, ProblemRow problem) {
    return new ReviewDataProblemItemResponse(
        publicProblemId(problem.id()),
        publicRecordId,
        TextQuerySupport.normalizeDisplay(problem.reviewer()),
        problem.workload(),
        TextQuerySupport.normalizeDisplay(problem.reviewType()),
        TextQuerySupport.normalizeDisplay(problem.position()),
        TextQuerySupport.normalizeDisplay(problem.problemType()),
        TextQuerySupport.normalizeDisplay(problem.description()),
        TextQuerySupport.normalizeDisplay(problem.suggestion()),
        TextQuerySupport.normalizeDisplay(problem.liablePerson()),
        TextQuerySupport.normalizeDisplay(problem.reasonForNotAccepting()),
        TextQuerySupport.normalizeDisplay(problem.problemStatus()),
        problem.updateTime() == null ? problem.createTime() : problem.updateTime().atStartOfDay(),
        null);
  }

  private List<ReportRow> loadReportRows() {
    return jdbcTemplate.query(
        "select " + REPORT_COLUMNS + " from review_data_match_mode_reports"
            + " order by review_time desc nulls last, id desc",
        this::mapReportRow);
  }

  private List<ProblemRow> loadProblemRows() {
    return jdbcTemplate.query(
        "select " + PROBLEM_COLUMNS + " from review_data_match_mode_problem_details order by id asc",
        this::mapProblemRow);
  }

  private List<DescriptionRow> loadDescriptionRows() {
    return jdbcTemplate.query(
        "select " + DESCRIPTION_COLUMNS + " from review_data_match_mode_descriptions order by id asc",
        this::mapDescriptionRow);
  }

  private List<ContentRow> loadContentRows() {
    return jdbcTemplate.query(
        "select " + CONTENT_COLUMNS + " from review_data_match_mode_contents"
            + " order by match_mode_report_legacy_id, content_order, id",
        this::mapContentRow);
  }

  private ContentRow mapContentRow(ResultSet rs, int rowNum) throws SQLException {
    return new ContentRow(
        rs.getLong("id"),
        rs.getString("match_mode_report_legacy_id"),
        rs.getInt("content_order"),
        rs.getString("reviewer_name"),
        rs.getBigDecimal("independent_workload_hours") == null
            ? null : rs.getBigDecimal("independent_workload_hours").doubleValue(),
        rs.getString("assignment_content"),
        (Integer) rs.getObject("independent_problem_count"),
        rs.getBigDecimal("meeting_workload_hours") == null
            ? null : rs.getBigDecimal("meeting_workload_hours").doubleValue(),
        (Integer) rs.getObject("meeting_problem_count"));
  }

  private ReportRow mapReportRow(ResultSet rs, int rowNum) throws SQLException {
    return new ReportRow(
        rs.getLong("id"),
        rs.getString("legacy_id"),
        rs.getString("project_name"),
        rs.getString("title"),
        rs.getString("module_name"),
        rs.getString("source_type"),
        rs.getString("doc_type"),
        rs.getString("review_type_str"),
        rs.getTimestamp("review_time") == null ? null : rs.getTimestamp("review_time").toLocalDateTime(),
        rs.getString("review_charger"),
        splitLooseList(rs.getString("review_experts")),
        (Integer) rs.getObject("defect_value"),
        (Integer) rs.getObject("defect_count_sum"),
        rs.getBigDecimal("review_defect_density"),
        rs.getBigDecimal("weighted_defect_density"),
        rs.getBigDecimal("review_efficiency"),
        rs.getBigDecimal("review_rate"),
        (Integer) rs.getObject("doc_specification"),
        (Integer) rs.getObject("integrity"),
        (Integer) rs.getObject("functionality"),
        (Integer) rs.getObject("feasibility"),
        rs.getString("not_reach_stand_cause"),
        splitLooseList(rs.getString("problem_detail_ids")),
        splitLooseList(rs.getString("description_ids")),
        rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toLocalDateTime());
  }

  private ProblemRow mapProblemRow(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal workload = rs.getBigDecimal("workload");
    return new ProblemRow(
        rs.getLong("id"),
        rs.getString("legacy_id"),
        rs.getString("reviewer"),
        workload == null ? null : workload.doubleValue(),
        rs.getString("review_type"),
        rs.getString("position"),
        rs.getString("problem_type"),
        rs.getString("description"),
        rs.getString("suggestion"),
        rs.getString("liable_person"),
        rs.getString("reason_for_not_accepting"),
        rs.getString("problem_status"),
        rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toLocalDateTime(),
        rs.getDate("update_time") == null ? null : rs.getDate("update_time").toLocalDate());
  }

  private DescriptionRow mapDescriptionRow(ResultSet rs, int rowNum) throws SQLException {
    return new DescriptionRow(
        rs.getLong("id"),
        rs.getString("legacy_id"),
        rs.getString("review_product"),
        rs.getString("version"),
        rs.getString("author"),
        (Integer) rs.getObject("review_scale_pages"),
        rs.getString("unit"));
  }

  private List<String> splitLooseList(String rawValue) {
    String normalized = TextQuerySupport.trimToNull(rawValue);
    if (normalized == null) {
      return List.of();
    }
    List<String> objectIds = matches(OBJECT_ID_PATTERN, normalized);
    if (!objectIds.isEmpty()) {
      return objectIds;
    }
    List<String> quoted = matches(QUOTED_VALUE_PATTERN, normalized);
    if (!quoted.isEmpty()) {
      return distinct(quoted);
    }
    String text = normalized.replace('[', ' ').replace(']', ' ').trim();
    List<String> values = new ArrayList<>();
    for (String part : text.split("[,，、;；\\n\\r]+")) {
      String value = TextQuerySupport.trimToNull(part);
      if (value != null) {
        values.add(value);
      }
    }
    return distinct(values);
  }

  private List<String> matches(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    List<String> values = new ArrayList<>();
    while (matcher.find()) {
      String normalized = TextQuerySupport.trimToNull(matcher.group(1));
      if (normalized != null) {
        values.add(normalized);
      }
    }
    return values;
  }

  private List<String> distinct(List<String> values) {
    LinkedHashSet<String> distinct = new LinkedHashSet<>();
    values.stream().map(TextQuerySupport::trimToNull).filter(Objects::nonNull).forEach(distinct::add);
    return List.copyOf(distinct);
  }

  private String firstText(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      String normalized = TextQuerySupport.normalizeDisplay(value);
      if (!normalized.isBlank()) {
        return normalized;
      }
    }
    return "";
  }

  private Boolean reachStandard(String reviewType, Double density) {
    return ReviewDataReachStandardRule.reached(reviewType, density == null ? 0D : density);
  }

  private Long publicRecordId(Long storageId) {
    return storageId == null ? null : -Math.abs(storageId);
  }

  private Long publicProblemId(Long storageId) {
    return storageId == null ? null : -Math.abs(storageId);
  }

  private Long storageId(Long publicId) {
    return publicId == null ? null : Math.abs(publicId);
  }

  private Set<String> loadPlatformOwnedLegacyIds() {
    return new LinkedHashSet<>(
        jdbcTemplate.query(
            """
            select match_mode_report_legacy_id
              from review_data_match_mode_edit_links
             where authority = 'PLATFORM_OWNED'
            """,
            (rs, rowNum) -> rs.getString("match_mode_report_legacy_id")));
  }

  private String findReportLegacyId(Long matchModeRecordId) {
    return jdbcTemplate.query(
        """
        select legacy_id
          from review_data_match_mode_reports
         where id = ?
        """,
        rs -> rs.next() ? rs.getString("legacy_id") : null,
        storageId(matchModeRecordId));
  }

  private record MatchModeRows(
      List<ReviewDataRecordRowResponse> records,
      Map<Long, List<ReviewDataProblemItemResponse>> problemItemsByRecordId,
      Map<Long, List<String>> expertsByRecordId) {}

  public record MatchModeRecordSource(
      ReportRow record,
      List<ProblemRow> problems,
      List<DescriptionRow> descriptions,
      List<ContentRow> contents) {
    public int reviewScalePages() {
      int descriptionPages =
          descriptions.stream()
              .map(DescriptionRow::reviewScalePages)
              .filter(Objects::nonNull)
              .mapToInt(value -> Math.max(0, value))
              .sum();
      if (descriptionPages > 0) {
        return descriptionPages;
      }
      double totalWorkload =
          problems.stream().map(ProblemRow::workload).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
      if (totalWorkload > 0D && record.reviewRate() != null && record.reviewRate().doubleValue() > 0D) {
        return Math.max(0, (int) Math.round(record.reviewRate().doubleValue() * totalWorkload));
      }
      return 0;
    }

    public DescriptionRow primaryDescription() {
      return descriptions.isEmpty() ? null : descriptions.get(0);
    }
  }

  public record ReportRow(
      Long id,
      String legacyId,
      String projectName,
      String title,
      String moduleName,
      String sourceType,
      String docType,
      String reviewTypeStr,
      LocalDateTime reviewTime,
      String reviewCharger,
      List<String> reviewExperts,
      Integer defectValue,
      Integer defectCountSum,
      BigDecimal reviewDefectDensity,
      BigDecimal weightedDefectDensity,
      BigDecimal reviewEfficiency,
      BigDecimal reviewRate,
      Integer docSpecification,
      Integer integrity,
      Integer functionality,
      Integer feasibility,
      String notReachStandCause,
      List<String> problemDetailIds,
      List<String> descriptionIds,
      LocalDateTime createTime) {}

  public record ProblemRow(
      Long id,
      String legacyId,
      String reviewer,
      Double workload,
      String reviewType,
      String position,
      String problemType,
      String description,
      String suggestion,
      String liablePerson,
      String reasonForNotAccepting,
      String problemStatus,
      LocalDateTime createTime,
      LocalDate updateTime) {}

  public record DescriptionRow(
      Long id,
      String legacyId,
      String reviewProduct,
      String version,
      String author,
      Integer reviewScalePages,
      String unit) {}

  public record ContentRow(
      Long id,
      String reportLegacyId,
      Integer contentOrder,
      String reviewerName,
      Double independentWorkloadHours,
      String assignmentContent,
      Integer independentProblemCount,
      Double meetingWorkloadHours,
      Integer meetingProblemCount) {}

  private record RecordMetrics(
      Integer reviewScalePages,
      Integer problemCount,
      Double problemDensity,
      Double reviewEfficiency,
      Double reviewRate,
      String reviewCategorySummary,
      Integer docSpecificationCount,
      Integer integrityCount,
      Integer functionalityCount,
      Integer feasibilityCount,
      Double independentReviewWorkload,
      Integer independentReviewProblemCount,
      Double meetingReviewWorkload,
      Integer meetingReviewProblemCount,
      Double weightedDefectDensity) {}
}
