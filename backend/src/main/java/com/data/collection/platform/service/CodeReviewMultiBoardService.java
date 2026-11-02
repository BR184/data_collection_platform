package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewDurationPartitionResponse;
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


  private final com.data.collection.platform.entity.read.DatasetReadSelection fixedRead;

  /** 完整页面组合下的只读计算对象，保留原公式与排序。 */
  public CodeReviewMultiBoardService forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new CodeReviewMultiBoardService(this, selection);
  }

  private CodeReviewMultiBoardService(CodeReviewMultiBoardService factory, com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    this.jdbcTemplate = factory.jdbcTemplate;
    this.codeReviewReadSupport = factory.codeReviewReadSupport.forRead(selection);
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CodeReviewMultiBoardService(
      JdbcTemplate jdbcTemplate,
      QualityBoardCodeReviewReadSupport codeReviewReadSupport) {
    this.fixedRead = null;
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
          "", "", 0, 0, 0, null, 0, 0, null, null, null, List.of(), List.of(), List.of());
    }
    //兼容模式-MatchMode：旧 overview 没有项目参数，继续保持当前读源全量语义；
    // 正式表与兼容表的选择只由统一读源支持层决定。
    QualityBoardCodeReviewReadScope readScope =
        codeReviewReadSupport.resolveAllProjectsScope(source, readMode);
    if (!readScope.available()) {
      return new CodeReviewMultiBoardOverviewResponse(
          source, sourceLabel(source), 0, 0, 0, null, 0, 0, null, null, null, List.of(), List.of(),
          List.of());
    }
    LegacyScopeSql scopeSql = legacyScopeSql(readScope);

    boolean formEnabled = formRecordsEnabled(readScope);
    Map<String, Object> summary =
        formEnabled
            ? queryForMap(
                """
                with base as (
                  select b0.*, coalesce(a.form_authority, 'COMMENT') as form_authority
                    from %s b0
                    left join merge_request_fact_authority a
                      on a.merge_request_revision_id = b0.id
                  %s
                )
                select
                  (select count(*) from base) as merge_request_count,
                  (select count(*) from base where review_status = 'COMPLETED') as completed_count,
                  (select count(*) from base
                    where review_status <> 'COMPLETED' or review_status is null) as pending_count,
                  (select round(avg(comment_rate)::numeric, 2) from base) as average_comment_rate,
                  (select coalesce(sum(defect_count), 0) from base) as total_defect_count,
                  (select coalesce(sum(added_lines), 0) from base) as total_added_lines,
                  (select round(avg(added_lines)::numeric, 2) from base) as average_added_lines,
                  (select round(avg(r.review_duration_minutes)::numeric, 2)
                     from base b join form_review_fact r on r.merge_request_revision_id = b.id
                    where b.form_authority = 'FORM'
                      and r.review_duration_minutes is not null) as form_average_duration,
                  (select count(*)
                     from base b join form_review_fact r on r.merge_request_revision_id = b.id
                    where b.form_authority = 'FORM'
                      and r.review_duration_minutes is not null) as form_record_count,
                  (select count(distinct b.merge_request_id)
                     from base b
                    where b.form_authority = 'FORM') as form_merge_request_count,
                  (select round(avg(b.review_duration_minutes)::numeric, 2)
                     from base b
                    where b.form_authority = 'COMMENT'
                      and b.review_duration_minutes is not null) as comment_average_duration,
                  (select count(*)
                     from base b
                    where b.form_authority = 'COMMENT'
                      and b.review_duration_minutes is not null) as comment_record_count,
                  (select count(*)
                     from base b
                    where b.form_authority = 'COMMENT') as comment_merge_request_count
                """.formatted(readScope.tableName(), scopeSql.whereClause()),
                scopeSql.args().toArray())
            : queryForMap(
                """
                select
                  count(*) as merge_request_count,
                  coalesce(sum(case when review_status = 'COMPLETED' then 1 else 0 end), 0) as completed_count,
                  coalesce(sum(case when review_status <> 'COMPLETED' or review_status is null then 1 else 0 end), 0) as pending_count,
                  round(avg(comment_rate)::numeric, 2) as average_comment_rate,
                  coalesce(sum(defect_count), 0) as total_defect_count,
                  coalesce(sum(added_lines), 0) as total_added_lines,
                  round(avg(review_duration_minutes)::numeric, 2) as average_review_duration_minutes,
                  round(avg(added_lines)::numeric, 2) as average_added_lines,
                  count(review_duration_minutes) as comment_record_count
                from %s
                %s
                """.formatted(readScope.tableName(), scopeSql.whereClause()),
                scopeSql.args().toArray());

    List<CodeReviewMultiBoardBreakdownRowResponse> moduleRows =
        queryBreakdown("module_name", "未标注模块", readScope, scopeSql);
    List<CodeReviewMultiBoardBreakdownRowResponse> ownerRows =
        queryBreakdown("reviewer_names", "未标注走查人", readScope, scopeSql);

    List<CodeReviewDurationPartitionResponse> partitions =
        durationPartitions(summary, formEnabled);
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
        overallDuration(partitions),
        doubleValue(summary.get("average_added_lines")),
        moduleRows,
        ownerRows,
        partitions);
  }

  private List<CodeReviewMultiBoardBreakdownRowResponse> queryBreakdown(
      String fieldName,
      String emptyLabel,
      QualityBoardCodeReviewReadScope readScope,
      LegacyScopeSql scopeSql) {
    if (formRecordsEnabled(readScope)) {
      return queryFormBreakdown(fieldName, emptyLabel, readScope, scopeSql);
    }
    List<Object> queryArgs = new ArrayList<>();
    queryArgs.add(emptyLabel);
    queryArgs.addAll(scopeSql.args());
    List<Map<String, Object>> rows =
        queryForList(
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
              round(avg(added_lines)::numeric, 2) as average_added_lines,
              count(review_duration_minutes) as comment_record_count
            from scoped
            group by row_label
            order by merge_request_count desc, row_label
            """
                .formatted(fieldName, readScope.tableName(), scopeSql.whereClause()),
            queryArgs.toArray());
    List<CodeReviewMultiBoardBreakdownRowResponse> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String label = String.valueOf(row.get("row_label"));
      List<CodeReviewDurationPartitionResponse> partitions =
          List.of(
              new CodeReviewDurationPartitionResponse(
                  "COMMENT",
                  intValue(row.get("merge_request_count")),
                  intValue(row.get("comment_record_count")),
                  doubleValue(row.get("average_review_duration_minutes"))));
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
              overallDuration(partitions),
              doubleValue(row.get("average_added_lines")),
              partitions));
    }
    return result;
  }

  /** FORM 权威 MR 以逐记录投影参与均时与人员分桶；未登记 FORM 的 MR 保持每 MR 一行。 */
  private boolean formRecordsEnabled(QualityBoardCodeReviewReadScope readScope) {
    return "code_review_formal_records".equals(readScope.tableName());
  }

  private List<CodeReviewMultiBoardBreakdownRowResponse> queryFormBreakdown(
      String fieldName,
      String emptyLabel,
      QualityBoardCodeReviewReadScope readScope,
      LegacyScopeSql scopeSql) {
    boolean module = "module_name".equals(fieldName);
    String recordLabel =
        module
            ? "coalesce(nullif(btrim(b.module_name), ''), ?)"
            : "coalesce(nullif(btrim(r.reviewer_name), ''), nullif(btrim(b.reviewer_names), ''), ?)";
    String nonFormLabel =
        module
            ? "coalesce(nullif(btrim(b.module_name), ''), ?)"
            : "coalesce(nullif(btrim(b.reviewer_names), ''), ?)";
    List<Object> args = new ArrayList<>(scopeSql.args());
    args.add(emptyLabel);
    args.add(emptyLabel);
    args.add(emptyLabel);
    List<Map<String, Object>> rows =
        queryForList(
            """
            with base as (
              select b0.*, coalesce(a.form_authority, 'COMMENT') as form_authority
                from %s b0
                left join merge_request_fact_authority a
                  on a.merge_request_revision_id = b0.id
              %s
            ), record_rows as (
              select 'FORM' as authority, %s as row_label,
                     b.review_status, b.comment_rate, b.defect_count, b.added_lines,
                     b.project_id, b.merge_request_id,
                     r.review_duration_minutes as duration
                from base b join form_review_fact r on r.merge_request_revision_id = b.id
               where b.form_authority = 'FORM'
              union all
              select 'COMMENT' as authority, %s as row_label,
                     b.review_status, b.comment_rate, b.defect_count, b.added_lines,
                     b.project_id, b.merge_request_id,
                     b.review_duration_minutes as duration
                from base b
               where b.form_authority = 'COMMENT'
              union all
              select 'FORM' as authority, %s as row_label,
                     b.review_status, b.comment_rate, b.defect_count, b.added_lines,
                     b.project_id, b.merge_request_id,
                     null::integer as duration
                from base b
               where b.form_authority = 'FORM'
                 and not exists (
                   select 1 from form_review_fact r where r.merge_request_revision_id = b.id)
            ), mr_rows as (
              select distinct row_label, project_id, merge_request_id, review_status,
                     comment_rate, defect_count, added_lines
                from record_rows
            ), mr_metrics as (
              select row_label,
                     count(*) as merge_request_count,
                     count(*) filter (where review_status = 'COMPLETED') as completed_count,
                     round(avg(comment_rate)::numeric, 2) as average_comment_rate,
                     coalesce(sum(defect_count), 0) as total_defect_count,
                     coalesce(sum(added_lines), 0) as total_added_lines,
                     round(avg(added_lines)::numeric, 2) as average_added_lines
                from mr_rows
               group by row_label
            ), record_metrics as (
              select row_label,
                     round(avg(duration) filter (where authority = 'FORM')::numeric, 2)
                       as form_average_duration,
                     count(*) filter (where authority = 'FORM' and duration is not null)
                       as form_record_count,
                     count(distinct (project_id, merge_request_id))
                       filter (where authority = 'FORM') as form_merge_request_count,
                     round(avg(duration) filter (where authority = 'COMMENT')::numeric, 2)
                       as comment_average_duration,
                     count(*) filter (where authority = 'COMMENT' and duration is not null)
                       as comment_record_count,
                     count(distinct (project_id, merge_request_id))
                       filter (where authority = 'COMMENT') as comment_merge_request_count
                from record_rows group by row_label
            )
            select m.row_label,
                   m.merge_request_count,
                   m.completed_count,
                   m.average_comment_rate,
                   m.total_defect_count,
                   m.total_added_lines,
                   m.average_added_lines,
                   r.form_average_duration,
                   r.form_record_count,
                   r.form_merge_request_count,
                   r.comment_average_duration,
                   r.comment_record_count,
                   r.comment_merge_request_count
              from mr_metrics m
              left join record_metrics r on r.row_label = m.row_label
             order by m.merge_request_count desc, m.row_label
            """
                .formatted(
                    readScope.tableName(),
                    scopeSql.whereClause(),
                    recordLabel,
                    nonFormLabel,
                    nonFormLabel),
            args.toArray());
    List<CodeReviewMultiBoardBreakdownRowResponse> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String label = String.valueOf(row.get("row_label"));
      List<CodeReviewDurationPartitionResponse> partitions = new ArrayList<>();
      if (intValue(row.get("form_merge_request_count")) > 0) {
        partitions.add(
            new CodeReviewDurationPartitionResponse(
                "FORM",
                intValue(row.get("form_merge_request_count")),
                intValue(row.get("form_record_count")),
                doubleValue(row.get("form_average_duration"))));
      }
      if (intValue(row.get("comment_merge_request_count")) > 0) {
        partitions.add(
            new CodeReviewDurationPartitionResponse(
                "COMMENT",
                intValue(row.get("comment_merge_request_count")),
                intValue(row.get("comment_record_count")),
                doubleValue(row.get("comment_average_duration"))));
      }
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
              overallDuration(partitions),
              doubleValue(row.get("average_added_lines")),
              List.copyOf(partitions)));
    }
    return result;
  }

  /**
   * 按持久权威拆分均时：FORM 按有效记录、COMMENT 每合并请求一行。
   *
   * <p>分区只包含实际有有效时长的口径；混合范围的整体均时由 {@link #overallDuration(List)}
   * 显式置空，禁止把两种口径拼成一个平均值。
   */
  private List<CodeReviewDurationPartitionResponse> durationPartitions(
      Map<String, Object> summary, boolean formEnabled) {
    List<CodeReviewDurationPartitionResponse> partitions = new ArrayList<>();
    if (formEnabled) {
      if (intValue(summary.get("form_merge_request_count")) > 0) {
        partitions.add(
            new CodeReviewDurationPartitionResponse(
                "FORM",
                intValue(summary.get("form_merge_request_count")),
                intValue(summary.get("form_record_count")),
                doubleValue(summary.get("form_average_duration"))));
      }
      if (intValue(summary.get("comment_merge_request_count")) > 0) {
        partitions.add(
            new CodeReviewDurationPartitionResponse(
                "COMMENT",
                intValue(summary.get("comment_merge_request_count")),
                intValue(summary.get("comment_record_count")),
                doubleValue(summary.get("comment_average_duration"))));
      }
      return List.copyOf(partitions);
    }
    int commentRecords = intValue(summary.get("comment_record_count"));
    if (commentRecords > 0) {
      partitions.add(
          new CodeReviewDurationPartitionResponse(
              "COMMENT",
              intValue(summary.get("merge_request_count")),
              commentRecords,
              doubleValue(summary.get("average_review_duration_minutes"))));
    }
    return List.copyOf(partitions);
  }

  /** 单一分区沿用既有均时字段；混合或空范围返回空值，不合成跨口径平均值。 */
  private Double overallDuration(List<CodeReviewDurationPartitionResponse> partitions) {
    return partitions.size() == 1 ? partitions.getFirst().averageReviewDurationMinutes() : null;
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

  private <T> java.util.List<T> queryForList(String sql, Class<T> type, Object... args) {
    return fixedRead == null ? jdbcTemplate.queryForList(sql, type, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).queryForList(fixedRead, sql, type, args);
  }
  private java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
    return fixedRead == null ? jdbcTemplate.queryForList(sql, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).queryForList(fixedRead, sql, args);
  }
  private java.util.Map<String, Object> queryForMap(String sql, Object... args) {
    return fixedRead == null ? jdbcTemplate.queryForMap(sql, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).queryForMap(fixedRead, sql, args);
  }
}
