package com.data.collection.platform.service;

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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataMatchModeRecordRepository {
  private static final Pattern OBJECT_ID_PATTERN = Pattern.compile("ObjectId\\(['\"]?([^'\")]+)['\"]?\\)");
  private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("[\"']([^\"']+)[\"']");
  private static final Set<String> NON_EFFECTIVE_STATUSES = Set.of("已拒绝", "未评审", "无问题");

  private final JdbcTemplate jdbcTemplate;

  public ReviewDataMatchModeRecordRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  //兼容模式-MatchMode
  public List<ReviewDataRecordRowResponse> loadRecords() {
    return buildRows().records();
  }

  //兼容模式-MatchMode
  public ReviewDataRecordRowResponse getRecordOrThrow(Long recordId) {
    return buildRows().records().stream()
        .filter(row -> Objects.equals(row.id(), publicRecordId(storageId(recordId))))
        .findFirst()
        .orElseThrow(() -> new EmptyResultDataAccessException("兼容模式评审记录不存在: " + recordId, 1));
  }

  //兼容模式-MatchMode
  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return buildRows().problemItemsByRecordId().getOrDefault(publicRecordId(storageId(recordId)), List.of());
  }

  //兼容模式-MatchMode
  public List<String> listRecordExperts(Long recordId) {
    return buildRows().expertsByRecordId().getOrDefault(publicRecordId(storageId(recordId)), List.of());
  }

  //兼容模式-MatchMode
  public Map<Long, List<String>> loadProblemStatusesByRecordIds(List<ReviewDataRecordRowResponse> records) {
    if (records == null || records.isEmpty()) {
      return Map.of();
    }
    MatchModeRows rows = buildRows();
    Map<Long, List<String>> result = new LinkedHashMap<>();
    for (ReviewDataRecordRowResponse record : records) {
      List<String> statuses =
          rows.problemItemsByRecordId().getOrDefault(record.id(), List.of()).stream()
              .map(ReviewDataProblemItemResponse::problemStatus)
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
    List<ProblemRow> problems = loadProblemRows();
    Map<String, ProblemRow> problemByLegacyId = new LinkedHashMap<>();
    for (ProblemRow problem : problems) {
      problemByLegacyId.put(problem.legacyId(), problem);
    }

    List<ReviewDataRecordRowResponse> records = new ArrayList<>();
    Map<Long, List<ReviewDataProblemItemResponse>> problemItemsByRecordId = new LinkedHashMap<>();
    Map<Long, List<String>> expertsByRecordId = new LinkedHashMap<>();
    for (ReportRow report : loadReportRows()) {
      Long publicRecordId = publicRecordId(report.id());
      List<ProblemRow> reportProblems =
          report.problemDetailIds().stream()
              .map(problemByLegacyId::get)
              .filter(Objects::nonNull)
              .toList();
      RecordMetrics metrics = metrics(report, reportProblems);
      String expertsSummary = String.join("、", report.reviewExperts());
      records.add(
          new ReviewDataRecordRowResponse(
              publicRecordId,
              TextQuerySupport.normalizeDisplay(report.projectName()),
              TextQuerySupport.normalizeDisplay(report.title()),
              ReviewDataModuleNameSupport.normalize(report.moduleName()),
              firstText(report.reviewTypeStr(), report.docType(), report.sourceType()),
              report.reviewTime() == null ? null : report.reviewTime().toLocalDate(),
              TextQuerySupport.normalizeDisplay(report.reviewCharger()),
              expertsSummary,
              report.defectValue(),
              null,
              null,
              null,
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
              reachStandard(metrics.problemDensity()),
              null,
              decimalToDouble(report.weightedDefectDensity()),
              report.createTime(),
              report.createTime(),
              false,
              null,
              null,
              null));
      problemItemsByRecordId.put(
          publicRecordId,
          reportProblems.stream().map(problem -> toProblemResponse(publicRecordId, problem)).toList());
      expertsByRecordId.put(publicRecordId, report.reviewExperts());
    }
    return new MatchModeRows(records, problemItemsByRecordId, expertsByRecordId);
  }

  private RecordMetrics metrics(ReportRow report, List<ProblemRow> problems) {
    int effectiveProblemCount =
        problems.isEmpty() && report.defectCountSum() != null
            ? Math.max(0, report.defectCountSum())
            : (int) problems.stream().filter(this::isEffectiveProblem).count();
    double totalWorkload =
        problems.stream().map(ProblemRow::workload).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    int reviewScalePages = report.defectValue() == null ? 0 : Math.max(0, report.defectValue());
    double problemDensity =
        reviewScalePages <= 0
            ? 0D
            : ReviewDataNumberSupport.floorToTwoDecimals(effectiveProblemCount * 1D / reviewScalePages);
    double reviewEfficiency =
        totalWorkload <= 0
            ? decimalToDouble(report.reviewEfficiency())
            : ReviewDataNumberSupport.floorToTwoDecimals(effectiveProblemCount / totalWorkload);
    double reviewRate =
        totalWorkload <= 0
            ? decimalToDouble(report.reviewRate())
            : ReviewDataNumberSupport.floorToTwoDecimals(reviewScalePages / totalWorkload);
    return new RecordMetrics(
        effectiveProblemCount,
        problemDensity,
        reviewEfficiency,
        reviewRate,
        reviewCategorySummary(report, problems),
        categoryCount(problems, "文档规范", report.docSpecification()),
        categoryCount(problems, "完整性", report.integrity()),
        categoryCount(problems, "功能性", report.functionality()),
        categoryCount(problems, "可行性", report.feasibility()),
        workloadByReviewType(problems, "独立评审"),
        problemCountByReviewType(problems, "独立评审"),
        workloadByReviewType(problems, "会议评审"),
        problemCountByReviewType(problems, "会议评审"));
  }

  private boolean isEffectiveProblem(ProblemRow problem) {
    String status = TextQuerySupport.normalizeDisplay(problem.problemStatus());
    String category = TextQuerySupport.normalizeDisplay(problem.problemType());
    return !NON_EFFECTIVE_STATUSES.contains(status) && !"无问题".equals(category);
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
        problem.updateTime() == null ? problem.createTime() : problem.updateTime().atStartOfDay());
  }

  private List<ReportRow> loadReportRows() {
    return jdbcTemplate.query(
        """
        select id, legacy_id, project_name, title, module_name, source_type, doc_type, review_type_str,
               review_time, review_charger, review_experts, defect_value, defect_count_sum,
               review_defect_density, weighted_defect_density, review_efficiency, review_rate,
               doc_specification, integrity, functionality, feasibility, not_reach_stand_cause,
               problem_detail_ids, create_time
          from review_data_match_mode_reports
         order by review_time desc nulls last, id desc
        """,
        this::mapReportRow);
  }

  private List<ProblemRow> loadProblemRows() {
    return jdbcTemplate.query(
        """
        select id, legacy_id, reviewer, workload, review_type, position, problem_type, description,
               suggestion, liable_person, reason_for_not_accepting, problem_status, create_time, update_time
          from review_data_match_mode_problem_details
         order by id asc
        """,
        this::mapProblemRow);
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

  private Double decimalToDouble(BigDecimal value) {
    return value == null ? 0D : value.doubleValue();
  }

  private Boolean reachStandard(Double density) {
    double value = density == null ? 0D : density;
    return value >= 0.2D && value <= 0.6D;
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

  private record MatchModeRows(
      List<ReviewDataRecordRowResponse> records,
      Map<Long, List<ReviewDataProblemItemResponse>> problemItemsByRecordId,
      Map<Long, List<String>> expertsByRecordId) {}

  private record ReportRow(
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
      LocalDateTime createTime) {}

  private record ProblemRow(
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

  private record RecordMetrics(
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
      Integer meetingReviewProblemCount) {}
}
