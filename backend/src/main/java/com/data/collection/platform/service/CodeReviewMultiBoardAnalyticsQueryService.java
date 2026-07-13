package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMultiBoardAnalyticsQueryService {
  private static final List<String> INVALID_REVIEWERS =
      List.of(
          "没有合法评论",
          "无合法评论",
          "代码走查时间或缺陷数异常",
          "代码走查标题异常",
          "代码走查记录行数异常",
          "GitLab 接口报错");
  private static final List<String> INVALID_PEOPLE =
      List.of("--", "无需标注", "无需走查", "无需走查扫描", "未标注人员");

  private final JdbcTemplate jdbcTemplate;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;

  public CodeReviewMultiBoardAnalyticsQueryService(
      JdbcTemplate jdbcTemplate,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.jdbcTemplate = jdbcTemplate;
    this.codeReviewReadSupport = codeReviewReadSupport;
  }

  public List<OptionItemResponse> listProjectOptions(String source) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return listProjectOptions(source, readMode);
  }

  public List<OptionItemResponse> listProjectOptions(
      String source, CodeReviewDataReadMode readMode) {
    return codeReviewReadSupport.listProjectOptions(normalizeSource(source), readMode);
  }

  public String resolveProjectName(String source, String requestedProjectName) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return resolveProjectScope(source, requestedProjectName, readMode).projectName();
  }

  CodeReviewMultiBoardProjectScope resolveProjectScope(
      String requestedSource,
      String requestedProjectName,
      CodeReviewDataReadMode readMode) {
    String source = normalizeSource(requestedSource);
    List<OptionItemResponse> options = listProjectOptions(source, readMode);
    String requested = TextQuerySupport.trimToNull(requestedProjectName);
    boolean allProjectsAvailable = options.stream()
        .map(OptionItemResponse::value)
        .anyMatch(QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION::equals);
    String projectName;
    if (allProjectsAvailable
        && QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION.equals(requested)) {
      projectName = QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION;
    } else {
      projectName = options.stream()
          .map(OptionItemResponse::value)
          .filter(value -> !QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION.equals(value))
          .filter(value -> requested != null && value.equalsIgnoreCase(requested))
          .findFirst()
          .orElseGet(() -> options.stream()
              .map(OptionItemResponse::value)
              .filter(value -> !QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION.equals(value))
              .findFirst()
              .orElse(allProjectsAvailable
                  ? QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION
                  : ""));
    }
    QualityBoardCodeReviewReadScope readScope =
        QualityBoardCodeReviewReadSupport.ALL_PROJECTS_OPTION.equals(projectName)
            ? codeReviewReadSupport.resolveAllProjectsScope(source, readMode)
            : codeReviewReadSupport.resolveScope(source, projectName, readMode);
    return new CodeReviewMultiBoardProjectScope(source, projectName, readMode, readScope);
  }

  public List<CodeReviewMultiBoardAnalyticsRow> loadRows(
      CodeReviewMultiBoardTopic topic,
      String requestedSource,
      String requestedProjectName) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return loadRows(
        topic, resolveProjectScope(requestedSource, requestedProjectName, readMode));
  }

  public List<CodeReviewMultiBoardAnalyticsRow> loadRows(
      CodeReviewMultiBoardTopic topic,
      String requestedSource,
      String requestedProjectName,
      CodeReviewDataReadMode readMode) {
    return loadRows(
        topic, resolveProjectScope(requestedSource, requestedProjectName, readMode));
  }

  List<CodeReviewMultiBoardAnalyticsRow> loadRows(
      CodeReviewMultiBoardTopic topic, CodeReviewMultiBoardProjectScope projectScope) {
    if (!StringUtils.hasText(projectScope.projectName())) {
      return List.of();
    }
    QualityBoardCodeReviewReadScope scope = projectScope.readScope();
    if (!scope.available()) {
      return List.of();
    }
    ScopedSql scopedSql = scopedSql(scope, topic);
    return jdbcTemplate.query(
        aggregateSql(topic, scopedSql),
        (rs, rowNumber) ->
            new CodeReviewMultiBoardAnalyticsRow(
                rs.getString("row_label"),
                rs.getBigDecimal("value") == null ? BigDecimal.ZERO : rs.getBigDecimal("value")),
        scopedSql.args().toArray());
  }

  public String sourceVersion(String source, String requestedProjectName) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    return sourceVersion(resolveProjectScope(source, requestedProjectName, readMode));
  }

  public String sourceVersion(
      String source,
      String requestedProjectName,
      CodeReviewDataReadMode readMode) {
    return sourceVersion(resolveProjectScope(source, requestedProjectName, readMode));
  }

  String sourceVersion(CodeReviewMultiBoardProjectScope projectScope) {
    if (!StringUtils.hasText(projectScope.projectName())) {
      return "empty";
    }
    QualityBoardCodeReviewReadScope scope = projectScope.readScope();
    if (!scope.available()) {
      return "unavailable";
    }
    ScopedSql scopedSql = scopedSql(scope, null);
    //兼容模式-MatchMode：兼容快照以 synced_at 作为版本；正式事实表只使用 fact_refreshed_at。
    String versionColumn = "code_review_match_mode_records".equals(scope.tableName())
        ? "synced_at"
        : "fact_refreshed_at";
    String value = jdbcTemplate.queryForObject(
        "select coalesce(max(" + versionColumn + ")::text, 'empty') from "
            + scope.tableName()
            + scopedSql.whereClause(),
        String.class,
        scopedSql.args().toArray());
    return value == null ? "empty" : value;
  }

  private ScopedSql scopedSql(
      QualityBoardCodeReviewReadScope scope,
      CodeReviewMultiBoardTopic topic) {
    QualityBoardCodeReviewQueryScope queryScope = codeReviewReadSupport.queryScope(scope);
    List<Object> args = new ArrayList<>(queryScope.args());
    StringBuilder where = new StringBuilder(" where ").append(queryScope.predicate());
    if (scope.projectNames().isEmpty()) {
      where.append(" and nullif(btrim(coalesce(project_name, '')), '') is not null");
    }
    where.append(" and upper(coalesce(merge_request_state, '')) = 'MERGED'")
        .append(scope.deletedPredicate());
    if (topic != null && topic.devBranchOnly()) {
      where.append(" and lower(coalesce(target_branch, '')) = 'dev'");
    }
    if (topic != null && topic.legalReviewOnly()) {
      where.append(" and coalesce(reviewer_names, '') not in (")
          .append(String.join(",", INVALID_REVIEWERS.stream().map(ignored -> "?").toList()))
          .append(")");
      args.addAll(INVALID_REVIEWERS);
    }
    return new ScopedSql(
        scope.tableName(),
        where.toString(),
        List.copyOf(args),
        distinctIdentity(scope));
  }

  private String aggregateSql(CodeReviewMultiBoardTopic topic, ScopedSql scopedSql) {
    String dimension = topic.dimension().column();
    String labelPredicate = labelPredicate(topic);
    String aggregation = switch (topic.aggregation()) {
      case AVERAGE_ROW_DENSITY ->
          "round((sum(case when coalesce(review_defect_density_per_kloc, 0) > 0 "
              + "then review_defect_density_per_kloc else 0 end) / count(*))::numeric, 2)";
      case DEFECT_SUM -> "sum(greatest(coalesce(defect_count, 0), 0))::numeric";
      case DISTINCT_MERGE_REQUEST_COUNT ->
          "count(distinct " + scopedSql.distinctMergeRequestIdentity() + ")::numeric";
      case ROW_COUNT -> "count(*)::numeric";
      case DEFECT_PER_KLOC ->
          "round((sum(greatest(coalesce(defect_count, 0), 0)) * 1000.0 "
              + "/ nullif(sum(greatest(coalesce(added_lines, 0), 0)), 0))::numeric, 2)";
    };
    String having = topic.aggregation() == CodeReviewMultiBoardTopic.Aggregation.DEFECT_PER_KLOC
        ? " having sum(greatest(coalesce(added_lines, 0), 0)) > 0"
        : "";
    return "select btrim(" + dimension + ") as row_label, " + aggregation + " as value from "
        + scopedSql.tableName()
        + scopedSql.whereClause()
        + " and "
        + labelPredicate
        + " group by btrim("
        + dimension
        + ")"
        + having
        + " order by value desc, row_label";
  }

  private String labelPredicate(CodeReviewMultiBoardTopic topic) {
    CodeReviewMultiBoardTopic.Dimension dimension = topic.dimension();
    String column = dimension.column();
    if (dimension == CodeReviewMultiBoardTopic.Dimension.MODULE) {
      String excludedLabels = topic == CodeReviewMultiBoardTopic.CODE_SUBMISSION_DEFECT_DENSITY
          ? "('无需标注')"
          : "('无需标注', '未标注模块名')";
      return "nullif(btrim(coalesce(" + column + ", '')), '') is not null "
          + "and btrim(" + column + ") not in " + excludedLabels;
    }
    return "nullif(btrim(coalesce(" + column + ", '')), '') is not null "
        + "and btrim(" + column + ") not in ("
        + INVALID_PEOPLE.stream().map(value -> "'" + value + "'").reduce((a, b) -> a + "," + b).orElse("''")
        + ") and btrim(" + column + ") not like '%#%'";
  }

  private String distinctIdentity(QualityBoardCodeReviewReadScope scope) {
    //兼容模式-MatchMode：单项目按 MR IID 去重；“全部”追加项目名，避免不同项目的相同 IID 相互吞并。
    return "code_review_match_mode_records".equals(scope.tableName())
        ? (scope.projectNames().isEmpty()
            ? "(project_name, merge_request_iid)"
            : "merge_request_iid")
        : "(project_id, merge_request_id)";
  }

  private String normalizeSource(String source) {
    return "dgm".equalsIgnoreCase(source == null ? "" : source.trim()) ? "dgm" : "cc";
  }

  private record ScopedSql(
      String tableName,
      String whereClause,
      List<Object> args,
      String distinctMergeRequestIdentity) {}
}

record CodeReviewMultiBoardProjectScope(
    String source,
    String projectName,
    CodeReviewDataReadMode readMode,
    QualityBoardCodeReviewReadScope readScope) {}
