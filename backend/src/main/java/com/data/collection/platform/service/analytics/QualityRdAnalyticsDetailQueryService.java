package com.data.collection.platform.service.analytics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.TextQuerySupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Raw-row query model shared by the R&D quality dashboard drill-downs and their exports.
 * Each view declares its own business predicate and never falls back to a different fact source.
 */
@Service
public class QualityRdAnalyticsDetailQueryService {
  static final long CROWN_CAD_PROJECT_ID =
      SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID;
  static final long HTGC_PROJECT_ID = 239L;
  private static final String DEFAULT_PROJECT_NAME = "CC2026R3";
  private static final String OPEN_ISSUE_PREDICATE =
      "lower(coalesce(issue_state, '')) in ('open', 'opened')";
  private static final String REJECTED_EXCLUSION_PREDICATE =
      "coalesce(bug_status, '') not like '%已拒绝%'";
  private static final Set<String> ILLEGAL_CODE_REVIEW_PEOPLE = Set.of(
      "没有合法评论",
      "代码走查时间或缺陷数异常",
      "代码走查标题异常",
      "代码走查记录行数异常");

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;

  public QualityRdAnalyticsDetailQueryService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseScopeResolver = phaseScopeResolver;
    this.codeReviewReadSupport = codeReviewReadSupport;
  }

  DetailPage load(String viewKey, AnalyticsDashboardQueryContext context) {
    return switch (viewKey) {
      case "integration-test-results" -> integrationResults(context);
      case "release-leakage-defects" -> releaseLeakageDefects(context);
      case "development-leakage-defects" -> developmentLeakageDefects(context);
      case "fix-user-defects" -> fixUserDefects(context);
      case "assignee-remaining-defects" -> assigneeRemainingSummary(context);
      case "quality-code-review-records" -> codeReviewRecords(context);
      default -> throw new BizException("研发质量看板不支持该详情: " + viewKey);
    };
  }

  List<OptionItemResponse> phaseOptions() {
    return phaseScopeResolver.listEnabledLegacyCrownCadParentNames().stream()
        .filter(TextQuerySupport::hasText)
        .distinct()
        .map(value -> new OptionItemResponse(value, value))
        .toList();
  }

  List<AssigneeSummaryRow> assigneeSummaryRows(
      String requestedProjectName, String requestedAssigneeName) {
    List<String> phases = phases(requestedProjectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    StringBuilder sql = new StringBuilder(
        """
        select btrim(assignee_name) as assignee_name, count(*) as remaining_count
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and %s
           and nullif(btrim(assignee_name), '') is not null
        """.formatted(scope.placeholders(), OPEN_ISSUE_PREDICATE));
    List<Object> args = new ArrayList<>(scope.args());
    String assigneeName = TextQuerySupport.trimToNull(requestedAssigneeName);
    if (assigneeName != null) {
      sql.append(" and btrim(assignee_name) = ?");
      args.add(assigneeName);
    }
    sql.append(" group by btrim(assignee_name) order by remaining_count desc, assignee_name");
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNumber) ->
            new AssigneeSummaryRow(rs.getString("assignee_name"), rs.getLong("remaining_count")),
        args.toArray());
  }

  List<AssigneeSummaryRow> assigneeSummaryRows(AnalyticsDashboardQueryContext context) {
    List<AssigneeSummaryRow> rows = new ArrayList<>(assigneeSummaryRows(
        projectName(context), context.parameter("assigneeName").orElse(null)));
    if (context.sortField() == null) {
      return List.copyOf(rows);
    }
    Comparator<AssigneeSummaryRow> comparator = "assigneeName".equals(context.sortField())
        ? Comparator.comparing(AssigneeSummaryRow::assigneeName)
        : Comparator.comparingLong(AssigneeSummaryRow::remainingCount);
    if ("desc".equalsIgnoreCase(context.sortOrder())) {
      comparator = comparator.reversed();
    }
    rows.sort(comparator.thenComparing(AssigneeSummaryRow::assigneeName));
    return List.copyOf(rows);
  }

  List<CcAssigneeDetailRow> ccAssigneeDetailRows(
      String requestedProjectName, String requestedAssigneeName) {
    List<String> phases = phases(requestedProjectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    List<IssueAggregateSource> sources = loadAggregateSources(
        CROWN_CAD_PROJECT_ID,
        phases,
        requestedAssigneeName,
        true);
    Map<ModuleAssigneeKey, MutableDefectStatistics> grouped = new LinkedHashMap<>();
    for (IssueAggregateSource source : sources) {
      for (String module : moduleNames(source.moduleNames(), source.moduleName())) {
        grouped.computeIfAbsent(
                new ModuleAssigneeKey(module, source.assigneeName()),
                ignored -> new MutableDefectStatistics())
            .add(source);
      }
    }
    Map<String, Long> moduleTotals = new LinkedHashMap<>();
    grouped.forEach((key, value) -> moduleTotals.merge(key.moduleName(), value.count, Long::sum));
    return grouped.entrySet().stream()
        .map(entry -> entry.getValue().toCcRow(entry.getKey()))
        .sorted(
            Comparator.<CcAssigneeDetailRow>comparingLong(
                    row -> moduleTotals.getOrDefault(row.moduleName(), 0L))
                .reversed()
                .thenComparing(CcAssigneeDetailRow::moduleName)
                .thenComparing(Comparator.comparingLong(CcAssigneeDetailRow::count).reversed())
                .thenComparing(CcAssigneeDetailRow::assigneeName))
        .toList();
  }

  List<HtgcAssigneeDetailRow> htgcAssigneeDetailRows() {
    List<IssueAggregateSource> sources =
        loadAggregateSources(HTGC_PROJECT_ID, List.of(), null, false);
    Map<String, MutableDefectStatistics> grouped = new LinkedHashMap<>();
    for (IssueAggregateSource source : sources) {
      grouped.computeIfAbsent(source.assigneeName(), ignored -> new MutableDefectStatistics())
          .add(source);
    }
    return grouped.entrySet().stream()
        .map(entry -> entry.getValue().toHtgcRow(entry.getKey()))
        .sorted(
            Comparator.comparingLong(HtgcAssigneeDetailRow::count)
                .reversed()
                .thenComparing(HtgcAssigneeDetailRow::assigneeName))
        .toList();
  }

  boolean htgcDataReady() {
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where deleted = false and project_id = ?",
        Long.class,
        HTGC_PROJECT_ID);
    return count != null && count > 0;
  }

  private DetailPage integrationResults(AnalyticsDashboardQueryContext context) {
    if (!tableExists("integration_test_fact")) {
      return DetailPage.empty();
    }
    String projectName = projectName(context);
    List<Map<String, Object>> rows = jdbcTemplate.query(
        """
        select source_instance, project_name, issue_iid, title, issue_state,
               module_name, function_name, executor, testing_phase,
               execute_case, pass_case, not_pass_case, problem_case,
               exception_count, updated_at_source
          from integration_test_fact
         where deleted = false
           and lower(coalesce(source_instance, '')) = ?
           and project_id = ?
           and testing_phase = ?
        """,
        (rs, rowNumber) -> {
          Integer executeCase = integer(rs.getObject("execute_case"));
          Integer passCase = integer(rs.getObject("pass_case"));
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("sourceInstance", rs.getString("source_instance"));
          row.put("projectName", rs.getString("project_name"));
          row.put("issueIid", rs.getObject("issue_iid"));
          row.put("title", rs.getString("title"));
          row.put("issueState", rs.getString("issue_state"));
          row.put("moduleName", rs.getString("module_name"));
          row.put("functionName", rs.getString("function_name"));
          row.put("executor", rs.getString("executor"));
          row.put("testingPhase", rs.getString("testing_phase"));
          row.put("executeCase", executeCase);
          row.put("passCase", passCase);
          row.put("notPassCase", rs.getObject("not_pass_case"));
          row.put("problemCase", rs.getObject("problem_case"));
          row.put("exceptionCount", rs.getObject("exception_count"));
          row.put("passRate", percentage(passCase, executeCase));
          row.put("updatedAt", rs.getObject("updated_at_source"));
          return row;
        },
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        CROWN_CAD_PROJECT_ID,
        projectName + "集成测试");
    return page(rows, context, "updatedAt", true, "issueIid");
  }

  private DetailPage releaseLeakageDefects(AnalyticsDashboardQueryContext context) {
    List<Map<String, Object>> rows = issueRows(
        phases(projectName(context)),
        REJECTED_EXCLUSION_PREDICATE,
        Map.of(),
        row -> row.put(
            "leakageState",
            isOpen(String.valueOf(row.getOrDefault("issueState", ""))) ? "未关闭" : "已关闭"));
    return page(rows, context, "updatedAt", true, "issueIid");
  }

  private DetailPage developmentLeakageDefects(AnalyticsDashboardQueryContext context) {
    String projectName = projectName(context);
    List<Map<String, Object>> rows = new ArrayList<>();
    if (tableExists("integration_test_fact")) {
      rows.addAll(jdbcTemplate.query(
          """
          select project_name, issue_iid, title, testing_phase, module_name,
                 executor, not_pass_case, updated_at_source
            from integration_test_fact
           where deleted = false
             and lower(coalesce(source_instance, '')) = ?
             and project_id = ?
             and testing_phase = ?
          """,
          (rs, rowNumber) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordType", "集成测试未通过用例");
            row.put("projectName", rs.getString("project_name"));
            row.put("issueIid", rs.getObject("issue_iid"));
            row.put("title", rs.getString("title"));
            row.put("testingPhase", rs.getString("testing_phase"));
            row.put("moduleName", rs.getString("module_name"));
            row.put("personName", rs.getString("executor"));
            row.put("metricContribution", rs.getObject("not_pass_case"));
            row.put("updatedAt", rs.getObject("updated_at_source"));
            return row;
          },
          GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
          CROWN_CAD_PROJECT_ID,
          projectName + "集成测试"));
    }
    rows.addAll(issueRows(
        phases(projectName),
        REJECTED_EXCLUSION_PREDICATE,
        Map.of(),
        row -> {
          row.put("recordType", "系统测试有效缺陷");
          row.put("personName", row.get("assigneeName"));
          row.put("metricContribution", 1);
        }));
    return page(rows, context, "updatedAt", true, "issueIid");
  }

  private DetailPage fixUserDefects(AnalyticsDashboardQueryContext context) {
    Map<String, String> filters = new LinkedHashMap<>();
    context.parameter("fixUser").ifPresent(value -> filters.put("fixUser", value));
    context.parameter("severityLevel").ifPresent(value -> filters.put("severityLevel", value));
    List<Map<String, Object>> rows = issueRows(
        phases(projectName(context)),
        REJECTED_EXCLUSION_PREDICATE
            + " and coalesce(category, '') not like '%功能屏蔽%'"
            + " and nullif(btrim(fix_user), '') is not null"
            + " and btrim(fix_user) <> '无合法评论'"
            + " and btrim(fix_user) not like '未设定%'",
        filters,
        ignored -> {});
    return page(rows, context, "updatedAt", true, "issueIid");
  }

  private DetailPage assigneeRemainingSummary(AnalyticsDashboardQueryContext context) {
    validateCrownCadProject(context.parameter("projectId").orElse(null));
    List<Map<String, Object>> rows = assigneeSummaryRows(
            projectName(context), context.parameter("assigneeName").orElse(null))
        .stream()
        .map(item -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("assigneeName", item.assigneeName());
          row.put("remainingCount", item.remainingCount());
          return row;
        })
        .toList();
    return page(rows, context, "remainingCount", true, "assigneeName");
  }

  private DetailPage codeReviewRecords(AnalyticsDashboardQueryContext context) {
    String source = "dgm".equalsIgnoreCase(context.parameter("source").orElse("")) ? "dgm" : "cc";
    String topic = context.parameter("topic")
        .orElseThrow(() -> new BizException("代码走查详情缺少图表口径"));
    String reviewerName = context.parameter("reviewerName").orElse(null);
    String authorName = context.parameter("authorName").orElse(null);
    //兼容模式-MatchMode：统一委托 QualityBoardCodeReviewReadSupport 选择隔离读源；
    //兼容模式-MatchMode：正式模式的 DGM 结果保持为空，禁止回落读取兼容快照或正式 CC 表。
    List<Map<String, Object>> rows = codeReviewReadSupport
        .codeReviewRecordRows(
            source, projectName(context), CodeReviewAnalyticsReadMode.from(context.readMode()))
        .stream()
        .filter(row -> matchesCodeReviewTopic(row, topic, reviewerName, authorName))
        .map(this::codeReviewRecord)
        .toList();
    return page(rows, context, "mergeRequestIid", true, "title");
  }

  private boolean matchesCodeReviewTopic(
      QualityBoardCodeReviewRecordExportRow row,
      String topic,
      String reviewerName,
      String authorName) {
    return switch (topic) {
      case "reviewer-density" ->
          isValidDensityPerson(row.reviewerNames())
              && (reviewerName == null || reviewerName.equals(trim(row.reviewerNames())));
      case "author-density" ->
          isValidDensityPerson(row.authorName())
              && isValidAuthorDensityAssigneeRow(row.reviewerNames())
              && (authorName == null || authorName.equals(trim(row.authorName())));
      case "submission-frequency" ->
          !trim(row.authorName()).isEmpty()
              && (authorName == null || authorName.equals(trim(row.authorName())));
      default -> throw new BizException("不支持的质量看板代码走查详情口径: " + topic);
    };
  }

  private boolean isValidDensityPerson(String value) {
    String person = trim(value);
    return !person.isEmpty()
        && !"无需标注".equals(person)
        && !"--".equals(person)
        && !person.contains("#")
        && !ILLEGAL_CODE_REVIEW_PEOPLE.contains(person);
  }

  private boolean isValidAuthorDensityAssigneeRow(String value) {
    return !ILLEGAL_CODE_REVIEW_PEOPLE.contains(value == null ? "" : value);
  }

  private Map<String, Object> codeReviewRecord(QualityBoardCodeReviewRecordExportRow source) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("source", source.source());
    row.put("projectName", source.projectName());
    row.put("repositoryName", source.repositoryName());
    row.put("mergeRequestIid", source.mergeRequestIid());
    row.put("title", source.title());
    row.put("authorName", source.authorName());
    row.put("reviewerName", source.reviewerNames());
    row.put("targetBranch", source.targetBranch());
    row.put("mergeRequestState", source.mergeRequestState());
    row.put("addedLines", source.addedLines());
    row.put("defectCount", source.defectCount());
    row.put("defectDensity", source.reviewDefectDensityPerKloc());
    return row;
  }

  private List<Map<String, Object>> issueRows(
      List<String> phases,
      String predicate,
      Map<String, String> filters,
      RowEnricher enricher) {
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    StringBuilder sql = new StringBuilder(
        """
        select source_instance, project_name, issue_iid, title, issue_state,
               testing_phase, module_name, module_names, function_name,
               author_name, assignee_name, fix_user, severity_level,
               priority_level, urgency, bug_status, category, delay_cause,
               created_at_source, updated_at_source
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and %s
        """.formatted(scope.placeholders(), predicate));
    List<Object> args = new ArrayList<>(scope.args());
    appendExactFilter(sql, args, "fix_user", filters.get("fixUser"));
    appendSeverityFilter(sql, args, filters.get("severityLevel"));
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNumber) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("sourceInstance", rs.getString("source_instance"));
          row.put("projectName", rs.getString("project_name"));
          row.put("issueIid", rs.getObject("issue_iid"));
          row.put("title", rs.getString("title"));
          row.put("issueState", rs.getString("issue_state"));
          row.put("testingPhase", rs.getString("testing_phase"));
          row.put("moduleName", displayModules(
              rs.getString("module_names"), rs.getString("module_name")));
          row.put("functionName", rs.getString("function_name"));
          row.put("authorName", rs.getString("author_name"));
          row.put("assigneeName", rs.getString("assignee_name"));
          row.put("fixUser", rs.getString("fix_user"));
          row.put("severityLevel", rs.getString("severity_level"));
          row.put("priorityLevel", firstText(
              rs.getString("priority_level"), rs.getString("urgency")));
          row.put("bugStatus", rs.getString("bug_status"));
          row.put("category", rs.getString("category"));
          row.put("delayCause", rs.getString("delay_cause"));
          row.put("createdAt", rs.getObject("created_at_source"));
          row.put("updatedAt", rs.getObject("updated_at_source"));
          enricher.enrich(row);
          return row;
        },
        args.toArray());
  }

  private List<IssueAggregateSource> loadAggregateSources(
      long projectId,
      List<String> phases,
      String requestedAssigneeName,
      boolean excludeFunctionShield) {
    StringBuilder sql = new StringBuilder(
        """
        select module_name, module_names, btrim(assignee_name) as assignee_name,
               bug_status, delay_cause, severity_level, priority_level, urgency
          from issue_fact
         where deleted = false
           and project_id = ?
           and %s
           and %s
           and nullif(btrim(assignee_name), '') is not null
        """.formatted(OPEN_ISSUE_PREDICATE, REJECTED_EXCLUSION_PREDICATE));
    List<Object> args = new ArrayList<>();
    args.add(projectId);
    if (!phases.isEmpty()) {
      sql.append(" and testing_phase in (")
          .append(String.join(",", phases.stream().map(ignored -> "?").toList()))
          .append(")");
      args.addAll(phases);
    }
    if (excludeFunctionShield) {
      sql.append(" and coalesce(category, '') not like '%功能屏蔽%'");
    }
    String assigneeName = TextQuerySupport.trimToNull(requestedAssigneeName);
    if (assigneeName != null) {
      sql.append(" and btrim(assignee_name) = ?");
      args.add(assigneeName);
    }
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNumber) -> new IssueAggregateSource(
            rs.getString("module_name"),
            rs.getString("module_names"),
            rs.getString("assignee_name"),
            rs.getString("bug_status"),
            rs.getString("delay_cause"),
            rs.getString("severity_level"),
            firstText(rs.getString("priority_level"), rs.getString("urgency"))),
        args.toArray());
  }

  private DetailPage page(
      List<Map<String, Object>> source,
      AnalyticsDashboardQueryContext context,
      String defaultSortField,
      boolean defaultDescending,
      String tieBreakField) {
    String sortField = context.sortField() == null ? defaultSortField : context.sortField();
    boolean descending = context.sortField() == null
        ? defaultDescending
        : "desc".equalsIgnoreCase(context.sortOrder());
    List<Map<String, Object>> rows = new ArrayList<>(source);
    Comparator<Map<String, Object>> comparator = (left, right) ->
        compareValues(left.get(sortField), right.get(sortField), descending);
    if (tieBreakField != null && !tieBreakField.equals(sortField)) {
      comparator = comparator.thenComparing(
          (left, right) -> compareValues(left.get(tieBreakField), right.get(tieBreakField), false));
    }
    rows.sort(comparator);
    long offset = (long) (context.page() - 1) * context.size();
    int fromIndex = (int) Math.min(offset, rows.size());
    int toIndex = Math.min(fromIndex + context.size(), rows.size());
    return new DetailPage(List.copyOf(rows.subList(fromIndex, toIndex)), rows.size());
  }

  private int compareValues(Object left, Object right, boolean descending) {
    if (left == right) {
      return 0;
    }
    if (left == null) {
      return 1;
    }
    if (right == null) {
      return -1;
    }
    int result;
    if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
      result = new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(rightNumber.toString()));
    } else if (left instanceof LocalDateTime leftTime && right instanceof LocalDateTime rightTime) {
      result = leftTime.compareTo(rightTime);
    } else {
      result = left.toString().compareToIgnoreCase(right.toString());
    }
    return descending ? -result : result;
  }

  private List<String> phases(String requestedProjectName) {
    return phaseScopeResolver.resolveLegacyCrownCadPhases(canonicalProjectName(requestedProjectName));
  }

  private SqlScope issueScope(List<String> phases) {
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    return new SqlScope(
        String.join(",", phases.stream().map(ignored -> "?").toList()),
        List.copyOf(args));
  }

  private String projectName(AnalyticsDashboardQueryContext context) {
    return canonicalProjectName(context.parameter("projectName").orElse(null));
  }

  String canonicalProjectName(String requestedProjectName) {
    String normalized = TextQuerySupport.trimToNull(requestedProjectName);
    if (normalized != null) {
      return TextQuerySupport.normalizeDisplay(normalized);
    }
    return phaseScopeResolver.listEnabledLegacyCrownCadParentNames().stream()
        .filter(TextQuerySupport::hasText)
        .findFirst()
        .orElse(DEFAULT_PROJECT_NAME);
  }

  private void validateCrownCadProject(String projectId) {
    String normalized = TextQuerySupport.trimToNull(projectId);
    if (normalized != null && !Long.toString(CROWN_CAD_PROJECT_ID).equals(normalized)) {
      throw new BizException("指派人剩余缺陷详情仅支持 CrownCAD 项目");
    }
  }

  private void appendExactFilter(
      StringBuilder sql, List<Object> args, String safeColumn, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    sql.append(" and btrim(").append(safeColumn).append(") = ?");
    args.add(normalized);
  }

  private void appendSeverityFilter(
      StringBuilder sql, List<Object> args, String requestedSeverityLevel) {
    String severityLevel = TextQuerySupport.trimToNull(requestedSeverityLevel);
    if (severityLevel == null) {
      return;
    }
    if ("SUGGESTION".equalsIgnoreCase(severityLevel)) {
      sql.append(" and (severity_level = 'SUGGESTION' or coalesce(category, '') like '%建议%')");
      return;
    }
    appendExactFilter(sql, args, "severity_level", severityLevel);
  }

  private boolean tableExists(String tableName) {
    try {
      Boolean exists = jdbcTemplate.queryForObject(
          "select to_regclass(?) is not null", Boolean.class, "public." + tableName);
      return Boolean.TRUE.equals(exists);
    } catch (DataAccessException error) {
      return false;
    }
  }

  private List<String> moduleNames(String moduleNames, String moduleName) {
    String source = firstText(moduleNames, moduleName);
    if (source == null) {
      return List.of("未设定模块");
    }
    Set<String> values = new LinkedHashSet<>();
    for (String value : source.split("[,，&、]+")) {
      String normalized = TextQuerySupport.trimToNull(value);
      if (normalized != null) {
        values.add(normalized);
      }
    }
    return values.isEmpty() ? List.of("未设定模块") : List.copyOf(values);
  }

  private String displayModules(String moduleNames, String moduleName) {
    return String.join("、", moduleNames(moduleNames, moduleName));
  }

  private String firstText(String first, String second) {
    String normalized = TextQuerySupport.trimToNull(first);
    return normalized == null ? TextQuerySupport.trimToNull(second) : normalized;
  }

  private String trim(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "" : normalized;
  }

  private Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private double percentage(Integer numerator, Integer denominator) {
    if (numerator == null || denominator == null || denominator <= 0) {
      return 0D;
    }
    return round(numerator * 100D / denominator);
  }

  private boolean isOpen(String state) {
    String normalized = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
    return "open".equals(normalized) || "opened".equals(normalized);
  }

  private static double round(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  record DetailPage(List<Map<String, Object>> records, long total) {
    DetailPage {
      records = records == null ? List.of() : List.copyOf(records);
    }

    static DetailPage empty() {
      return new DetailPage(List.of(), 0L);
    }
  }

  record AssigneeSummaryRow(String assigneeName, long remainingCount) {}

  record CcAssigneeDetailRow(
      String moduleName,
      String assigneeName,
      long count,
      long fixedCount,
      long remainingCount,
      long unreproducibleCount,
      long designCount,
      long technicalBlockCount,
      double fixedRate,
      long level1Count,
      long level1FixedCount,
      double level1FixedRate,
      long p1Count,
      long p1FixedCount,
      double p1FixedRate,
      long p2Count,
      long p2FixedCount,
      double p2FixedRate) {}

  record HtgcAssigneeDetailRow(
      String assigneeName,
      long count,
      long fixedCount,
      long remainingCount,
      long unreproducibleCount,
      double fixedRate,
      long level1Count,
      long level1FixedCount,
      double level1FixedRate,
      long level2Count,
      long level2FixedCount,
      double level2FixedRate,
      long level3Count,
      long level3FixedCount,
      double level3FixedRate) {}

  private record SqlScope(String placeholders, List<Object> args) {}

  private record ModuleAssigneeKey(String moduleName, String assigneeName) {}

  private record IssueAggregateSource(
      String moduleName,
      String moduleNames,
      String assigneeName,
      String bugStatus,
      String delayCause,
      String severityLevel,
      String priorityLevel) {}

  @FunctionalInterface
  private interface RowEnricher {
    void enrich(Map<String, Object> row);
  }

  private static final class MutableDefectStatistics {
    private long count;
    private long fixedCount;
    private long unreproducibleCount;
    private long designCount;
    private long technicalBlockCount;
    private long level1Count;
    private long level1FixedCount;
    private long level2Count;
    private long level2FixedCount;
    private long level3Count;
    private long level3FixedCount;
    private long p1Count;
    private long p1FixedCount;
    private long p2Count;
    private long p2FixedCount;

    private void add(IssueAggregateSource source) {
      count++;
      boolean fixed = isFixedStatus(source.bugStatus());
      if (fixed) {
        fixedCount++;
      }
      if ("未复现".equals(trimValue(source.bugStatus()))) {
        unreproducibleCount++;
      }
      if ("需求如此".equals(trimValue(source.bugStatus()))) {
        designCount++;
      }
      if (source.delayCause() != null && source.delayCause().contains("技术卡点")) {
        technicalBlockCount++;
      }
      if ("LEVEL1".equalsIgnoreCase(trimValue(source.severityLevel()))) {
        level1Count++;
        if (fixed) {
          level1FixedCount++;
        }
      }
      if ("LEVEL2".equalsIgnoreCase(trimValue(source.severityLevel()))) {
        level2Count++;
        if (fixed) {
          level2FixedCount++;
        }
      }
      if ("LEVEL3".equalsIgnoreCase(trimValue(source.severityLevel()))) {
        level3Count++;
        if (fixed) {
          // Corrected old-platform source bug: level-three fixed count must use LEVEL3, not P2.
          level3FixedCount++;
        }
      }
      if ("P1".equalsIgnoreCase(trimValue(source.priorityLevel()))) {
        p1Count++;
        if (fixed) {
          p1FixedCount++;
        }
      }
      if ("P2".equalsIgnoreCase(trimValue(source.priorityLevel()))) {
        p2Count++;
        if (fixed) {
          p2FixedCount++;
        }
      }
    }

    private CcAssigneeDetailRow toCcRow(ModuleAssigneeKey key) {
      return new CcAssigneeDetailRow(
          key.moduleName(), key.assigneeName(), count, fixedCount, count - fixedCount,
          unreproducibleCount, designCount, technicalBlockCount, rate(fixedCount, count),
          level1Count, level1FixedCount, rate(level1FixedCount, level1Count),
          p1Count, p1FixedCount, rate(p1FixedCount, p1Count),
          p2Count, p2FixedCount, rate(p2FixedCount, p2Count));
    }

    private HtgcAssigneeDetailRow toHtgcRow(String assigneeName) {
      return new HtgcAssigneeDetailRow(
          assigneeName, count, fixedCount, count - fixedCount, unreproducibleCount,
          rate(fixedCount, count),
          level1Count, level1FixedCount, rate(level1FixedCount, level1Count),
          level2Count, level2FixedCount, rate(level2FixedCount, level2Count),
          level3Count, level3FixedCount, rate(level3FixedCount, level3Count));
    }

    private static boolean isFixedStatus(String bugStatus) {
      String value = bugStatus == null ? "" : bugStatus;
      return value.contains("已修复") || value.contains("待合并") || value.contains("未更新");
    }

    private static String trimValue(String value) {
      return value == null ? "" : value.trim();
    }

    private static double rate(long numerator, long denominator) {
      return denominator <= 0 ? 0D : round(numerator * 100D / denominator);
    }
  }
}
