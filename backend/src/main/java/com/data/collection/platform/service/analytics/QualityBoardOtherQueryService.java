package com.data.collection.platform.service.analytics;

import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.statistics.SystemTestPhaseMembershipPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QualityBoardOtherQueryService {
  private static final long CROWN_CAD_PROJECT_ID =
      IssueScopeCatalogService.CROWN_CAD_PROJECT_ID;

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;

  public QualityBoardOtherQueryService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseScopeResolver = phaseScopeResolver;
    this.codeReviewReadSupport = codeReviewReadSupport;
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
    return context.parameter(topic.scopeParameterKey())
        .filter(StringUtils::hasText)
        .map(String::trim)
        .orElseGet(() -> phaseScopeResolver.defaultParentName(CROWN_CAD_PROJECT_ID));
  }

  List<String> enabledProjectNames() {
    return phaseScopeResolver.listEnabledParentNames(CROWN_CAD_PROJECT_ID);
  }

  String memberScopeSourceVersion() {
    return jdbcTemplate.queryForObject(
        """
        select concat(
                 count(*), ':',
                 coalesce(to_char(max(updated_at), 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'empty')
               )
          from quality_board_member_scopes
         where topic_key = 'QUALITY_RANKING'
           and business_source = 'cc'
        """,
        String.class);
  }

  private List<Row> functionDefectCountRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolvePhases(CROWN_CAD_PROJECT_ID, projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        containsPhasePredicate(phases);
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phasePredicate.args());
    String sql = """
        select btrim(function_name) as item_name,
               count(*)::bigint as numerator
          from issue_fact
         where deleted = false
           and project_id = ?
           and (%s)
           and nullif(btrim(function_name), '') is not null
           and btrim(function_name) not like '未设定%%'
           and coalesce(bug_status, '') not like '%%已拒绝%%'
         group by btrim(function_name)
         having count(*) > 0
         order by numerator desc, item_name
        """.formatted(phasePredicate.sql());
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> Row.count(projectName, rs.getString("item_name"), rs.getLong("numerator")),
        args.toArray());
  }

  private List<Row> functionDefectDensityRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolvePhases(CROWN_CAD_PROJECT_ID, projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    Map<String, Long> addedLines =
        codeReviewReadSupport.reviewedAddedLinesByFunction(projectName);
    if (addedLines.isEmpty()) {
      return List.of();
    }
    Map<String, Long> issueCounts = issueCountsByFunction(phases);
    return addedLines.entrySet().stream()
        .filter(entry -> StringUtils.hasText(entry.getKey()))
        .filter(entry -> !"--".equals(entry.getKey().trim()))
        .map(entry -> {
          String functionName = entry.getKey().trim();
          long defectCount = issueCounts.entrySet().stream()
              .filter(issue -> issue.getKey().contains(functionName))
              .mapToLong(Map.Entry::getValue)
              .sum();
          long lines = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
          return Row.ratio(projectName, functionName, defectCount, lines,
              percentage(defectCount, lines));
        })
        .sorted((left, right) -> {
          int byValue = Double.compare(right.value(), left.value());
          return byValue == 0 ? left.name().compareTo(right.name()) : byValue;
        })
        .toList();
  }

  private List<Row> qualityRankingRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolvePhases(CROWN_CAD_PROJECT_ID, projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    List<QualityMember> members = qualityRankingMembers();
    if (members.isEmpty()) {
      return List.of();
    }
    Map<String, Long> addedLines = codeReviewReadSupport.addedLinesByAuthorAcrossAllProjects();
    Map<MemberPhase, Long> issueCounts = issueCountsByMemberAndPhase(members, phases);
    return members.stream()
        .map(member -> {
          long lines = Math.max(0L, addedLines.getOrDefault(member.name(), 0L));
          long totalDefects = phases.stream()
              .mapToLong(phase -> defectsForMemberPhase(issueCounts, member.name(), phase))
              .sum();
          double value = lines <= 0 || phases.isEmpty()
              ? 0D
              : round(phases.stream()
                  .mapToDouble(phase -> defectsForMemberPhase(issueCounts, member.name(), phase)
                      * 1000D / lines)
                  .average()
                  .orElse(0D));
          return Row.ratio(projectName, member.name(), totalDefects, lines, value);
        })
        .sorted((left, right) -> {
          int byValue = Double.compare(right.value(), left.value());
          return byValue == 0 ? left.name().compareTo(right.name()) : byValue;
        })
        .toList();
  }

  private List<Row> memberUnresolvedRateRows(String projectName) {
    List<String> phases = phaseScopeResolver.resolvePhases(CROWN_CAD_PROJECT_ID, projectName);
    if (phases.isEmpty()) {
      return List.of();
    }
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        containsPhasePredicate(phases);
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phasePredicate.args());
    String sql = """
        select btrim(fix_user) as item_name,
               count(*) filter (where coalesce(bug_status, '') like '%%未修复%%')::bigint as numerator,
               count(*)::bigint as denominator,
               round((count(*) filter (where coalesce(bug_status, '') like '%%未修复%%') * 100.0
                      / count(*))::numeric, 2) as value
          from issue_fact
         where deleted = false
           and project_id = ?
           and (%s)
           and nullif(btrim(fix_user), '') is not null
           and btrim(fix_user) <> '无合法评论'
           and btrim(fix_user) not like '未设定%%'
           %s
         group by btrim(fix_user)
        having count(*) filter (where coalesce(bug_status, '') like '%%未修复%%') > 0
         order by value desc, item_name
        """.formatted(phasePredicate.sql(), rejectedIssuePredicate());
    return jdbcTemplate.query(sql, this::mapRatioRow, args.toArray());
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
    return releaseLeakageRateRows();
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
        ),
        project_scope as (
          select distinct project_name from phase_scope
        )
        select scope.project_name,
               count(issue.testing_phase)::bigint as total_count,
               count(issue.testing_phase) filter (
                 where lower(coalesce(issue.issue_state, '')) in ('open', 'opened')
               )::bigint as open_count
          from project_scope scope
          left join issue_fact issue
            on issue.deleted = false
           and issue.project_id = ?
           and exists (
             select 1
               from phase_scope phase
              where phase.project_name = scope.project_name
                and issue.testing_phase like '%%' || phase.testing_phase || '%%'
           )
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

  private BatchScope issueBatchScope(List<String> projectNames) {
    Set<ScopeEntry> entries = new LinkedHashSet<>();
    for (String projectName : projectNames) {
      phaseScopeResolver.resolvePhases(CROWN_CAD_PROJECT_ID, projectName).stream()
          .filter(StringUtils::hasText)
          .map(phase -> new ScopeEntry(projectName, phase))
          .forEach(entries::add);
    }
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

  private record BatchScope(
      String placeholders, List<Object> args, List<ScopeEntry> entries) {}

  private record ScopeEntry(String projectName, String testingPhase) {}

  private record IssueCounts(String projectName, long total, long open) {}

  private SystemTestPhaseMembershipPolicy.SqlPredicate containsPhasePredicate(
      List<String> phases) {
    return SystemTestPhaseMembershipPolicy.sqlPredicate(
        phases, SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER);
  }

  private Map<String, Long> issueCountsByFunction(List<String> phases) {
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        containsPhasePredicate(phases);
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phasePredicate.args());
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbcTemplate.query(
            """
            select btrim(function_name) as item_name,
                   count(*)::bigint as numerator
              from issue_fact
             where deleted = false
               and project_id = ?
               and (%s)
               and nullif(btrim(function_name), '') is not null
               %s
             group by btrim(function_name)
            """.formatted(phasePredicate.sql(), rejectedIssuePredicate()),
            (rs, rowNum) -> Map.entry(rs.getString("item_name"), rs.getLong("numerator")),
            args.toArray())
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(counts);
  }

  private List<QualityMember> qualityRankingMembers() {
    return jdbcTemplate.query(
        """
        select member_name, display_order
          from quality_board_member_scopes
         where topic_key = 'QUALITY_RANKING'
           and business_source = 'cc'
           and enabled = true
         order by display_order, id
        """,
        (rs, rowNum) -> new QualityMember(
            rs.getString("member_name"), rs.getInt("display_order")));
  }

  private Map<MemberPhase, Long> issueCountsByMemberAndPhase(
      List<QualityMember> members, List<String> phases) {
    SystemTestPhaseMembershipPolicy.SqlPredicate phasePredicate =
        containsPhasePredicate(phases);
    List<Object> args = new ArrayList<>();
    args.add(CROWN_CAD_PROJECT_ID);
    args.addAll(phasePredicate.args());
    args.addAll(members.stream().map(QualityMember::name).toList());
    String memberPlaceholders =
        String.join(",", members.stream().map(ignored -> "?").toList());
    Map<MemberPhase, Long> counts = new LinkedHashMap<>();
    jdbcTemplate.query(
            """
            select btrim(fix_user) as member_name,
                   testing_phase,
                   count(*)::bigint as defect_count
              from issue_fact
             where deleted = false
               and project_id = ?
               and (%s)
               and coalesce(bug_status, '') not like '%%已拒绝%%'
               and btrim(coalesce(fix_user, '')) in (%s)
             group by btrim(fix_user), testing_phase
            """.formatted(phasePredicate.sql(), memberPlaceholders),
            (rs, rowNum) -> Map.entry(
                new MemberPhase(rs.getString("member_name"), rs.getString("testing_phase")),
                rs.getLong("defect_count")),
            args.toArray())
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(counts);
  }

  private long defectsForMemberPhase(
      Map<MemberPhase, Long> counts, String memberName, String phase) {
    return counts.entrySet().stream()
        .filter(entry -> entry.getKey().memberName().equals(memberName))
        .filter(entry -> SystemTestPhaseMembershipPolicy.matches(
            entry.getKey().testingPhase(),
            List.of(phase),
            SystemTestPhaseMembershipPolicy.MatchMode.CONTAINS_MEMBER))
        .mapToLong(Map.Entry::getValue)
        .sum();
  }

  private record QualityMember(String name, int displayOrder) {}

  private record MemberPhase(String memberName, String testingPhase) {}
}
