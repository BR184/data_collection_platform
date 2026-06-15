package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestHorizontalComparisonExportService {
  private static final String FILTER_GROUP_PARAM = "filterGroup";
  private static final List<String> HEADERS =
      List.of(
          "模块名称",
          "需求评审数据-需求评审缺陷密度(个/页)",
          "需求评审数据-需求文档页数",
          "需求评审数据-需求评审缺陷数",
          "需求评审数据-文档规范",
          "需求评审数据-完整性",
          "需求评审数据-功能性",
          "需求评审数据-可行性",
          "设计评审数据-设计评审缺陷密度(个/页)",
          "设计评审数据-设计文档页数",
          "设计评审数据-设计评审缺陷数",
          "设计评审数据-文档规范",
          "设计评审数据-完整性",
          "设计评审数据-功能性",
          "设计评审数据-可行性",
          "代码走查-代码走查数据(CrownCAD)-代码走查缺陷密度(个/KLOC)",
          "代码走查-代码走查数据(CrownCAD)-代码走查行数",
          "代码走查-代码走查数据(CrownCAD)-代码走查缺陷合计(个)",
          "代码走查-代码走查数据(CrownCAD)-规范类缺陷数(个)",
          "代码走查-代码走查数据(CrownCAD)-逻辑类缺陷数(个)",
          "代码走查-代码走查数据(CrownCAD)-设计类缺陷数(个)",
          "代码走查-代码走查数据(CrownCAD)-性能类缺陷数(个)",
          "代码走查-代码走查数据(CrownCAD)-其他类缺陷数(个)",
          "代码走查-代码走查数据(DGM)-代码走查缺陷密度(个/KLOC)",
          "代码走查-代码走查数据(DGM)-代码走查行数",
          "代码走查-代码走查数据(DGM)-代码走查缺陷合计(个)",
          "代码走查-代码走查数据(DGM)-规范类缺陷数(个)",
          "代码走查-代码走查数据(DGM)-逻辑类缺陷数(个)",
          "代码走查-代码走查数据(DGM)-设计类缺陷数(个)",
          "代码走查-代码走查数据(DGM)-性能类缺陷数(个)",
          "代码走查-代码走查数据(DGM)-其他类缺陷数(个)",
          "缺陷原因-需求理解偏差-个数(个)",
          "缺陷原因-需求理解偏差-占比(%)",
          "缺陷原因-新增需求-个数(个)",
          "缺陷原因-新增需求-占比(%)",
          "缺陷原因-编码逻辑错误-个数(个)",
          "缺陷原因-编码逻辑错误-占比(%)",
          "缺陷原因-环境部署问题-个数(个)",
          "缺陷原因-环境部署问题-占比(%)",
          "缺陷原因-算法机制不支持-个数(个)",
          "缺陷原因-算法机制不支持-占比(%)",
          "缺陷原因-其他原因-个数(个)",
          "缺陷原因-其他原因-占比(%)",
          "一级缺陷-分类-回退",
          "一级缺陷-分类-挂机",
          "一级缺陷-分类-其他",
          "一级缺陷-一级缺陷已修复数量",
          "一级缺陷-一级缺陷数量(个)",
          "一级缺陷-一级缺陷修复率(%)",
          "二级缺陷-二级缺陷已修复数量",
          "二级缺陷-二级缺陷(个)",
          "二级缺陷-二级缺陷修复率(%)",
          "三级缺陷-三级缺陷修复数量",
          "三级缺陷-三级缺陷(个)",
          "三级缺陷-三级缺陷修复率(%)",
          "建议类缺陷(个)",
          "P1-P1级别缺陷",
          "P1-P1缺陷修复率(%)",
          "P1-P1缺陷关闭率(%)",
          "P2-P2级别缺陷",
          "P2-P2缺陷修复率(%)",
          "P2-P2缺陷关闭率(%)",
          "P3-P3级别缺陷",
          "P3-P3缺陷修复率(%)",
          "模块总缺陷数(个)",
          "缺陷占比(%)",
          "延期缺陷占比(%)",
          "已修复/未更新",
          "修复率(%)",
          "关闭率(%)",
          "未关闭缺陷数(个)",
          "申请延期(个)",
          "复测未通过缺陷数(个)",
          "新发议题-新发议题修复数量",
          "新发议题-新发议题数量",
          "新发议题-新发缺陷修复率(%)",
          "新发议题-新发缺陷关闭率(%)",
          "遗留率-一级缺陷遗留率(%)",
          "遗留率-二级缺陷遗留数量",
          "遗留率-三级缺陷遗留数量",
          "遗留率-二三级缺陷遗留率(%)");
  private static final List<String> LEGACY_FIXED_STATUS_TOKENS = List.of("已修复", "待合并", "未更新");
  private static final List<String> LEGACY_RESOLVED_STATUS_TOKENS = List.of("已修复/完成", "未复现");
  private static final List<String> SYSTEM_TEST_SCOPE_TOKENS = List.of("系统测试", "回归测试");
  private static final Set<String> STANDARD_REASON_CATEGORIES =
      Set.of("需求理解偏差", "新增需求", "编码逻辑错误", "环境部署问题", "算法机制不支持");

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public SystemTestHorizontalComparisonExportService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  public String exportCsv(Map<String, String> filters) {
    ExportScope scope = ExportScope.from(filters, parseFilterGroup(filters));
    Map<String, HorizontalRow> rows = new LinkedHashMap<>();
    addModuleRows(rows, loadModules(scope));
    mergeReview(rows, loadReviewMetrics(scope.reviewProjectName(), "需求说明书评审"), true);
    mergeReview(rows, loadReviewMetrics(scope.reviewProjectName(), "设计说明书评审"), false);
    mergeCodeReview(rows, loadCodeReviewMetrics(scope.projectName(), true), true);
    mergeCodeReview(rows, loadCodeReviewMetrics(scope.projectName(), false), false);
    mergeIssues(rows, loadIssueSources(scope), scope);
    if (StringUtils.hasText(scope.moduleName())) {
      rows.keySet().removeIf(moduleName -> !moduleName.equalsIgnoreCase(scope.moduleName()));
    }
    List<HorizontalRow> exportRows =
        rows.values().stream()
            .filter(row -> StringUtils.hasText(row.moduleName()))
            .filter(row -> !row.isEmpty())
            .sorted((a, b) -> a.moduleName().compareToIgnoreCase(b.moduleName()))
            .toList();
    return toCsv(exportRows);
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

  private List<String> loadModules(ExportScope scope) {
    Set<String> modules = new LinkedHashSet<>();
    List<Object> reviewArgs = new ArrayList<>();
    StringBuilder reviewSql =
        new StringBuilder(
            """
            select distinct module_name
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
            reviewSql.toString(),
            String.class,
            reviewArgs.toArray()));

    List<Object> codeReviewArgs = new ArrayList<>();
    StringBuilder codeReviewSql =
        new StringBuilder(
            """
            select distinct module_name
              from merge_request_fact
             where deleted = false
               and nullif(btrim(module_name), '') is not null
               and lower(coalesce(target_branch, '')) = 'dev'
               and upper(coalesce(merge_request_state, '')) = 'MERGED'
            """);
    if (StringUtils.hasText(scope.projectName())) {
      codeReviewSql.append("\n   and lower(project_name) like ?");
      codeReviewArgs.add(like(scope.projectName()));
    }
    modules.addAll(
        jdbcTemplate.queryForList(
            codeReviewSql.toString(),
            String.class,
            codeReviewArgs.toArray()));
    for (IssueExportSource issue : loadIssueSources(scope.withoutModuleFilter())) {
      modules.addAll(issue.moduleNames());
    }
    if (StringUtils.hasText(scope.moduleName())) {
      modules.removeIf(module -> !module.equalsIgnoreCase(scope.moduleName()));
    }
    return modules.stream().filter(StringUtils::hasText).toList();
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
              coalesce(sum(problem.feasibility_count), 0)::integer as feasibility_count
            from review_records r
            left join lateral (
              select
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
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new ReviewMetric(
                text(rs.getString("module_name")),
                rs.getInt("review_pages"),
                rs.getInt("defect_count"),
                rs.getInt("doc_specification_count"),
                rs.getInt("integrity_count"),
                rs.getInt("functionality_count"),
                rs.getInt("feasibility_count")),
        args.toArray());
  }

  private List<CodeReviewMetric> loadCodeReviewMetrics(String projectName, boolean crownCad) {
    String sourcePredicate =
        crownCad
            ? "lower(coalesce(source_instance, 'default')) in ('cc', 'default')"
            : "lower(coalesce(source_instance, '')) = 'dgm'";
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select
              module_name,
              coalesce(sum(added_lines), 0)::integer as added_lines,
              coalesce(sum(code_specification_count), 0)::integer as code_specification_count,
              coalesce(sum(code_logic_specification_count), 0)::integer as code_logic_specification_count,
              coalesce(sum(design_specification_count), 0)::integer as design_specification_count,
              coalesce(sum(performance_specification_count), 0)::integer as performance_specification_count,
              coalesce(sum(other_specification_count), 0)::integer as other_specification_count
            from merge_request_fact
            where deleted = false
              and %s
              and lower(coalesce(target_branch, '')) = 'dev'
              and upper(coalesce(merge_request_state, '')) = 'MERGED'
            """
                .formatted(sourcePredicate));
    if (StringUtils.hasText(projectName)) {
      sql.append("\n      and lower(project_name) like ?");
      args.add(like(projectName));
    }
    sql.append("\n    group by module_name");
    return jdbcTemplate.query(
        sql.toString(),
        (rs, rowNum) ->
            new CodeReviewMetric(
                text(rs.getString("module_name")),
                rs.getInt("added_lines"),
                rs.getInt("code_specification_count"),
                rs.getInt("code_logic_specification_count"),
                rs.getInt("design_specification_count"),
                rs.getInt("performance_specification_count"),
                rs.getInt("other_specification_count")),
        args.toArray());
  }

  private List<IssueExportSource> loadIssueSources(ExportScope scope) {
    List<String> predicates = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    predicates.add("deleted = false");
    predicates.add(systemTestScopePredicate(args));
    predicates.add("is_excluded = false");
    if (StringUtils.hasText(scope.projectName())) {
      predicates.add("lower(coalesce(project_name, '')) like ?");
      args.add(like(scope.projectName()));
    }
    if (StringUtils.hasText(scope.testingPhase())) {
      predicates.add("lower(coalesce(phase_filter_value, '')) = ?");
      args.add(scope.testingPhase().toLowerCase(Locale.ROOT));
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
          reason_category,
          delay_issue,
          is_regression,
          is_crash,
          is_level1_other,
          label_names
        from issue_fact
        where
        """
            + String.join(" and ", predicates),
        this::mapIssueSource,
        args.toArray());
  }

  private String systemTestScopePredicate(List<Object> args) {
    List<String> parts = new ArrayList<>();
    for (String token : SYSTEM_TEST_SCOPE_TOKENS) {
      parts.add("lower(coalesce(testing_phase, '')) like ?");
      args.add("%" + token + "%");
      parts.add("lower(coalesce(system_test_label, '')) like ?");
      args.add("%" + token + "%");
      parts.add("lower(coalesce(label_names, '')) like ?");
      args.add("%" + token + "%");
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
        text(rs.getString("reason_category")),
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
    long overall = issues.size();
    long causeTotal = issues.stream().filter(issue -> StringUtils.hasText(issue.reasonCategory())).count();
    for (IssueExportSource issue : issues) {
      for (String moduleName : issue.moduleNames()) {
        if (StringUtils.hasText(scope.moduleName()) && !moduleName.equalsIgnoreCase(scope.moduleName())) {
          continue;
        }
        rows.computeIfAbsent(moduleName, HorizontalRow::new).issues.add(issue);
      }
    }
    for (HorizontalRow row : rows.values()) {
      row.issueOverallCount = overall;
      row.issueCauseTotal = causeTotal;
    }
  }

  private String toCsv(List<HorizontalRow> rows) {
    StringBuilder builder = new StringBuilder();
    StringJoiner header = new StringJoiner(",");
    HEADERS.forEach(value -> header.add(csv(value)));
    builder.append(header).append('\n');
    for (HorizontalRow row : rows) {
      StringJoiner values = new StringJoiner(",");
      row.values().forEach(value -> values.add(csv(value)));
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
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  private static String floorDecimal(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
  }

  private static String codeReviewDensity(long defectCount, long lineCount) {
    if (lineCount <= 0) {
      return "0";
    }
    return decimal(defectCount * 1000D / lineCount);
  }

  private static String rate(long numerator, long denominator) {
    if (denominator <= 0) {
      return "/";
    }
    return decimal(numerator * 100D / denominator);
  }

  private static boolean containsAny(String value, List<String> tokens) {
    return tokens.stream().anyMatch(token -> StringUtils.hasText(value) && value.contains(token));
  }

  private static ReviewMetric emptyReview() {
    return new ReviewMetric("", 0, 0, 0, 0, 0, 0);
  }

  private static CodeReviewMetric emptyCodeReview() {
    return new CodeReviewMetric("", 0, 0, 0, 0, 0, 0);
  }

  private record ExportScope(String projectName, String reviewProjectName, String testingPhase, String moduleName) {
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
      return new ExportScope(
          blankToNull(project),
          reviewProjectName(project),
          blankToNull(phase),
          blankToNull(module));
    }

    ExportScope withoutModuleFilter() {
      return new ExportScope(projectName, reviewProjectName, testingPhase, null);
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
      int feasibility) {
    String density() {
      if (reviewPages <= 0) {
        return "0";
      }
      return floorDecimal(defectCount * 1D / reviewPages);
    }
  }

  private record CodeReviewMetric(
      String moduleName,
      int addedLines,
      int codeSpecification,
      int codeLogicSpecification,
      int designSpecification,
      int performanceSpecification,
      int otherSpecification) {
    int defectSum() {
      return codeSpecification
          + codeLogicSpecification
          + designSpecification
          + performanceSpecification
          + otherSpecification;
    }

    String density() {
      return codeReviewDensity(defectSum(), addedLines);
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
      String reasonCategory,
      boolean delayIssue,
      boolean regression,
      boolean crash,
      boolean level1Other,
      List<String> labels) {
    boolean isClosed() {
      return closedAtPresent || "closed".equalsIgnoreCase(issueState);
    }

    boolean isLevel1() {
      return "LEVEL1".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel2() {
      return "LEVEL2".equalsIgnoreCase(severityLevel);
    }

    boolean isLevel3() {
      return "LEVEL3".equalsIgnoreCase(severityLevel);
    }

    boolean isSuggestion() {
      return "SUGGESTION".equalsIgnoreCase(severityLevel) || bugStatus.contains("建议") || category.contains("建议");
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

    List<String> values() {
      long total = issues.size();
      long closed = issues.stream().filter(IssueExportSource::isClosed).count();
      long open = total - closed;
      long fixed = issues.stream().filter(IssueExportSource::isLegacyFixed).count();
      long level1 = issues.stream().filter(IssueExportSource::isLevel1).count();
      long level1Fixed = issues.stream().filter(issue -> issue.isLevel1() && issue.isLegacyFixed()).count();
      long level2 = issues.stream().filter(IssueExportSource::isLevel2).count();
      long level2Fixed = issues.stream().filter(issue -> issue.isLevel2() && issue.isLegacyFixed()).count();
      long level3 = issues.stream().filter(IssueExportSource::isLevel3).count();
      long level3Fixed = issues.stream().filter(issue -> issue.isLevel3() && issue.isLegacyFixed()).count();
      long p1 = issues.stream().filter(issue -> issue.isPriority("P1")).count();
      long p1Fixed = issues.stream().filter(issue -> issue.isPriority("P1") && issue.isPriorityFixed()).count();
      long p1Closed = issues.stream().filter(issue -> issue.isPriority("P1") && issue.isClosed()).count();
      long p2 = issues.stream().filter(issue -> issue.isPriority("P2")).count();
      long p2Fixed = issues.stream().filter(issue -> issue.isPriority("P2") && issue.isPriorityFixed()).count();
      long p2Closed =
          issues.stream().filter(issue -> issue.isPriority("P2") && issue.isPriorityClosedWithResolvedStatus()).count();
      long p3 = issues.stream().filter(issue -> issue.isPriority("P3")).count();
      long p3Fixed = issues.stream().filter(issue -> issue.isPriority("P3") && issue.isPriorityFixed()).count();
      long newIssues = issues.stream().filter(IssueExportSource::isNewIssue).count();
      long newFixed = issues.stream().filter(issue -> issue.isNewIssue() && issue.isLegacyFixed()).count();
      long newClosed = issues.stream().filter(IssueExportSource::isNewClosed).count();
      long level2Open = issues.stream().filter(issue -> issue.isLevel2() && !issue.isLegacyFixed()).count();
      long level3Open = issues.stream().filter(issue -> issue.isLevel3() && !issue.isLegacyFixed()).count();
      long level23Fixed =
          issues.stream().filter(issue -> (issue.isLevel2() || issue.isLevel3()) && issue.isLegacyFixed()).count();
      List<String> values = new ArrayList<>();
      values.add(moduleName);
      addReviewValues(values, demandReview);
      addReviewValues(values, designReview);
      addCodeReviewValues(values, crownCadCodeReview);
      addCodeReviewValues(values, dgmCodeReview);
      addReasonValues(values, "需求理解偏差");
      addReasonValues(values, "新增需求");
      addReasonValues(values, "编码逻辑错误");
      addReasonValues(values, "环境部署问题");
      addReasonValues(values, "算法机制不支持");
      long otherReason =
          issues.stream()
              .filter(issue -> StringUtils.hasText(issue.reasonCategory()))
              .filter(issue -> !STANDARD_REASON_CATEGORIES.contains(issue.reasonCategory()))
              .count();
      values.add(count(otherReason));
      values.add(rate(otherReason, issueCauseTotal));
      values.add(count(issues.stream().filter(issue -> issue.isLevel1() && issue.regression()).count()));
      values.add(count(issues.stream().filter(issue -> issue.isLevel1() && issue.crash()).count()));
      values.add(count(issues.stream().filter(issue -> issue.isLevel1() && issue.level1Other()).count()));
      values.add(count(level1Fixed));
      values.add(count(level1));
      values.add(rate(level1Fixed, level1));
      values.add(count(level2Fixed));
      values.add(count(level2));
      values.add(rate(level2Fixed, level2));
      values.add(count(level3Fixed));
      values.add(count(level3));
      values.add(rate(level3Fixed, level3));
      values.add(count(issues.stream().filter(IssueExportSource::isSuggestion).count()));
      values.add(count(p1));
      values.add(rate(p1Fixed, p1));
      values.add(rate(p1Closed, p1));
      values.add(count(p2));
      values.add(rate(p2Fixed, p2));
      values.add(rate(p2Closed, p2));
      values.add(count(p3));
      values.add(rate(p3Fixed, p3));
      values.add(count(total));
      values.add(rate(total, issueOverallCount));
      values.add(rate(issues.stream().filter(IssueExportSource::delayIssue).count(), total));
      values.add(count(fixed));
      values.add(rate(fixed, total));
      values.add(rate(closed, total));
      values.add(count(open));
      values.add(count(issues.stream().filter(IssueExportSource::hasExtensionLabel).count()));
      values.add(count(issues.stream().filter(IssueExportSource::isRetestFailed).count()));
      values.add(count(newFixed));
      values.add(count(newIssues));
      values.add(rate(newFixed, newIssues));
      values.add(rate(newClosed, newIssues));
      values.add(rate(level1 - level1Fixed, level1));
      values.add(count(level2Open));
      values.add(count(level3Open));
      values.add(rate(level23Fixed, total));
      return values;
    }

    private void addReviewValues(List<String> values, ReviewMetric metric) {
      values.add(metric.density());
      values.add(count(metric.reviewPages()));
      values.add(count(metric.defectCount()));
      values.add(count(metric.docSpecification()));
      values.add(count(metric.integrity()));
      values.add(count(metric.functionality()));
      values.add(count(metric.feasibility()));
    }

    private void addCodeReviewValues(List<String> values, CodeReviewMetric metric) {
      values.add(metric.density());
      values.add(count(metric.addedLines()));
      values.add(count(metric.defectSum()));
      values.add(count(metric.codeSpecification()));
      values.add(count(metric.codeLogicSpecification()));
      values.add(count(metric.designSpecification()));
      values.add(count(metric.performanceSpecification()));
      values.add(count(metric.otherSpecification()));
    }

    private void addReasonValues(List<String> values, String reasonCategory) {
      long reasonCount = issues.stream().filter(issue -> reasonCategory.equals(issue.reasonCategory())).count();
      values.add(count(reasonCount));
      values.add(rate(reasonCount, issueCauseTotal));
    }
  }
}
