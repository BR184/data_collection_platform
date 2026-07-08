package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardMetricResponse;
import com.data.collection.platform.entity.QualityBoardProjectOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QualityBoardRdService {
  private static final String DEFAULT_PROJECT_NAME = "CC2026R3";
  private static final String DEMAND_REVIEW_TYPE = "需求说明书评审";
  private static final String DESIGN_REVIEW_TYPE = "设计说明书评审";
  private static final long CROWN_CAD_PROJECT_ID = SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID;

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final ReviewDataRecordReadRepository reviewDataRecordReadRepository;
  private final ReviewDataMatchModeRecordRepository reviewDataMatchModeRecordRepository;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;

  public QualityBoardRdService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      ReviewDataRecordReadRepository reviewDataRecordReadRepository,
      ReviewDataMatchModeRecordRepository reviewDataMatchModeRecordRepository,
      CodeReviewMatchModeSwitchService matchModeSwitchService) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseScopeResolver = phaseScopeResolver;
    this.reviewDataRecordReadRepository = reviewDataRecordReadRepository;
    this.reviewDataMatchModeRecordRepository = reviewDataMatchModeRecordRepository;
    this.matchModeSwitchService = matchModeSwitchService;
  }

  public QualityBoardProjectOptionsResponse listProjectOptions() {
    List<String> projectNames = new ArrayList<>(phaseScopeResolver.listEnabledLegacyCrownCadParentNames());
    if (projectNames.stream().noneMatch(DEFAULT_PROJECT_NAME::equals)) {
      projectNames.add(0, DEFAULT_PROJECT_NAME);
    }
    List<OptionItemResponse> options =
        projectNames.stream()
            .filter(StringUtils::hasText)
            .distinct()
            .map(value -> new OptionItemResponse(value, value))
            .toList();
    String defaultProject =
        options.stream()
            .map(OptionItemResponse::value)
            .filter(DEFAULT_PROJECT_NAME::equals)
            .findFirst()
            .orElse(options.stream().map(OptionItemResponse::value).findFirst().orElse(DEFAULT_PROJECT_NAME));
    return new QualityBoardProjectOptionsResponse(defaultProject, options);
  }

  public QualityBoardRdOverviewResponse getOverview(String requestedProjectName) {
    String projectName =
        TextQuerySupport.trimToNull(requestedProjectName) == null
            ? DEFAULT_PROJECT_NAME
            : TextQuerySupport.normalizeDisplay(requestedProjectName);
    double demandReviewDensity = reviewDensity(projectName, DEMAND_REVIEW_TYPE);
    double designReviewDensity = reviewDensity(projectName, DESIGN_REVIEW_TYPE);
    double codeReviewCcDensity = codeReviewDensity("cc", projectName);
    double codeReviewDgmDensity = codeReviewDensity("dgm", toDgmProjectName(projectName));
    double integrationPassRate = integrationPassRate(projectName);
    double defectLeakageRate = defectLeakageRate(projectName);
    double defectEliminationRate = defectEliminationRate(projectName);
    double newIssueFixRate = newIssueFixRate(projectName);
    List<QualityBoardMetricResponse> metrics =
        List.of(
            new QualityBoardMetricResponse("demandReviewReportDensity", "需求评审缺陷密度", demandReviewDensity, "", "目标值：[0.20, 0.60]"),
            new QualityBoardMetricResponse("designReviewReportDensity", "设计评审缺陷密度", designReviewDensity, "", "目标值：[0.20, 0.60]"),
            new QualityBoardMetricResponse("codeWalkThroughDefectDensityCc", "CC代码走查缺陷密度", codeReviewCcDensity, "KLOC", "目标值：[2.00, 10.00]"),
            new QualityBoardMetricResponse("codeWalkThroughDefectDensityDgm", "DGM代码走查缺陷密度", codeReviewDgmDensity, "KLOC", "目标值：[2.00, 10.00]"),
            new QualityBoardMetricResponse("integrationPassRate", "集成测试通过率", integrationPassRate, "%", "目标值：不低于 90.00%"),
            new QualityBoardMetricResponse("defectLeakageRate", "发布缺陷遗留率", defectLeakageRate, "%", "目标值：不超过 15.00%"),
            new QualityBoardMetricResponse("defectEliminationRate", "开发缺陷遗留率", defectEliminationRate, "%", "目标值：不低于 90.00%"),
            new QualityBoardMetricResponse("newIssueFixRate", "新发缺陷修复率", newIssueFixRate, "%", "新发缺陷中已修复/未复现占比"));
    return new QualityBoardRdOverviewResponse(
        projectName,
        demandReviewDensity,
        designReviewDensity,
        codeReviewCcDensity,
        codeReviewDgmDensity,
        integrationPassRate,
        defectLeakageRate,
        defectEliminationRate,
        newIssueFixRate,
        metrics);
  }

  private double reviewDensity(String projectName, String reviewType) {
    List<ReviewDataRecordRowResponse> formalRows =
        reviewDataRecordReadRepository.loadRecords(null, projectName, null, null, null, null, null, null).stream()
            .filter(row -> matchesReviewType(row.reviewType(), reviewType))
            .toList();
    Stream<ReviewDataRecordRowResponse> rows = formalRows.stream();
    // 兼容模式 match mode：评审兼容读开启时，质量看板与评审数据管理一致，合并正式表和未转正式的老平台 Mongo 兼容表。
    // 后续删除兼容模式时，只移除下面 matchRows 合并分支，正式表统计仍可独立工作。
    if (matchModeSwitchService.isReviewDataCompatibilityReadEnabled()) {
      List<ReviewDataRecordRowResponse> matchRows =
          reviewDataMatchModeRecordRepository.loadRecords().stream()
              .filter(row -> equalsText(row.projectName(), projectName))
              .filter(row -> matchesReviewType(row.reviewType(), reviewType))
              .toList();
      rows = Stream.concat(rows, matchRows.stream());
    }
    ReviewDensityAccumulator accumulator =
        rows.collect(
            () -> new ReviewDensityAccumulator(0, 0),
            (acc, row) -> acc.add(row.problemCount(), row.reviewScalePages()),
            ReviewDensityAccumulator::merge);
    return divide(accumulator.problemCount(), accumulator.reviewScalePages(), 1D);
  }

  private double codeReviewDensity(String sourceInstance, String projectName) {
    boolean matchMode = matchModeSwitchService.isCodeReviewCompatibilityReadEnabled();
    // 兼容模式 match mode：代码走查非法数据页开启兼容读时只读老平台 MySQL 兼容表，质量看板跟随同一读源，避免和转正式数据双算。
    String tableName = matchMode ? "code_review_match_mode_records" : "merge_request_fact";
    String deletedPredicate = matchMode ? "" : " and deleted = false";
    String sql =
        """
        with scoped as (
          select
            project_id,
            merge_request_id,
            id,
            coalesce(defect_count, 0) as defect_count,
            coalesce(added_lines, 0) as added_lines,
            row_number() over(partition by project_id, merge_request_id order by id asc) as row_number_in_mr
          from %s
          where lower(coalesce(source_instance, '')) = ?
            and coalesce(project_name, '') = ?
            and lower(coalesce(target_branch, '')) = 'dev'
            and upper(coalesce(merge_request_state, '')) = 'MERGED'
            %s
        ),
        merged_records as (
          select
            project_id,
            merge_request_id,
            sum(case when defect_count = -1 then 0 else defect_count end) as defect_count,
            sum(case when row_number_in_mr = 1 then added_lines else 0 end) as added_lines
          from scoped
          group by project_id, merge_request_id
        )
        select
          coalesce(sum(defect_count), 0) as defect_count,
          coalesce(sum(added_lines), 0) as added_lines
        from merged_records
        """
            .formatted(tableName, deletedPredicate);
    return jdbcTemplate.query(
        sql,
        rs -> {
          if (!rs.next()) {
            return 0D;
          }
          return divide(rs.getLong("defect_count") * 1000D, rs.getLong("added_lines"), 1D);
        },
        sourceInstance.toLowerCase(Locale.ROOT),
        projectName);
  }

  private double integrationPassRate(String projectName) {
    if (!tableExists("integration_test_fact")) {
      return 0D;
    }
    String testingPhase = projectName + "集成测试";
    return jdbcTemplate.query(
        """
        select pass_case, execute_case
          from integration_test_fact
         where deleted = false
           and testing_phase = ?
        """,
        rs -> {
          double totalRate = 0D;
          long rowCount = 0L;
          while (rs.next()) {
            Integer passCase = (Integer) rs.getObject("pass_case");
            Integer executeCase = (Integer) rs.getObject("execute_case");
            rowCount++;
            if (passCase != null && executeCase != null && executeCase > 0) {
              totalRate += ReviewDataNumberSupport.roundToTwoDecimals(passCase * 100D / executeCase);
            }
          }
          return rowCount <= 0 ? 0D : ReviewDataNumberSupport.roundToTwoDecimals(totalRate / rowCount);
        },
        testingPhase);
  }

  private double defectLeakageRate(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    long openCount = issueCount(phases, " and upper(coalesce(issue_state, '')) = 'OPEN'");
    long totalCount = issueCount(phases, "");
    return divide(openCount * 100D, totalCount, 1D);
  }

  private double defectEliminationRate(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    long integrationNotPassCount = integrationNotPassCount(projectName);
    long systemTestIssueCount = issueCount(phases, "");
    if (integrationNotPassCount <= 0 || systemTestIssueCount <= 0) {
      return 0D;
    }
    return divide(integrationNotPassCount * 100D, integrationNotPassCount + systemTestIssueCount, 1D);
  }

  private double newIssueFixRate(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    String commonPredicate = oldPlatformQueryFilterPredicate()
        + " and coalesce(bug_status, '') not like '%历史遗留%'";
    long fixedCount =
        issueCount(
            phases,
            commonPredicate
                + " and upper(coalesce(issue_state, '')) = 'CLOSED'"
                + " and (coalesce(bug_status, '') like '%已修复/完成%'"
                + "      or coalesce(bug_status, '') like '%未复现%'"
                + "      or is_fixed = true)");
    long totalCount = issueCount(phases, commonPredicate);
    return divide(fixedCount * 100D, totalCount, 1D);
  }

  private long integrationNotPassCount(String projectName) {
    if (!tableExists("integration_test_fact")) {
      return 0L;
    }
    Long count =
        jdbcTemplate.queryForObject(
            """
            select coalesce(sum(not_pass_case), 0)
              from integration_test_fact
             where deleted = false
               and testing_phase = ?
            """,
            Long.class,
            projectName + "集成测试");
    return count == null ? 0L : count;
  }

  private long issueCount(List<String> phases, String extraPredicate) {
    if (phases.isEmpty()) {
      return 0L;
    }
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from issue_fact
             where deleted = false
               and project_id = ?
               and testing_phase in (%s)
            """
                .formatted(placeholders)
                + (extraPredicate == null ? "" : extraPredicate),
            Long.class,
            args.toArray());
    return count == null ? 0L : count;
  }

  private String oldPlatformQueryFilterPredicate() {
    return """
       and coalesce(category, '') not like '%功能屏蔽%'
       and coalesce(bug_status, '') not like '%已拒绝%'
       and coalesce(category, '') not like '%建议%'
       and not (coalesce(bug_status, '') like '%申请否决%' and upper(coalesce(issue_state, '')) like '%CLOSED%')
       and not (coalesce(bug_status, '') like '%需求如此%' and upper(coalesce(issue_state, '')) like '%CLOSED%')
      """;
  }

  private boolean tableExists(String tableName) {
    try {
      Boolean exists =
          jdbcTemplate.queryForObject(
              "select to_regclass(?) is not null",
              Boolean.class,
              "public." + tableName);
      return Boolean.TRUE.equals(exists);
    } catch (DataAccessException error) {
      return false;
    }
  }

  private boolean matchesReviewType(String actual, String expected) {
    String normalizedActual = TextQuerySupport.normalizeDisplay(actual);
    String normalizedExpected = TextQuerySupport.normalizeDisplay(expected);
    if (!StringUtils.hasText(normalizedActual) || !StringUtils.hasText(normalizedExpected)) {
      return false;
    }
    String keyword = normalizedExpected.contains("需求") ? "需求" : normalizedExpected.contains("设计") ? "设计" : normalizedExpected;
    return normalizedActual.equals(normalizedExpected) || normalizedActual.contains(keyword);
  }

  private boolean equalsText(String actual, String expected) {
    return Objects.equals(TextQuerySupport.normalizeDisplay(actual), TextQuerySupport.normalizeDisplay(expected));
  }

  private String toDgmProjectName(String projectName) {
    String normalized = TextQuerySupport.normalizeDisplay(projectName);
    return normalized.replaceFirst("^CC(\\d{4})(R\\d)$", "CrownCAD $1 $2");
  }

  private double divide(double numerator, double denominator, double multiplier) {
    if (denominator <= 0D || numerator <= 0D) {
      return 0D;
    }
    return ReviewDataNumberSupport.roundToTwoDecimals((numerator / denominator) * multiplier);
  }

  private static final class ReviewDensityAccumulator {
    private long problemCount;
    private long reviewScalePages;

    private ReviewDensityAccumulator(long problemCount, long reviewScalePages) {
      this.problemCount = problemCount;
      this.reviewScalePages = reviewScalePages;
    }

    private void add(Integer problems, Integer pages) {
      problemCount += Math.max(0, problems == null ? 0 : problems);
      reviewScalePages += Math.max(0, pages == null ? 0 : pages);
    }

    private void merge(ReviewDensityAccumulator other) {
      problemCount += other.problemCount;
      reviewScalePages += other.reviewScalePages;
    }

    private long problemCount() {
      return problemCount;
    }

    private long reviewScalePages() {
      return reviewScalePages;
    }
  }
}
