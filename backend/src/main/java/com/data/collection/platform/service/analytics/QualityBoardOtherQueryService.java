package com.data.collection.platform.service.analytics;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QualityBoardOtherQueryService {
  static final String DEFAULT_PROJECT_NAME = "CC2026R3";
  static final String DEFAULT_QUALITY_RANKING_PROJECT_NAME = "CC2025R1";
  private static final long CROWN_CAD_PROJECT_ID =
      SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID;

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;

  public QualityBoardOtherQueryService(
      JdbcTemplate jdbcTemplate, SystemTestPhaseScopeResolver phaseScopeResolver) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseScopeResolver = phaseScopeResolver;
  }

  List<Row> load(QualityBoardOtherTopic topic, AnalyticsDashboardQueryContext context) {
    String selectedScope = scope(topic, context);
    List<Row> rows = switch (topic) {
      case FUNCTION_DEFECT_COUNT -> functionDefectCountRows(scope(topic, context));
      case FUNCTION_DEFECT_DENSITY -> functionDefectDensityRows(scope(topic, context));
      case QUALITY_RANKING -> qualityRankingRows(scope(topic, context));
      case MEMBER_UNRESOLVED_RATE -> memberUnresolvedRateRows(scope(topic, context));
      case RELEASE_LEAKAGE_RATE -> releaseLeakageRateRows();
      case DEVELOPMENT_LEAKAGE_RATE -> developmentLeakageRateRows();
    };
    return rows.stream()
        .map(row -> StringUtils.hasText(row.scope()) ? row : row.withScope(selectedScope))
        .toList();
  }

  String scope(QualityBoardOtherTopic topic, AnalyticsDashboardQueryContext context) {
    if (topic.scopeParameterKey() == null) {
      return "全部启用版本";
    }
    String fallback = topic == QualityBoardOtherTopic.QUALITY_RANKING
        ? DEFAULT_QUALITY_RANKING_PROJECT_NAME
        : DEFAULT_PROJECT_NAME;
    return context.parameter(topic.scopeParameterKey())
        .filter(StringUtils::hasText)
        .map(String::trim)
        .orElse(fallback);
  }

  List<String> enabledProjectNames() {
    return phaseScopeResolver.listEnabledLegacyCrownCadParentNames();
  }

  private List<Row> functionDefectCountRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    String sql = """
        select btrim(function_name) as item_name,
               count(*)::bigint as numerator
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and nullif(btrim(function_name), '') is not null
           and btrim(function_name) not like '未设定%%'
           and coalesce(bug_status, '') not like '%%已拒绝%%'
         group by btrim(function_name)
         having count(*) > 0
         order by numerator desc, item_name
        """.formatted(scope.placeholders());
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> Row.count(projectName, rs.getString("item_name"), rs.getLong("numerator")),
        scope.args().toArray());
  }

  private List<Row> functionDefectDensityRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    List<Object> args = new ArrayList<>();
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(CROWN_CAD_PROJECT_ID);
    args.add(projectName);
    args.addAll(scope.args());
    String sql = """
        with code_lines as (
          select btrim(function_name) as item_name,
                 sum(greatest(coalesce(added_lines, 0), 0))::bigint as added_lines
            from merge_request_fact
           where deleted = false
             and lower(coalesce(source_instance, '')) = ?
             and project_id = ?
             and project_name = ?
             and nullif(btrim(function_name), '') is not null
             and btrim(function_name) <> '--'
             and coalesce(scan_status, '') <> '无需代码走查'
           group by btrim(function_name)
        ),
        issue_counts as (
          select btrim(function_name) as item_name,
                 count(*)::bigint as defect_count
            from issue_fact
           where deleted = false
             and project_id = ?
             and testing_phase in (%s)
             and nullif(btrim(function_name), '') is not null
             %s
           group by btrim(function_name)
        )
        select lines.item_name,
               issues.defect_count as numerator,
               lines.added_lines as denominator,
               round((issues.defect_count * 100.0 / lines.added_lines)::numeric, 2) as value
          from code_lines lines
          join issue_counts issues on issues.item_name = lines.item_name
         where lines.added_lines > 0
           and issues.defect_count > 0
         order by value desc, lines.item_name
        """.formatted(scope.placeholders(), rejectedIssuePredicate());
    return jdbcTemplate.query(sql, this::mapRatioRow, args.toArray());
  }

  private List<Row> qualityRankingRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    List<Object> args = new ArrayList<>();
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(CROWN_CAD_PROJECT_ID);
    args.add(projectName);
    args.addAll(scope.args());
    String sql = """
        with code_lines as (
          select btrim(author_name) as item_name,
                 sum(greatest(coalesce(added_lines, 0), 0))::bigint as added_lines
            from merge_request_fact
           where deleted = false
             and lower(coalesce(source_instance, '')) = ?
             and project_id = ?
             and project_name = ?
             and nullif(btrim(author_name), '') is not null
             and btrim(author_name) not like '未设定%%'
           group by btrim(author_name)
        ),
        issue_counts as (
          select btrim(fix_user) as item_name,
                 count(*)::bigint as defect_count
            from issue_fact
           where deleted = false
             and project_id = ?
             and testing_phase in (%s)
             and coalesce(bug_status, '') not like '%%已拒绝%%'
             and nullif(btrim(fix_user), '') is not null
             and btrim(fix_user) <> '无合法评论'
             and btrim(fix_user) not like '未设定%%'
           group by btrim(fix_user)
        )
        select lines.item_name,
               issues.defect_count as numerator,
               lines.added_lines as denominator,
               round((issues.defect_count * 1000.0 / lines.added_lines)::numeric, 2) as value
          from code_lines lines
          join issue_counts issues on issues.item_name = lines.item_name
         where lines.added_lines > 0
           and issues.defect_count > 0
         order by value asc, lines.item_name
        """.formatted(scope.placeholders());
    return jdbcTemplate.query(sql, this::mapRatioRow, args.toArray());
  }

  private List<Row> memberUnresolvedRateRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolveLegacyCrownCadPhases(projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SqlScope scope = issueScope(phases);
    String sql = """
        select btrim(fix_user) as item_name,
               count(*) filter (where coalesce(bug_status, '') like '%%未修复%%')::bigint as numerator,
               count(*)::bigint as denominator,
               round((count(*) filter (where coalesce(bug_status, '') like '%%未修复%%') * 100.0
                      / count(*))::numeric, 2) as value
          from issue_fact
         where deleted = false
           and project_id = ?
           and testing_phase in (%s)
           and nullif(btrim(fix_user), '') is not null
           and btrim(fix_user) <> '无合法评论'
           and btrim(fix_user) not like '未设定%%'
           %s
         group by btrim(fix_user)
        having count(*) filter (where coalesce(bug_status, '') like '%%未修复%%') > 0
         order by value desc, item_name
        """.formatted(scope.placeholders(), rejectedIssuePredicate());
    return jdbcTemplate.query(sql, this::mapRatioRow, scope.args().toArray());
  }

  private List<Row> releaseLeakageRateRows() {
    List<String> projectNames = enabledProjectNames();
    Map<String, IssueCounts> issueCounts = issueCountsByProject(projectNames);
    return projectNames.stream()
        .map(projectName -> releaseLeakageRateRow(projectName, issueCounts.get(projectName)))
        .sorted((left, right) -> {
          int byValue = Double.compare(right.value(), left.value());
          return byValue == 0 ? left.name().compareTo(right.name()) : byValue;
        })
        .toList();
  }

  private Row releaseLeakageRateRow(String projectName, IssueCounts counts) {
    long total = counts == null ? 0L : counts.total();
    long open = counts == null ? 0L : counts.open();
    return Row.ratio(projectName, projectName, open, total, percentage(open, total));
  }

  private List<Row> developmentLeakageRateRows() {
    List<String> projectNames = enabledProjectNames();
    Map<String, IssueCounts> issueCounts = issueCountsByProject(projectNames);
    Map<String, Long> integrationCounts = integrationNotPassCountsByProject(projectNames);
    return projectNames.stream()
        .map(projectName -> developmentLeakageRateRow(
            projectName,
            issueCounts.get(projectName),
            integrationCounts.get(projectName)))
        .sorted((left, right) -> {
          int byValue = Double.compare(right.value(), left.value());
          return byValue == 0 ? left.name().compareTo(right.name()) : byValue;
        })
        .toList();
  }

  private Row developmentLeakageRateRow(
      String projectName, IssueCounts counts, Long integrationCount) {
    long systemTestIssues = counts == null ? 0L : counts.total();
    long integrationNotPass = integrationCount == null ? 0L : integrationCount;
    double value = integrationNotPass <= 0 || systemTestIssues <= 0
        ? 0D
        : percentage(integrationNotPass, integrationNotPass + systemTestIssues);
    return Row.ratio(projectName, projectName, integrationNotPass, systemTestIssues, value);
  }

  private Map<String, IssueCounts> issueCountsByProject(List<String> projectNames) {
    BatchScope scope = issueBatchScope(projectNames);
    if (scope.entries().isEmpty()) {
      return Map.of();
    }
    List<Object> args = new ArrayList<>(scope.args());
    args.add(CROWN_CAD_PROJECT_ID);
    String sql = """
        with phase_scope(project_name, testing_phase) as (
          values %s
        )
        select scope.project_name,
               count(issue.testing_phase)::bigint as total_count,
               count(issue.testing_phase) filter (
                 where lower(coalesce(issue.issue_state, '')) in ('open', 'opened')
               )::bigint as open_count
          from phase_scope scope
          left join issue_fact issue
            on issue.deleted = false
           and issue.project_id = ?
           and issue.testing_phase = scope.testing_phase
           %s
         group by scope.project_name
        """.formatted(scope.placeholders(), rejectedIssuePredicate("issue."));
    List<IssueCounts> results = jdbcTemplate.query(
        sql,
        (rs, rowNum) -> new IssueCounts(
            rs.getString("project_name"),
            rs.getLong("total_count"),
            rs.getLong("open_count")),
        args.toArray());
    Map<String, IssueCounts> byProject = new LinkedHashMap<>();
    results.forEach(result -> byProject.put(result.projectName(), result));
    return Map.copyOf(byProject);
  }

  private Map<String, Long> integrationNotPassCountsByProject(List<String> projectNames) {
    if (projectNames.isEmpty() || !tableExists("integration_test_fact")) {
      return Map.of();
    }
    BatchScope scope = integrationBatchScope(projectNames);
    if (scope.entries().isEmpty()) {
      return Map.of();
    }
    String sql = """
        with integration_scope(project_name, testing_phase) as (
          values %s
        )
        select scope.project_name,
               coalesce(sum(integration_fact.not_pass_case), 0)::bigint
                   as integration_not_pass
          from integration_scope scope
          left join integration_test_fact integration_fact
            on integration_fact.deleted = false
           and lower(coalesce(integration_fact.source_instance, '')) = ?
           and integration_fact.project_id = ?
           and integration_fact.testing_phase = scope.testing_phase
         group by scope.project_name
        """.formatted(scope.placeholders());
    Map<String, Long> byProject = new LinkedHashMap<>();
    List<Object> args = new ArrayList<>(scope.args());
    args.add(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    args.add(CROWN_CAD_PROJECT_ID);
    jdbcTemplate.query(
            sql,
            (rs, rowNum) -> new IntegrationCount(
                rs.getString("project_name"), rs.getLong("integration_not_pass")),
            args.toArray())
        .forEach(result -> byProject.put(result.projectName(), result.notPass()));
    return Map.copyOf(byProject);
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

  private SqlScope issueScope(List<String> phases) {
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phases);
    return new SqlScope(
        String.join(",", phases.stream().map(ignored -> "?").toList()),
        List.copyOf(args));
  }

  private BatchScope issueBatchScope(List<String> projectNames) {
    Set<ScopeEntry> entries = new LinkedHashSet<>();
    for (String projectName : projectNames) {
      phaseScopeResolver.resolveLegacyCrownCadPhases(projectName).stream()
          .filter(StringUtils::hasText)
          .map(phase -> new ScopeEntry(projectName, phase))
          .forEach(entries::add);
    }
    return batchScope(entries);
  }

  private BatchScope integrationBatchScope(List<String> projectNames) {
    Set<ScopeEntry> entries = new LinkedHashSet<>();
    projectNames.stream()
        .filter(StringUtils::hasText)
        .map(projectName -> new ScopeEntry(projectName, projectName + "集成测试"))
        .forEach(entries::add);
    return batchScope(entries);
  }

  private BatchScope batchScope(Set<ScopeEntry> entries) {
    List<Object> args = new ArrayList<>(entries.size() * 2);
    entries.forEach(entry -> {
      args.add(entry.projectName());
      args.add(entry.testingPhase());
    });
    return new BatchScope(
        String.join(",", entries.stream().map(ignored -> "(?::text, ?::text)").toList()),
        List.copyOf(args),
        List.copyOf(entries));
  }

  private String rejectedIssuePredicate() {
    return rejectedIssuePredicate("");
  }

  private String rejectedIssuePredicate(String columnPrefix) {
    return """
       and coalesce({prefix}bug_status, '') not like '%已拒绝%'
      """.replace("{prefix}", columnPrefix);
  }

  private Row mapRatioRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return Row.ratio(
        "",
        rs.getString("item_name"),
        rs.getLong("numerator"),
        rs.getLong("denominator"),
        number(rs.getObject("value")));
  }

  private double percentage(long numerator, long denominator) {
    if (numerator <= 0 || denominator <= 0) {
      return 0D;
    }
    return round(numerator * 100D / denominator);
  }

  private double number(Object value) {
    return value instanceof Number number ? round(number.doubleValue()) : 0D;
  }

  private double round(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  record Row(String scope, String name, long numerator, long denominator, double value) {
    static Row count(String scope, String name, long count) {
      return new Row(scope, name, count, 0L, count);
    }

    static Row ratio(String scope, String name, long numerator, long denominator, double value) {
      return new Row(scope, name, numerator, denominator, value);
    }

    Row withScope(String nextScope) {
      return new Row(nextScope, name, numerator, denominator, value);
    }
  }

  private record SqlScope(String placeholders, List<Object> args) {}

  private record BatchScope(
      String placeholders, List<Object> args, List<ScopeEntry> entries) {}

  private record ScopeEntry(String projectName, String testingPhase) {}

  private record IssueCounts(String projectName, long total, long open) {}

  private record IntegrationCount(String projectName, long notPass) {}
}
