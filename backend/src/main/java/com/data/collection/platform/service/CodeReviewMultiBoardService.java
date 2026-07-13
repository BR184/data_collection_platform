package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewMultiBoardBreakdownRowResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMultiBoardService {
  private final JdbcTemplate jdbcTemplate;
  private final QualityBoardCodeReviewReadSupport codeReviewReadSupport;

  public CodeReviewMultiBoardService(
      JdbcTemplate jdbcTemplate,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.jdbcTemplate = jdbcTemplate;
    this.codeReviewReadSupport = codeReviewReadSupport;
  }

  public List<OptionItemResponse> listSourceOptions() {
    return codeReviewReadSupport.listAvailableSources();
  }

  public List<OptionItemResponse> listProjectOptions(String source) {
    return codeReviewReadSupport.listProjectOptions(source);
  }

  public CodeReviewMultiBoardOverviewResponse getOverview(CodeReviewMultiBoardOverviewRequest request) {
    CodeReviewDataReadMode readMode = codeReviewReadSupport.configuredReadMode();
    List<OptionItemResponse> options = codeReviewReadSupport.listAvailableSources(readMode);
    String source = resolveSource(request.source(), options);
    if (!StringUtils.hasText(source)) {
      return new CodeReviewMultiBoardOverviewResponse(
          "", "", 0, 0, 0, null, 0, 0, null, null, null, List.of(), List.of());
    }
    //兼容模式-MatchMode：旧 overview 没有项目参数，继续保持当前读源全量语义；
    // 正式表与兼容表的选择只由统一读源支持层决定。
    QualityBoardCodeReviewReadScope readScope =
        codeReviewReadSupport.resolveAllProjectsScope(source, readMode);
    if (!readScope.available()) {
      return new CodeReviewMultiBoardOverviewResponse(
          source, sourceLabel(source), 0, 0, 0, null, 0, 0, null, null, null, List.of(), List.of());
    }
    LegacyScopeSql scopeSql = legacyScopeSql(readScope);

    Map<String, Object> summary =
        jdbcTemplate.queryForMap(
            """
            select
              count(*) as merge_request_count,
              coalesce(sum(case when review_status = 'COMPLETED' then 1 else 0 end), 0) as completed_count,
              coalesce(sum(case when review_status <> 'COMPLETED' or review_status is null then 1 else 0 end), 0) as pending_count,
              round(avg(comment_rate)::numeric, 2) as average_comment_rate,
              coalesce(sum(defect_count), 0) as total_defect_count,
              coalesce(sum(added_lines), 0) as total_added_lines,
              round(avg(review_duration_minutes)::numeric, 2) as average_review_duration_minutes,
              round(avg(added_lines)::numeric, 2) as average_added_lines
            from %s
            %s
            """.formatted(readScope.tableName(), scopeSql.whereClause()),
            scopeSql.args().toArray());

    List<CodeReviewMultiBoardBreakdownRowResponse> moduleRows =
        queryBreakdown("module_name", "未标注模块", readScope, scopeSql);
    List<CodeReviewMultiBoardBreakdownRowResponse> ownerRows =
        queryBreakdown("reviewer_names", "未标注走查人", readScope, scopeSql);

    return new CodeReviewMultiBoardOverviewResponse(
        source,
        sourceLabel(source),
        intValue(summary.get("merge_request_count")),
        intValue(summary.get("completed_count")),
        intValue(summary.get("pending_count")),
        doubleValue(summary.get("average_comment_rate")),
        intValue(summary.get("total_defect_count")),
        intValue(summary.get("total_added_lines")),
        densityValue(summary.get("total_defect_count"), summary.get("total_added_lines")),
        doubleValue(summary.get("average_review_duration_minutes")),
        doubleValue(summary.get("average_added_lines")),
        moduleRows,
        ownerRows);
  }

  private List<CodeReviewMultiBoardBreakdownRowResponse> queryBreakdown(
      String fieldName,
      String emptyLabel,
      QualityBoardCodeReviewReadScope readScope,
      LegacyScopeSql scopeSql) {
    List<Object> queryArgs = new ArrayList<>();
    queryArgs.add(emptyLabel);
    queryArgs.addAll(scopeSql.args());
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            with scoped as (
              select
                coalesce(nullif(btrim(%s), ''), ?) as row_label,
                review_status,
                comment_rate,
                defect_count,
                review_duration_minutes,
                added_lines
              from %s
              %s
            )
            select
              row_label,
              count(*) as merge_request_count,
              coalesce(sum(case when review_status = 'COMPLETED' then 1 else 0 end), 0) as completed_count,
              round(avg(comment_rate)::numeric, 2) as average_comment_rate,
              coalesce(sum(defect_count), 0) as total_defect_count,
              coalesce(sum(added_lines), 0) as total_added_lines,
              round(avg(review_duration_minutes)::numeric, 2) as average_review_duration_minutes,
              round(avg(added_lines)::numeric, 2) as average_added_lines
            from scoped
            group by row_label
            order by merge_request_count desc, row_label
            """
                .formatted(fieldName, readScope.tableName(), scopeSql.whereClause()),
            queryArgs.toArray());
    List<CodeReviewMultiBoardBreakdownRowResponse> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String label = String.valueOf(row.get("row_label"));
      result.add(
          new CodeReviewMultiBoardBreakdownRowResponse(
              label,
              label,
              intValue(row.get("merge_request_count")),
              intValue(row.get("completed_count")),
              doubleValue(row.get("average_comment_rate")),
              intValue(row.get("total_defect_count")),
              intValue(row.get("total_added_lines")),
              densityValue(row.get("total_defect_count"), row.get("total_added_lines")),
              doubleValue(row.get("average_review_duration_minutes")),
              doubleValue(row.get("average_added_lines"))));
    }
    return result;
  }

  private String resolveSource(String requestedSource, List<OptionItemResponse> options) {
    String normalized = normalizeSourceValue(requestedSource);
    if (StringUtils.hasText(normalized) && options.stream().anyMatch(option -> option.value().equals(normalized))) {
      return normalized;
    }
    return options.stream().map(OptionItemResponse::value).findFirst().orElse("");
  }

  private LegacyScopeSql legacyScopeSql(QualityBoardCodeReviewReadScope scope) {
    QualityBoardCodeReviewQueryScope queryScope = codeReviewReadSupport.queryScope(scope);
    List<Object> args = new ArrayList<>(queryScope.args());
    StringBuilder where = new StringBuilder("where ").append(queryScope.predicate()).append(" ");
    where.append("and upper(coalesce(merge_request_state, '')) = 'MERGED'")
        .append(scope.deletedPredicate());
    return new LegacyScopeSql(where.toString(), List.copyOf(args));
  }

  private String normalizeSourceValue(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(java.util.Locale.ROOT) : "";
  }

  private String sourceLabel(String value) {
    return switch (normalizeSourceValue(value)) {
      case "cc" -> "CC";
      case "dgm" -> "DGM";
      case "default" -> "默认";
      default -> value == null ? "" : value.toUpperCase(java.util.Locale.ROOT);
    };
  }

  private int intValue(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private Double doubleValue(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private Double densityValue(Object defectCountValue, Object addedLinesValue) {
    int defectCount = intValue(defectCountValue);
    int addedLines = intValue(addedLinesValue);
    if (defectCount <= 0 || addedLines <= 0) {
      return null;
    }
    return Math.round((defectCount * 1000D / addedLines) * 100D) / 100D;
  }

  private record LegacyScopeSql(String whereClause, List<Object> args) {}
}
