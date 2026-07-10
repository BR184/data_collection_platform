package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.QualityBoardCodeReviewRecordExportRow;
import com.data.collection.platform.entity.QualityBoardChartRowResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class QualityBoardCodeReviewReadSupport {
  private final JdbcTemplate jdbcTemplate;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;

  public QualityBoardCodeReviewReadSupport(
      JdbcTemplate jdbcTemplate,
      CodeReviewMatchModeSwitchService matchModeSwitchService) {
    this.jdbcTemplate = jdbcTemplate;
    this.matchModeSwitchService = matchModeSwitchService;
  }

  public List<OptionItemResponse> listAvailableSources() {
    if (matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()) {
      //兼容模式-MatchMode：老平台交接期同时开放 CC/DGM；删除兼容模式时删除本分支即可。
      return List.of(
          new OptionItemResponse("CC", "cc"),
          new OptionItemResponse("DGM", "dgm"));
    }
    return List.of(new OptionItemResponse("CC", "cc"));
  }

  public double defectDensity(String source, String projectName) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName);
    if (!scope.available()) {
      return 0D;
    }
    QueryScope queryScope = queryScope(scope);
    String identity = scope.mergeRequestIdentity();
    String sql =
        """
        with scoped as (
          select
            %s,
            id,
            coalesce(defect_count, 0) as defect_count,
            coalesce(added_lines, 0) as added_lines,
            row_number() over(partition by %s order by id asc) as row_number_in_mr
          from %s
          where %s
            and lower(coalesce(target_branch, '')) = 'dev'
            and upper(coalesce(merge_request_state, '')) = 'MERGED'
            %s
        ),
        merged_records as (
          select
            %s,
            sum(case when defect_count = -1 then 0 else defect_count end) as defect_count,
            sum(case when row_number_in_mr = 1 then added_lines else 0 end) as added_lines
          from scoped
          group by %s
        )
        select
          coalesce(sum(defect_count), 0) as defect_count,
          coalesce(sum(added_lines), 0) as added_lines
        from merged_records
        """
            .formatted(
                identity,
                identity,
                scope.tableName(),
                queryScope.predicate(),
                scope.deletedPredicate(),
                identity,
                identity);
    return jdbcTemplate.query(
        sql,
        rs -> {
          if (!rs.next()) {
            return 0D;
          }
          long addedLines = rs.getLong("added_lines");
          long defectCount = rs.getLong("defect_count");
          return addedLines <= 0 || defectCount <= 0
              ? 0D
              : ReviewDataNumberSupport.roundToTwoDecimals(defectCount * 1000D / addedLines);
        },
        queryScope.args().toArray());
  }

  public List<QualityBoardChartRowResponse> personDefectDensityRows(
      String personField,
      boolean excludeIllegalAssigneeRows,
      String source,
      String projectName) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName);
    if (!scope.available()) {
      return List.of();
    }
    String safePersonField = switch (personField) {
      case "assignee_names" -> "assignee_names";
      case "author_name" -> "author_name";
      default -> throw new IllegalArgumentException("Unsupported person field: " + personField);
    };
    QueryScope queryScope = queryScope(scope);
    String illegalAssigneePredicate = excludeIllegalAssigneeRows
        ? " and coalesce(assignee_names, '') not in ('没有合法评论', '代码走查时间或缺陷数异常', '代码走查标题异常', '代码走查记录行数异常')"
        : "";
    String sql =
        """
        select
          person_name,
          round((sum(case when coalesce(review_defect_density_per_kloc, 0) > 0 then review_defect_density_per_kloc else 0 end) / count(*))::numeric, 2) as value
        from (
          select
            nullif(btrim(%s), '') as person_name,
            review_defect_density_per_kloc
          from %s
          where %s
            %s
            %s
        ) scoped
        where person_name is not null
          and person_name <> '无需标注'
          and person_name <> '--'
          and person_name not like '%%#%%'
          and person_name not in ('没有合法评论', '代码走查时间或缺陷数异常', '代码走查标题异常', '代码走查记录行数异常')
        group by person_name
        order by value desc, person_name
        limit 12
        """
            .formatted(
                safePersonField,
                scope.tableName(),
                queryScope.predicate(),
                scope.deletedPredicate(),
                illegalAssigneePredicate);
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new QualityBoardChartRowResponse(
                rs.getString("person_name"),
                numberValue(rs.getObject("value"))),
        queryScope.args().toArray());
  }

  public List<QualityBoardChartRowResponse> frequencyRows(String source, String projectName) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName);
    if (!scope.available()) {
      return List.of();
    }
    QueryScope queryScope = queryScope(scope);
    String sql =
        """
        select
          coalesce(nullif(btrim(author_name), ''), '未标注提交人') as person_name,
          count(*)::numeric as value
        from %s
        where %s
          %s
        group by person_name
        order by value desc, person_name
        limit 12
        """
            .formatted(scope.tableName(), queryScope.predicate(), scope.deletedPredicate());
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new QualityBoardChartRowResponse(
                rs.getString("person_name"),
                numberValue(rs.getObject("value"))),
        queryScope.args().toArray());
  }

  public List<QualityBoardCodeReviewRecordExportRow> codeReviewRecordRows(
      String source,
      String projectName) {
    //兼容模式-MatchMode：质量看板导出必须和统计共用 resolveScope，避免 DGM 兼容表影响正式 CC 读取。
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName);
    if (!scope.available()) {
      return List.of();
    }
    QueryScope queryScope = queryScope(scope);
    String sql =
        """
        select
          source_instance,
          project_name,
          repository_name,
          merge_request_iid,
          title,
          author_name,
          assignee_names,
          target_branch,
          merge_request_state,
          added_lines,
          defect_count,
          review_defect_density_per_kloc
        from %s
        where %s
          %s
        order by merged_at_source desc nulls last, merge_request_iid desc
        limit 5000
        """
            .formatted(scope.tableName(), queryScope.predicate(), scope.deletedPredicate());
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new QualityBoardCodeReviewRecordExportRow(
                rs.getString("source_instance"),
                rs.getString("project_name"),
                rs.getString("repository_name"),
                longValue(rs.getObject("merge_request_iid")),
                rs.getString("title"),
                rs.getString("author_name"),
                rs.getString("assignee_names"),
                rs.getString("target_branch"),
                rs.getString("merge_request_state"),
                intValue(rs.getObject("added_lines")),
                intValue(rs.getObject("defect_count")),
                numberValue(rs.getObject("review_defect_density_per_kloc"))),
        queryScope.args().toArray());
  }

  public Map<String, Long> addedLinesByFunction(String projectName) {
    return groupedAddedLines("function_name", "cc", projectName);
  }

  public Map<String, Long> addedLinesByAuthor(String projectName) {
    return groupedAddedLines("author_name", "cc", projectName);
  }

  QualityBoardCodeReviewReadScope resolveScope(String requestedSource, String requestedProjectName) {
    String source = normalizeSource(requestedSource);
    String projectName = TextQuerySupport.normalizeDisplay(requestedProjectName);
    if (matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()) {
      //兼容模式-MatchMode：兼容快照沿用老平台 source_instance 与 MR IID，项目名同时兼容当前值和历史 DGM 别名。
      List<String> projectNames = "dgm".equals(source)
          ? distinctProjectNames(projectName, legacyDgmProjectName(projectName))
          : List.of(projectName);
      return new QualityBoardCodeReviewReadScope(
          true,
          "code_review_match_mode_records",
          source,
          projectNames,
          "merge_request_iid",
          "");
    }
    if ("dgm".equals(source)) {
      return new QualityBoardCodeReviewReadScope(
          false,
          "merge_request_fact",
          "dgm",
          List.of(),
          "project_id, merge_request_id",
          " and deleted = false");
    }
    return new QualityBoardCodeReviewReadScope(
        true,
        "merge_request_fact",
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        List.of(projectName),
        "project_id, merge_request_id",
        " and deleted = false");
  }

  private Map<String, Long> groupedAddedLines(
      String groupField,
      String source,
      String projectName) {
    String safeGroupField = switch (groupField) {
      case "function_name" -> "function_name";
      case "author_name" -> "author_name";
      default -> throw new IllegalArgumentException("Unsupported code-review group field: " + groupField);
    };
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName);
    if (!scope.available()) {
      return Map.of();
    }
    QueryScope queryScope = queryScope(scope);
    String identity = scope.mergeRequestIdentity();
    String sql =
        """
        with ranked as (
          select
            nullif(btrim(%s), '') as group_name,
            coalesce(added_lines, 0) as added_lines,
            row_number() over(partition by %s order by id asc) as row_number_in_mr
          from %s
          where %s
            %s
        )
        select group_name,
               sum(case when row_number_in_mr = 1 then added_lines else 0 end) as added_lines
          from ranked
         where group_name is not null
         group by group_name
         order by group_name
        """
            .formatted(
                safeGroupField,
                identity,
                scope.tableName(),
                queryScope.predicate(),
                scope.deletedPredicate());
    Map<String, Long> result = new LinkedHashMap<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          while (rs.next()) {
            result.put(rs.getString("group_name"), rs.getLong("added_lines"));
          }
          return null;
        },
        queryScope.args().toArray());
    return result;
  }

  private QueryScope queryScope(QualityBoardCodeReviewReadScope scope) {
    List<Object> args = new ArrayList<>();
    args.add(scope.sourceInstance());
    args.addAll(scope.projectNames());
    String projectPlaceholders =
        String.join(",", scope.projectNames().stream().map(ignored -> "?").toList());
    return new QueryScope(
        "lower(coalesce(source_instance, '')) = ? and coalesce(project_name, '') in ("
            + projectPlaceholders
            + ")",
        args);
  }

  private String normalizeSource(String requestedSource) {
    return "dgm".equalsIgnoreCase(requestedSource == null ? "" : requestedSource.trim())
        ? "dgm"
        : "cc";
  }

  private String legacyDgmProjectName(String projectName) {
    return projectName.replaceFirst("^CC(\\d{4})(R\\d)$", "CrownCAD $1 $2");
  }

  private List<String> distinctProjectNames(String first, String second) {
    if (first.equals(second)) {
      return List.of(first);
    }
    return List.of(first, second);
  }

  private Double numberValue(Object value) {
    return value instanceof Number number
        ? ReviewDataNumberSupport.roundToTwoDecimals(number.doubleValue())
        : 0D;
  }

  private Integer intValue(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private record QueryScope(String predicate, List<Object> args) {}
}

record QualityBoardCodeReviewReadScope(
    boolean available,
    String tableName,
    String sourceInstance,
    List<String> projectNames,
    String mergeRequestIdentity,
    String deletedPredicate) {}
