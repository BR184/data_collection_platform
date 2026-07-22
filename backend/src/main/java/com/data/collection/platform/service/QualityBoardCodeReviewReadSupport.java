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
  static final String ALL_PROJECTS_OPTION = "全部";
  private static final String DEFAULT_CC_PROJECT = "CC2026R3";
  private static final String DEFAULT_DGM_PROJECT = "CrownCAD 2026 R3";

  private final JdbcTemplate jdbcTemplate;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;

  public QualityBoardCodeReviewReadSupport(
      JdbcTemplate jdbcTemplate,
      CodeReviewMatchModeSwitchService matchModeSwitchService) {
    this.jdbcTemplate = jdbcTemplate;
    this.matchModeSwitchService = matchModeSwitchService;
  }

  public List<OptionItemResponse> listAvailableSources() {
    return listAvailableSources(configuredReadMode());
  }

  public List<OptionItemResponse> listAvailableSources(CodeReviewDataReadMode readMode) {
    return List.of(
        new OptionItemResponse("CC", "cc"),
        new OptionItemResponse("DGM", "dgm"));
  }

  public List<OptionItemResponse> listProjectOptions(String requestedSource) {
    return listProjectOptions(requestedSource, configuredReadMode());
  }

  public List<OptionItemResponse> listProjectOptions(
      String requestedSource, CodeReviewDataReadMode readMode) {
    String source = normalizeSource(requestedSource);
    QualityBoardCodeReviewReadScope scope = resolveAllProjectsScope(source, readMode);
    if (!scope.available()) {
      return List.of();
    }
    //兼容模式-MatchMode：候选与统计共用 read scope，CC/DGM 兼容仓库不会与正式事实或其他仓库混合。
    return orderProjectOptions(source, queryProjectOptions(scope));
  }

  public double defectDensity(String source, String projectName) {
    return defectDensity(source, projectName, configuredReadMode());
  }

  public double defectDensity(
      String source, String projectName, CodeReviewDataReadMode readMode) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName, readMode);
    if (!scope.available()) {
      return 0D;
    }
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
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
      boolean excludeIllegalReviewerRows,
      String source,
      String projectName) {
    return personDefectDensityRows(
        personField, excludeIllegalReviewerRows, source, projectName, configuredReadMode());
  }

  public List<QualityBoardChartRowResponse> personDefectDensityRows(
      String personField,
      boolean excludeIllegalAssigneeRows,
      String source,
      String projectName,
      CodeReviewDataReadMode readMode) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName, readMode);
    if (!scope.available()) {
      return List.of();
    }
    String safePersonField = switch (personField) {
      case "reviewer_names" -> "reviewer_names";
      case "assignee_names" -> "assignee_names";
      case "author_name" -> "author_name";
      default -> throw new IllegalArgumentException("Unsupported person field: " + personField);
    };
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
    // 老平台 getAuthorDefectDensity 只排除 assignee 中的非法走查记录；
    // 走查人图表本身按 assignee 聚合，不把 reviewer_names 当成非法记录过滤条件。
    String illegalRecordPredicate = excludeIllegalAssigneeRows
        ? " and coalesce(assignee_names, '') not in ('没有合法评论', '代码走查时间或缺陷数异常', '代码走查标题异常', '代码走查记录行数异常')"
        : "";
    String sql =
        """
        select
          person_name,
          round((sum(case when row_density > 0 then row_density else 0 end) / count(*))::numeric, 2) as value
        from (
          select
            nullif(btrim(%s), '') as person_name,
            case
              when review_defect_density_per_kloc is not null then review_defect_density_per_kloc
              when coalesce(defect_count, 0) > 0 and coalesce(added_lines, 0) > 0
                then defect_count * 1000.0 / added_lines
              else 0
            end as row_density
          from %s
          where %s
            %s
            %s
        ) scoped
        where person_name is not null
          and person_name <> '无需标注'
          and person_name <> '--'
          and person_name <> '无需走查'
          and person_name <> '无需走查扫描'
          and person_name <> '未标注人员'
          and person_name not like '%%#%%'
          and person_name not in ('没有合法评论', '代码走查时间或缺陷数异常', '代码走查标题异常', '代码走查记录行数异常')
        group by person_name
        order by value desc, person_name
        """
            .formatted(
                safePersonField,
                scope.tableName(),
            queryScope.predicate(),
            scope.deletedPredicate(),
                illegalRecordPredicate);
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new QualityBoardChartRowResponse(
                rs.getString("person_name"),
                numberValue(rs.getObject("value"))),
        queryScope.args().toArray());
  }

  public List<QualityBoardChartRowResponse> frequencyRows(String source, String projectName) {
    return frequencyRows(source, projectName, configuredReadMode());
  }

  public List<QualityBoardChartRowResponse> frequencyRows(
      String source, String projectName, CodeReviewDataReadMode readMode) {
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName, readMode);
    if (!scope.available()) {
      return List.of();
    }
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
    String sql =
        """
        select
          btrim(author_name) as person_name,
          count(*)::numeric as value
        from %s
        where %s
          %s
          and nullif(btrim(author_name), '') is not null
        group by person_name
        order by value desc, person_name
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
    return codeReviewRecordRows(source, projectName, configuredReadMode());
  }

  public List<QualityBoardCodeReviewRecordExportRow> codeReviewRecordRows(
      String source,
      String projectName,
      CodeReviewDataReadMode readMode) {
    //兼容模式-MatchMode：质量看板导出必须和统计共用 resolveScope，避免 DGM 兼容表影响正式 CC 读取。
    QualityBoardCodeReviewReadScope scope = resolveScope(source, projectName, readMode);
    if (!scope.available()) {
      return List.of();
    }
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
    String sql =
        """
        select
          source_instance,
          code_walkthrough_date,
          project_name,
          module_name,
          merge_request_state,
          merge_request_iid,
          title,
          author_name,
          reviewer_names,
          assignee_names,
          merged_at_source,
          merge_user_name,
          review_duration_minutes,
          added_lines,
          deleted_lines,
          code_specification_count,
          code_logic_specification_count,
          performance_specification_count,
          design_specification_count,
          other_specification_count,
          defect_count,
          review_speed_loc_per_hour,
          review_speed_kloc_per_hour,
          review_defect_density_per_kloc,
          review_efficiency_per_hour,
          target_branch,
          scan_status,
          commit_count,
          commit_rate,
          function_name,
          comment_rate,
          annotation_rate_result,
          scan_bug_count,
          bug_count_result,
          repository_name,
          clang_added_line_count
        from %s
        where %s
          %s
        order by merged_at_source desc nulls last, merge_request_iid desc
        """
            .formatted(scope.tableName(), queryScope.predicate(), scope.deletedPredicate());
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) ->
            new QualityBoardCodeReviewRecordExportRow(
                rs.getString("source_instance"),
                localDateTimeValue(rs.getObject("code_walkthrough_date")),
                rs.getString("project_name"),
                rs.getString("module_name"),
                rs.getString("merge_request_state"),
                longValue(rs.getObject("merge_request_iid")),
                rs.getString("title"),
                rs.getString("author_name"),
                rs.getString("reviewer_names"),
                rs.getString("assignee_names"),
                localDateTimeValue(rs.getObject("merged_at_source")),
                rs.getString("merge_user_name"),
                intValue(rs.getObject("review_duration_minutes")),
                intValue(rs.getObject("added_lines")),
                intValue(rs.getObject("deleted_lines")),
                intValue(rs.getObject("code_specification_count")),
                intValue(rs.getObject("code_logic_specification_count")),
                intValue(rs.getObject("performance_specification_count")),
                intValue(rs.getObject("design_specification_count")),
                intValue(rs.getObject("other_specification_count")),
                intValue(rs.getObject("defect_count")),
                intValue(rs.getObject("review_speed_loc_per_hour")),
                numberValue(rs.getObject("review_speed_kloc_per_hour")),
                numberValue(rs.getObject("review_defect_density_per_kloc")),
                numberValue(rs.getObject("review_efficiency_per_hour")),
                rs.getString("target_branch"),
                rs.getString("scan_status"),
                intValue(rs.getObject("commit_count")),
                intValue(rs.getObject("commit_rate")),
                rs.getString("function_name"),
                numberValue(rs.getObject("comment_rate")),
                rs.getString("annotation_rate_result"),
                intValue(rs.getObject("scan_bug_count")),
                rs.getString("bug_count_result"),
                rs.getString("repository_name"),
                intValue(rs.getObject("clang_added_line_count"))),
        queryScope.args().toArray());
  }

  public Map<String, Long> addedLinesByFunction(String projectName) {
    return groupedAddedLines("function_name", "cc", projectName);
  }

  public Map<String, Long> addedLinesByAuthor(String projectName) {
    return groupedAddedLines("author_name", "cc", projectName);
  }

  QualityBoardCodeReviewReadScope resolveScope(String requestedSource, String requestedProjectName) {
    return resolveScope(requestedSource, requestedProjectName, configuredReadMode());
  }

  QualityBoardCodeReviewReadScope resolveScope(
      String requestedSource,
      String requestedProjectName,
      CodeReviewDataReadMode readMode) {
    String source = normalizeSource(requestedSource);
    String projectName = TextQuerySupport.normalizeDisplay(requestedProjectName);
    if (readMode == CodeReviewDataReadMode.MATCH_MODE) {
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
          "",
          "lower(btrim(coalesce(repository_name, ''))) = ?",
          List.of(legacyRepositoryName(source)));
    }
    List<String> projectNames = "dgm".equals(source)
        ? distinctProjectNames(projectName, legacyDgmProjectName(projectName))
        : List.of(projectName);
    return new QualityBoardCodeReviewReadScope(
        true,
        "code_review_formal_records",
        source,
        projectNames,
        "project_id, merge_request_id",
        "",
        "",
        List.of());
  }

  QualityBoardCodeReviewReadScope resolveAllProjectsScope(
      String requestedSource, CodeReviewDataReadMode readMode) {
    String source = normalizeSource(requestedSource);
    if (readMode == CodeReviewDataReadMode.MATCH_MODE) {
      //兼容模式-MatchMode：明确的“全部”范围仅扫描当前 source 的兼容快照，不拼接正式事实表。
      return new QualityBoardCodeReviewReadScope(
          true,
          "code_review_match_mode_records",
          source,
          List.of(),
          "merge_request_iid",
          "",
          "lower(btrim(coalesce(repository_name, ''))) = ?",
          List.of(legacyRepositoryName(source)));
    }
    return new QualityBoardCodeReviewReadScope(
        true,
        "code_review_formal_records",
        source,
        List.of(),
        "project_id, merge_request_id",
        "",
        "",
        List.of());
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
    //兼容模式-MatchMode：其他看板的代码规模是正式 CC 业务口径，不能随兼容开关切到临时快照。
    QualityBoardCodeReviewReadScope scope =
        resolveScope(source, projectName, CodeReviewDataReadMode.FORMAL);
    if (!scope.available()) {
      return Map.of();
    }
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
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

  public CodeReviewDataReadMode configuredReadMode() {
    //兼容模式-MatchMode：旧接口在调用入口只读取一次设置；Analytics Provider 必须显式传入请求上下文中的固定模式。
    return matchModeSwitchService.isCodeReviewCompatibilityReadEnabled()
        ? CodeReviewDataReadMode.MATCH_MODE
        : CodeReviewDataReadMode.FORMAL;
  }

  QualityBoardCodeReviewQueryScope queryScope(QualityBoardCodeReviewReadScope scope) {
    List<Object> args = new ArrayList<>();
    args.add(scope.sourceInstance());
    String sourceColumn = "code_review_formal_records".equals(scope.tableName())
        ? "business_source"
        : "source_instance";
    StringBuilder predicate =
        new StringBuilder("lower(coalesce(" + sourceColumn + ", '')) = ?");
    if (scope.additionalPredicate() != null && !scope.additionalPredicate().isBlank()) {
      predicate.append(" and ").append(scope.additionalPredicate());
      args.addAll(scope.additionalArgs());
    }
    if (!scope.projectNames().isEmpty()) {
      args.addAll(scope.projectNames());
      String projectPlaceholders =
          String.join(",", scope.projectNames().stream().map(ignored -> "?").toList());
      predicate.append(" and coalesce(project_name, '') in (")
          .append(projectPlaceholders)
          .append(")");
    }
    return new QualityBoardCodeReviewQueryScope(predicate.toString(), List.copyOf(args));
  }

  private List<OptionItemResponse> orderProjectOptions(
      String source, List<OptionItemResponse> discoveredOptions) {
    String preferredProject = "dgm".equals(source) ? DEFAULT_DGM_PROJECT : DEFAULT_CC_PROJECT;
    OptionItemResponse defaultOption = discoveredOptions.stream()
        .filter(option -> option.value().equalsIgnoreCase(preferredProject))
        .findFirst()
        .orElseGet(() -> discoveredOptions.stream().findFirst().orElse(null));
    List<OptionItemResponse> ordered = new ArrayList<>();
    if (defaultOption != null) {
      ordered.add(defaultOption);
    }
    ordered.add(new OptionItemResponse(ALL_PROJECTS_OPTION, ALL_PROJECTS_OPTION));
    discoveredOptions.stream()
        .filter(option -> defaultOption == null
            || !option.value().equalsIgnoreCase(defaultOption.value()))
        .filter(option -> !ALL_PROJECTS_OPTION.equals(option.value()))
        .forEach(ordered::add);
    return List.copyOf(ordered);
  }

  private List<OptionItemResponse> queryProjectOptions(QualityBoardCodeReviewReadScope scope) {
    QualityBoardCodeReviewQueryScope queryScope = queryScope(scope);
    String sql =
        "select btrim(project_name) as project_name from "
            + scope.tableName()
            + " where "
            + queryScope.predicate()
            + scope.deletedPredicate()
            + " and upper(coalesce(merge_request_state, '')) = 'MERGED'"
            + " and nullif(btrim(coalesce(project_name, '')), '') is not null "
            + "and btrim(project_name) <> '未标注项目名' "
            + "and btrim(project_name) not like '未设定%' "
            + "group by btrim(project_name) order by lower(btrim(project_name))";
    List<String> projectNames = jdbcTemplate.query(
        sql,
        (rs, rowNum) -> rs.getString("project_name"),
        queryScope.args().toArray());
    Map<String, OptionItemResponse> options = new LinkedHashMap<>();
    for (String projectName : projectNames) {
      String normalized = TextQuerySupport.trimToNull(projectName);
      if (normalized != null
          && !normalized.startsWith("未设定")
          && !"未标注项目名".equals(normalized)) {
        options.putIfAbsent(normalized, new OptionItemResponse(normalized, normalized));
      }
    }
    return List.copyOf(options.values());
  }

  private String normalizeSource(String requestedSource) {
    return "dgm".equalsIgnoreCase(requestedSource == null ? "" : requestedSource.trim())
        ? "dgm"
        : "cc";
  }

  private String legacyDgmProjectName(String projectName) {
    return projectName.replaceFirst("^CC(\\d{4})(R\\d)$", "CrownCAD $1 $2");
  }

  private String legacyRepositoryName(String source) {
    return "dgm".equals(source) ? "dgm" : "crowncad";
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

  private java.time.LocalDateTime localDateTimeValue(Object value) {
    if (value instanceof java.time.LocalDateTime localDateTime) {
      return localDateTime;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    return null;
  }

}

record QualityBoardCodeReviewReadScope(
    boolean available,
    String tableName,
    String sourceInstance,
    List<String> projectNames,
    String mergeRequestIdentity,
    String deletedPredicate,
    String additionalPredicate,
    List<Object> additionalArgs) {
  QualityBoardCodeReviewReadScope {
    projectNames = projectNames == null ? List.of() : List.copyOf(projectNames);
    additionalArgs = additionalArgs == null ? List.of() : List.copyOf(additionalArgs);
  }
}

record QualityBoardCodeReviewQueryScope(String predicate, List<Object> args) {
  QualityBoardCodeReviewQueryScope {
    args = args == null ? List.of() : List.copyOf(args);
  }
}
