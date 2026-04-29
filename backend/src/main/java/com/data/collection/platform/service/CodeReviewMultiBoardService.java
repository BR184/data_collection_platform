package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewMultiBoardBreakdownRowResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CodeReviewMultiBoardService {
  private static final List<String> PREFERRED_SOURCE_ORDER = List.of("cc", "dgm");
  private final JdbcTemplate jdbcTemplate;

  public CodeReviewMultiBoardService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<OptionItemResponse> listSourceOptions() {
    List<String> sourceInstances =
        jdbcTemplate.queryForList(
            """
            select distinct source_instance
              from merge_request_fact
             where deleted = false
               and source_instance is not null
               and btrim(source_instance) <> ''
            order by source_instance
            """,
            String.class);
    List<String> normalized =
        sourceInstances.stream()
            .map(this::normalizeSourceValue)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    List<String> preferred =
        PREFERRED_SOURCE_ORDER.stream().filter(normalized::contains).toList();
    List<String> values = preferred.isEmpty() ? normalized : preferred;
    return values.stream().map(value -> new OptionItemResponse(sourceLabel(value), value)).toList();
  }

  public CodeReviewMultiBoardOverviewResponse getOverview(CodeReviewMultiBoardOverviewRequest request) {
    List<OptionItemResponse> options = listSourceOptions();
    String source = resolveSource(request.source(), options);
    if (!StringUtils.hasText(source)) {
      return new CodeReviewMultiBoardOverviewResponse(
          "", "", 0, 0, 0, null, 0, 0, null, null, null, List.of(), List.of());
    }

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
            from merge_request_fact
            where deleted = false
              and lower(source_instance) = ?
            """,
            source);

    List<CodeReviewMultiBoardBreakdownRowResponse> moduleRows =
        queryBreakdown("module_name", "未标注模块", source);
    List<CodeReviewMultiBoardBreakdownRowResponse> ownerRows =
        queryBreakdown("owner_name", "未标注责任人", source);

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
      String fieldName, String emptyLabel, String source) {
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
              from merge_request_fact
              where deleted = false
                and lower(source_instance) = ?
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
            limit 12
            """
                .formatted(fieldName),
            emptyLabel,
            source);
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

  private String normalizeSourceValue(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
  }

  private String sourceLabel(String value) {
    return switch (normalizeSourceValue(value)) {
      case "cc" -> "CC";
      case "dgm" -> "DGM";
      case "default" -> "默认";
      default -> value == null ? "" : value.toUpperCase(Locale.ROOT);
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
}
