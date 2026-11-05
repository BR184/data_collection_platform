package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import com.data.collection.platform.entity.QualityBoardFixUserSeverityRowResponse;
import com.data.collection.platform.entity.QualityBoardMetricResponse;
import com.data.collection.platform.entity.QualityBoardOtherOverviewResponse;
import com.data.collection.platform.entity.QualityBoardProjectOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import com.data.collection.platform.entity.QualityBoardRdFilterOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.service.dashboard.DashboardVersionScope;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QualityBoardRdService {
  private static final String DEMAND_REVIEW_TYPE = "需求说明书评审";
  private static final String DESIGN_REVIEW_TYPE = "设计说明书评审";
  private static final long CROWN_CAD_PROJECT_ID = IssueScopeCatalogService.CROWN_CAD_PROJECT_ID;

  private final JdbcTemplate jdbcTemplate;
  private final ReviewDataRecordReadRepository reviewDataRecordReadRepository;
  private final ReviewDataMatchModeRecordRepository reviewDataMatchModeRecordRepository;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;
  private final DashboardVersionScopeService versionScopeService;


  private final com.data.collection.platform.entity.read.DatasetReadSelection fixedRead;

  /** 完整页面组合下的只读计算对象，保留原公式与排序。 */
  public QualityBoardRdService forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new QualityBoardRdService(this, selection);
  }

  private QualityBoardRdService(QualityBoardRdService factory, com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    this.jdbcTemplate = factory.jdbcTemplate;
    this.reviewDataRecordReadRepository = factory.reviewDataRecordReadRepository.forRead(selection);
    this.reviewDataMatchModeRecordRepository = factory.reviewDataMatchModeRecordRepository.forRead(selection);
    this.codeReviewReadSupport = factory.codeReviewReadSupport.forRead(selection);
    this.versionScopeService = factory.versionScopeService.forRead(selection);
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public QualityBoardRdService(
      JdbcTemplate jdbcTemplate,
      ReviewDataRecordReadRepository reviewDataRecordReadRepository,
      ReviewDataMatchModeRecordRepository reviewDataMatchModeRecordRepository,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport,
      DashboardVersionScopeService versionScopeService) {
    this.fixedRead = null;
    this.jdbcTemplate = jdbcTemplate;
    this.reviewDataRecordReadRepository = reviewDataRecordReadRepository;
    this.reviewDataMatchModeRecordRepository = reviewDataMatchModeRecordRepository;
    this.codeReviewReadSupport = codeReviewReadSupport;
    this.versionScopeService = versionScopeService;
  }

  public QualityBoardProjectOptionsResponse listProjectOptions() {
    List<OptionItemResponse> options =
        versionScopeService.listOptions(CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE).stream()
            .filter(option -> StringUtils.hasText(option.value()))
            .map(option -> new OptionItemResponse(option.displayName(), option.value()))
            .toList();
    String defaultProject = options.stream().map(OptionItemResponse::value).findFirst().orElse("");
    return new QualityBoardProjectOptionsResponse(defaultProject, options);
  }

  public QualityBoardRdFilterOptionsResponse listFilterOptions() {
    QualityBoardProjectOptionsResponse projects = listProjectOptions();
    return new QualityBoardRdFilterOptionsResponse(
        projects.defaultProjectName(),
        projects.options(),
        codeReviewReadSupport.listAvailableSources());
  }

  public QualityBoardRdOverviewResponse getOverview(String requestedProjectName) {
    return getOverview(requestedProjectName, codeReviewReadSupport.configuredReadMode());
  }

  public QualityBoardRdOverviewResponse getOverview(
      String requestedProjectName, CodeReviewDataReadMode codeReviewReadMode) {
    DashboardVersionScope scope = resolveScope(requestedProjectName);
    String projectName = scope.businessKey();
    double demandReviewDensity = reviewDensity(projectName, DEMAND_REVIEW_TYPE);
    double designReviewDensity = reviewDensity(projectName, DESIGN_REVIEW_TYPE);
    double codeReviewCcDensity =
        codeReviewReadSupport.defectDensity("cc", projectName, codeReviewReadMode);
    double codeReviewDgmDensity =
        codeReviewReadSupport.defectDensity("dgm", projectName, codeReviewReadMode);
    double integrationPassRate = integrationPassRate(projectName);
    double defectLeakageRate = defectLeakageRate(projectName);
    double defectEliminationRate = defectEliminationRate(projectName);
    double newIssueFixRate = newIssueFixRate(projectName);
    List<QualityBoardMetricResponse> metrics =
        List.of(
            new QualityBoardMetricResponse("demandReviewReportDensity", "需求评审缺陷密度", demandReviewDensity, "", "目标值：[0.20, 0.60]"),
            new QualityBoardMetricResponse("designReviewReportDensity", "设计评审缺陷密度", designReviewDensity, "", "目标值：[0.30, 0.80]"),
            new QualityBoardMetricResponse("codeWalkThroughDefectDensityCc", "CC代码走查缺陷密度", codeReviewCcDensity, "KLOC", "目标值：[3.00, 12.00]"),
            new QualityBoardMetricResponse("codeWalkThroughDefectDensityDgm", "DGM代码走查缺陷密度", codeReviewDgmDensity, "KLOC", "目标值：[3.00, 12.00]"),
            new QualityBoardMetricResponse("integrationPassRate", "集成测试通过率", integrationPassRate, "%", "目标值：不低于 90.00%"),
            new QualityBoardMetricResponse("defectLeakageRate", "发布缺陷遗留率", defectLeakageRate, "%", "目标值：不超过 15.00%"),
            new QualityBoardMetricResponse("defectEliminationRate", "开发缺陷遗留率", defectEliminationRate, "%", "目标值：不低于 90.00%"),
            new QualityBoardMetricResponse("newIssueFixRate", "新发缺陷修复率", newIssueFixRate, "%", "新发缺陷中已修复/未复现占比"));
    return new QualityBoardRdOverviewResponse(
        scope.displayName(),
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

  public QualityBoardRdDashboardResponse getRdDashboard(
      String requestedProjectName,
      String requestedCodeReviewSource) {
    return getRdDashboard(
        requestedProjectName,
        requestedCodeReviewSource,
        codeReviewReadSupport.configuredReadMode());
  }

  public QualityBoardRdDashboardResponse getRdDashboard(
      String requestedProjectName,
      String requestedCodeReviewSource,
      CodeReviewDataReadMode codeReviewReadMode) {
    String projectName = normalizeProjectName(requestedProjectName);
    String codeReviewSource =
        resolveCodeReviewSource(requestedCodeReviewSource, codeReviewReadMode);
    List<String> phases = phaseValues(projectName);
    return new QualityBoardRdDashboardResponse(
        getOverview(projectName, codeReviewReadMode),
        codeReviewSource,
        codeReviewReadSupport.listAvailableSources(codeReviewReadMode),
        codeReviewReadSupport.personDefectDensityRows(
            "reviewer_names", false, codeReviewSource, projectName, codeReviewReadMode),
        codeReviewReadSupport.personDefectDensityRows(
            "author_name", true, codeReviewSource, projectName, codeReviewReadMode),
        fixUserSeverityRows(phases),
        codeReviewReadSupport.frequencyRows(
            codeReviewSource, projectName, codeReviewReadMode),
        defectRepairUserRows(phases));
  }

  public QualityBoardOtherOverviewResponse getOtherOverview(String requestedProjectName) {
    DashboardVersionScope scope = resolveScope(requestedProjectName);
    String projectName = scope.businessKey();
    List<String> phases = phaseValues(projectName);
    return new QualityBoardOtherOverviewResponse(
        scope.displayName(),
        functionDefectCountRows(phases),
        functionDefectDensityRows(projectName, phases),
        qualityRankingRows(projectName, phases),
        memberUnresolvedRateRows(phases),
        releaseLeakageRateRows(),
        developmentLeakageRateRows());
  }

  private List<QualityBoardChartRowResponse> functionDefectCountRows(List<String> phases) {
    return issueGroupedCountRows(phases, "function_name", "未标注功能");
  }

  private List<QualityBoardChartRowResponse> functionDefectDensityRows(
      String projectName,
      List<String> phases) {
    Map<String, Long> addedLinesByFunction =
        codeReviewReadSupport.reviewedAddedLinesByFunction(projectName);
    Map<String, Long> issueCounts = issueGroupedCounts(phases, "function_name", "未标注功能");
    return addedLinesByFunction.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(entry -> new QualityBoardChartRowResponse(
            entry.getKey(),
            divide(issueCounts.getOrDefault(entry.getKey(), 0L) * 100D, entry.getValue(), 1D)))
        .sorted((left, right) -> Double.compare(right.value(), left.value()))
        .limit(20)
        .toList();
  }

  private List<QualityBoardChartRowResponse> qualityRankingRows(
      String projectName,
      List<String> phases) {
    Map<String, Long> addedLinesByAuthor = codeReviewReadSupport.addedLinesByAuthor(projectName);
    Map<String, Long> issueCounts = issueGroupedCounts(phases, "fix_user", "未标注修复人");
    return addedLinesByAuthor.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(entry -> new QualityBoardChartRowResponse(
            entry.getKey(),
            divide(issueCounts.getOrDefault(entry.getKey(), 0L) * 1000D, entry.getValue(), 1D)))
        .sorted((left, right) -> Double.compare(left.value(), right.value()))
        .limit(20)
        .toList();
  }

  private List<QualityBoardChartRowResponse> memberUnresolvedRateRows(List<String> phases) {
    if (phases.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    String sql =
        """
        select
          btrim(fix_user) as person_name,
          round((count(*) filter (where coalesce(bug_status, '') like '%%未修复%%') * 100.0 / count(*))::numeric, 2) as value
        from issue_fact
        where deleted = false
          and project_id = ?
          and testing_phase in (%s)
          and coalesce(fix_user, '') <> '无合法评论'
        group by person_name
        having count(*) > 0
        order by value desc, person_name
        limit 20
        """
            .formatted(placeholders);
    return query(
        sql,
        (rs, rowNum) -> new QualityBoardChartRowResponse(
            rs.getString("person_name"),
            doubleValue(rs.getObject("value"))),
        args.toArray());
  }

  private List<QualityBoardChartRowResponse> releaseLeakageRateRows() {
    return versionScopeService.listOptions(CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE).stream()
        .map(option -> new QualityBoardChartRowResponse(
            option.displayName(), defectLeakageRate(option.value())))
        .toList();
  }

  private List<QualityBoardChartRowResponse> developmentLeakageRateRows() {
    return versionScopeService.listOptions(CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE).stream()
        .map(option -> new QualityBoardChartRowResponse(
            option.displayName(), defectEliminationRate(option.value())))
        .toList();
  }

  private List<QualityBoardChartRowResponse> issueGroupedCountRows(
      List<String> phases,
      String fieldName,
      String emptyLabel) {
    return issueGroupedCounts(phases, fieldName, emptyLabel).entrySet().stream()
        .map(entry -> new QualityBoardChartRowResponse(entry.getKey(), entry.getValue().doubleValue()))
        .sorted((left, right) -> Double.compare(right.value(), left.value()))
        .limit(20)
        .toList();
  }

  private Map<String, Long> issueGroupedCounts(
      List<String> phases,
      String fieldName,
      String emptyLabel) {
    if (phases.isEmpty()) {
      return Map.of();
    }
    String safeFieldName = switch (fieldName) {
      case "function_name" -> "function_name";
      case "fix_user" -> "fix_user";
      default -> throw new IllegalArgumentException("Unsupported issue group field: " + fieldName);
    };
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    String sql =
        """
        select coalesce(nullif(btrim(%s), ''), ?) as group_name,
               count(*) as value
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
         group by group_name
         order by group_name
        """
            .formatted(safeFieldName, placeholders);
    List<Object> queryArgs = new ArrayList<>();
    queryArgs.add(emptyLabel);
    queryArgs.addAll(args);
    Map<String, Long> result = new java.util.LinkedHashMap<>();
    query(
        sql,
        rs -> {
          while (rs.next()) {
            result.put(rs.getString("group_name"), rs.getLong("value"));
          }
          return null;
        },
        queryArgs.toArray());
    return result;
  }

  private double reviewDensity(String projectName, String reviewType) {
    ReviewDensityAccumulator accumulator = new ReviewDensityAccumulator(0, 0);
    reviewRecords(projectName, reviewType)
        .forEach(row -> accumulator.add(row.problemCount(), row.reviewScalePages()));
    return divide(accumulator.problemCount(), accumulator.reviewScalePages(), 1D);
  }

  /**
   * 返回看板当前范围去重后的评审记录，供密度统计与下钻明细共用。
   *
   * @param requestedProjectName 看板稳定业务 key；为空时使用默认候选
   * @param reviewType 评审类型关键字；为空时返回全部类型
   */
  public List<ReviewDataRecordRowResponse> reviewRecords(
      String requestedProjectName, String reviewType) {
    DashboardVersionScope scope = resolveScope(requestedProjectName);
    List<ReviewDataRecordRowResponse> rows = new ArrayList<>();
    Set<Long> seenRecordIds = new HashSet<>();
    for (String reviewProjectName : scope.reviewProjectNames()) {
      reviewDataRecordReadRepository
          .loadRecords(null, reviewProjectName, null, null, null, null, null, null).stream()
          .filter(row -> reviewType == null || matchesReviewType(row.reviewType(), reviewType))
          .forEach(row -> addUniqueRecord(rows, seenRecordIds, row));
    }
    Set<String> projectNames = new HashSet<>();
    scope.reviewProjectNames().forEach(value ->
        projectNames.add(TextQuerySupport.normalizeDisplay(value)));
    reviewDataMatchModeRecordRepository.loadRecords().stream()
        .filter(row -> projectNames.contains(TextQuerySupport.normalizeDisplay(row.projectName())))
        .filter(row -> reviewType == null || matchesReviewType(row.reviewType(), reviewType))
        .forEach(row -> addUniqueRecord(rows, seenRecordIds, row));
    return List.copyOf(rows);
  }

  private void addUniqueRecord(
      List<ReviewDataRecordRowResponse> rows,
      Set<Long> seenRecordIds,
      ReviewDataRecordRowResponse row) {
    if (row.id() != null && !seenRecordIds.add(row.id())) {
      return;
    }
    rows.add(row);
  }

  private List<QualityBoardFixUserSeverityRowResponse> fixUserSeverityRows(List<String> phases) {
    if (phases.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    String sql =
        """
        select
          btrim(fix_user) as person_name,
          sum(case when severity_level = 'LEVEL1' then 1 else 0 end) as level1,
          sum(case when severity_level = 'LEVEL2' then 1 else 0 end) as level2,
          sum(case when severity_level = 'LEVEL3' then 1 else 0 end) as level3,
          sum(case when severity_level = 'SUGGESTION' or coalesce(category, '') like '%%建议%%' then 1 else 0 end) as suggestion,
          count(*) as total
        from issue_fact
        where deleted = false
          and project_id = ?
          and testing_phase in (%s)
          and coalesce(category, '') not like '%%功能屏蔽%%'
          and coalesce(bug_status, '') not like '%%已拒绝%%'
          and nullif(btrim(fix_user), '') is not null
          and btrim(fix_user) <> '无合法评论'
          and btrim(fix_user) not like '未设定%%'
        group by person_name
        having count(*) > 0
        order by total desc, person_name
        """
            .formatted(placeholders);
    return query(
        sql,
        (rs, rowNum) -> new QualityBoardFixUserSeverityRowResponse(
            rs.getString("person_name"),
            rs.getInt("level1"),
            rs.getInt("level2"),
            rs.getInt("level3"),
            rs.getInt("suggestion"),
            rs.getInt("total")),
        args.toArray());
  }

  private List<QualityBoardChartRowResponse> defectRepairUserRows(List<String> phases) {
    if (phases.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    String sql =
        """
        select
          btrim(assignee_name) as person_name,
          count(*)::numeric as value
        from issue_fact
        where deleted = false
          and project_id = ?
          and testing_phase in (%s)
          and %s
          and coalesce(bug_status, '') not like '%%已拒绝%%'
          and nullif(btrim(assignee_name), '') is not null
        group by person_name
        order by value desc, person_name
        """
            .formatted(placeholders, openIssueStatePredicate());
    return query(
        sql,
        (rs, rowNum) -> new QualityBoardChartRowResponse(
            rs.getString("person_name"),
            doubleValue(rs.getObject("value"))),
        args.toArray());
  }

  private double integrationPassRate(String projectName) {
    if (!tableExists("integration_test_fact")) {
      return 0D;
    }
    List<String> testingPhases = integrationPhases(projectName);
    if (testingPhases.isEmpty()) {
      return 0D;
    }
    String placeholders = String.join(",", testingPhases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(testingPhases);
    return query(
        """
        select pass_case, execute_case
          from integration_test_fact
         where deleted = false
           and lower(coalesce(source_instance, '')) = ?
           and project_id = ?
           and testing_phase in (%s)
        """.formatted(placeholders),
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
        args.toArray());
  }

  private double defectLeakageRate(String projectName) {
    List<String> phases = phaseValues(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    String validIssuePredicate = rejectedIssueExclusionPredicate();
    long openCount = issueCount(
        phases, validIssuePredicate + " and " + openIssueStatePredicate());
    long totalCount = issueCount(phases, validIssuePredicate);
    return divide(openCount * 100D, totalCount, 1D);
  }

  private double defectEliminationRate(String projectName) {
    List<String> phases = phaseValues(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    long integrationNotPassCount = integrationNotPassCount(projectName);
    long systemTestIssueCount = issueCount(phases, rejectedIssueExclusionPredicate());
    if (integrationNotPassCount <= 0 || systemTestIssueCount <= 0) {
      return 0D;
    }
    return divide(integrationNotPassCount * 100D, integrationNotPassCount + systemTestIssueCount, 1D);
  }

  private double newIssueFixRate(String projectName) {
    List<String> phases = phaseValues(projectName);
    if (phases.isEmpty()) {
      return 0D;
    }
    long fixedCount = issueCount(phases, newIssueFixedPredicate());
    long totalCount = issueCount(phases, newIssueValidPredicate());
    return divide(fixedCount * 100D, totalCount, 1D);
  }

  /** 新发缺陷修复率的有效缺陷集合；下钻明细复用同一条件，避免统计与明细口径漂移。 */
  public String newIssueValidPredicate() {
    return oldPlatformQueryFilterPredicate()
        + " and coalesce(bug_status, '') not like '%历史遗留%'";
  }

  private String newIssueFixedPredicate() {
    return newIssueValidPredicate()
        + " and upper(coalesce(issue_state, '')) = 'CLOSED'"
        + " and (coalesce(bug_status, '') like '%已修复/完成%'"
        + "      or coalesce(bug_status, '') like '%未复现%'"
        + "      or is_fixed = true)";
  }

  private long integrationNotPassCount(String projectName) {
    if (!tableExists("integration_test_fact")) {
      return 0L;
    }
    List<String> testingPhases = integrationPhases(projectName);
    if (testingPhases.isEmpty()) {
      return 0L;
    }
    String placeholders = String.join(",", testingPhases.stream().map(ignored -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(testingPhases);
    Long count =
        queryForObject(
            """
            select coalesce(sum(not_pass_case), 0)
              from integration_test_fact
             where deleted = false
               and lower(coalesce(source_instance, '')) = ?
               and project_id = ?
               and testing_phase in (%s)
            """.formatted(placeholders),
            Long.class,
            args.toArray());
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
        queryForObject(
            """
            select count(*)
              from issue_fact
             where deleted = false
               and project_id = ?
               and testing_phase in (%s)
            """
                .formatted(placeholders)
                + (extraPredicate == null ? "" : " and " + extraPredicate),
            Long.class,
            args.toArray());
    return count == null ? 0L : count;
  }

  private String oldPlatformQueryFilterPredicate() {
    return """
       coalesce(category, '') not like '%功能屏蔽%'
       and coalesce(bug_status, '') not like '%已拒绝%'
       and coalesce(category, '') not like '%建议%'
       and not (coalesce(bug_status, '') like '%申请否决%' and upper(coalesce(issue_state, '')) like '%CLOSED%')
       and not (coalesce(bug_status, '') like '%需求如此%' and upper(coalesce(issue_state, '')) like '%CLOSED%')
      """;
  }

  private String rejectedIssueExclusionPredicate() {
    return "coalesce(bug_status, '') not like '%已拒绝%'";
  }

  private String openIssueStatePredicate() {
    return "lower(coalesce(issue_state, '')) in ('open', 'opened')";
  }

  private boolean tableExists(String tableName) {
    if (fixedRead != null) {
      boolean bound = fixedRead.facts() != null && fixedRead.facts().contexts().stream()
          .anyMatch(context -> context.table().tableName().equals(tableName));
      if (!bound) { throw new IllegalStateException("完整质量看板缺少必需事实身份：" + tableName); }
      return true;
    }
    try {
      Boolean exists =
          queryForObject(
              "select to_regclass(?) is not null",
              Boolean.class,
              "public." + tableName);
      return Boolean.TRUE.equals(exists);
    } catch (DataAccessException error) {
      return false;
    }
  }

  /** 将请求值规范化为稳定业务键；未传值时使用候选第一项。 */
  public String normalizeProjectName(String requestedProjectName) {
    return resolveScope(requestedProjectName).businessKey();
  }

  /** 返回候选的显示名，仅用于页面文案和文件名。 */
  public String displayProjectName(String requestedProjectName) {
    return resolveScope(requestedProjectName).displayName();
  }

  private DashboardVersionScope resolveScope(String requestedProjectName) {
    return versionScopeService.resolve(
        CROWN_CAD_PROJECT_ID, IssueScopeDimension.TESTING_PHASE, requestedProjectName);
  }

  private List<String> phaseValues(String requestedProjectName) {
    return resolveScope(requestedProjectName).phases().stream()
        .map(DashboardVersionScope.PhaseMember::sourceValue)
        .toList();
  }

  private List<String> integrationPhases(String requestedProjectName) {
    return resolveScope(requestedProjectName).integrationTestPhases();
  }

  private String resolveCodeReviewSource(String requestedSource) {
    return resolveCodeReviewSource(requestedSource, codeReviewReadSupport.configuredReadMode());
  }

  private String resolveCodeReviewSource(
      String requestedSource, CodeReviewDataReadMode codeReviewReadMode) {
    List<OptionItemResponse> options =
        codeReviewReadSupport.listAvailableSources(codeReviewReadMode);
    String normalized = TextQuerySupport.trimToNull(requestedSource);
    if (normalized != null
        && options.stream().anyMatch(option -> option.value().equalsIgnoreCase(normalized))) {
      return normalized.toLowerCase(java.util.Locale.ROOT);
    }
    return options.stream().map(OptionItemResponse::value).findFirst().orElse("cc");
  }

  private Double doubleValue(Object value) {
    return value instanceof Number number
        ? ReviewDataNumberSupport.roundToTwoDecimals(number.doubleValue())
        : 0D;
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

  private <T> T queryForObject(String sql, Class<T> type, Object... args) {
    return fixedRead == null ? jdbcTemplate.queryForObject(sql, type, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).queryForObject(fixedRead, sql, type, args);
  }
  private <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return fixedRead == null ? jdbcTemplate.query(sql, mapper, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).query(fixedRead, sql, mapper, args);
  }
  private <T> T query(String sql, org.springframework.jdbc.core.ResultSetExtractor<T> extractor, Object... args) {
    return fixedRead == null ? jdbcTemplate.query(sql, extractor, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).query(fixedRead, sql, extractor, args);
  }
}
