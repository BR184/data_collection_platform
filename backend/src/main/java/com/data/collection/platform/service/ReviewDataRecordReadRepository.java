package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataRecordReadRepository {
  private final JdbcTemplate jdbcTemplate;
  private final ReviewDataRecordRowMapper rowMapper;
  private final ReviewDataRecordQueryBuilder queryBuilder;

  public ReviewDataRecordReadRepository(
      JdbcTemplate jdbcTemplate,
      ReviewDataRecordRowMapper rowMapper,
      ReviewDataRecordQueryBuilder queryBuilder) {
    this.jdbcTemplate = jdbcTemplate;
    this.rowMapper = rowMapper;
    this.queryBuilder = queryBuilder;
  }

  public List<ReviewDataRecordRowResponse> loadRecords(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword) {
    ReviewDataRecordQueryBuilder.SqlParts query =
        queryBuilder.buildListQuery(
            title,
            projectName,
            moduleName,
            reviewOwner,
            reviewType,
            problemStatus,
            reviewExpert,
            keyword);
    return jdbcTemplate.query(query.sql(), rowMapper::mapRecordRow, query.args().toArray());
  }

  public List<ReviewDataRecordRowResponse> loadRecordsForFilterOptions() {
    return jdbcTemplate.query(queryBuilder.baseListSql(), rowMapper::mapRecordRow);
  }

  public RecordPageResult loadRecordPage(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword,
      StatisticFilterGroup filterGroup,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    ReviewDataRecordQueryBuilder.SqlParts from =
        queryBuilder.buildFilteredFromSql(
            title,
            projectName,
            moduleName,
            reviewOwner,
            reviewType,
            problemStatus,
            reviewExpert,
            keyword,
            filterGroup);
    String orderBy = queryBuilder.buildWindowOrderBy(sortField, sortOrder);
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    int offset = (safePage - 1) * safeSize;

    List<Object> args = new ArrayList<>(from.args());
    args.add(offset);
    args.add(offset + safeSize);
    return jdbcTemplate.query(
        """
        with filtered_records as (
          select
            r.id,
            r.project_name,
            r.title,
            r.module_name,
            r.review_type,
            r.review_date,
            r.review_owner,
            r.review_scale_pages,
            r.review_product,
            r.author_name,
            r.review_version,
            r.not_reach_standard_reason,
            r.source_file_name,
            """
            + ReviewDataMetricSqlExpressions.WEIGHTED_DEFECT_DENSITY
            + " as weighted_defect_density,\n            "
            + """
            r.gitlab_project_id,
            r.gitlab_resource_iid,
            r.gitlab_resource_type,
            r.created_at,
            r.updated_at,
            r.deleted,
            coalesce(problem.problem_count, 0) as problem_count,
            coalesce(problem.total_workload_hours, 0) as total_workload_hours,
            coalesce(problem.review_category_summary, '') as review_category_summary,
            coalesce(problem.doc_specification_count, 0) as doc_specification_count,
            coalesce(problem.integrity_count, 0) as integrity_count,
            coalesce(problem.functionality_count, 0) as functionality_count,
            coalesce(problem.feasibility_count, 0) as feasibility_count,
            coalesce(problem.independent_review_workload, 0) as independent_review_workload,
            coalesce(problem.independent_review_problem_count, 0) as independent_review_problem_count,
            coalesce(problem.meeting_review_workload, 0) as meeting_review_workload,
            coalesce(problem.meeting_review_problem_count, 0) as meeting_review_problem_count,
            """
            + ReviewDataMetricSqlExpressions.PROBLEM_DENSITY
            + " as problem_density,\n            "
            + ReviewDataMetricSqlExpressions.REVIEW_EFFICIENCY
            + " as review_efficiency,\n            "
            + ReviewDataMetricSqlExpressions.REVIEW_RATE
            + " as review_rate\n"
            + """
        """
            + from.sql()
            + """
        ),
        summary as (
          select
            count(*) as total_records,
            coalesce(sum(problem_count), 0) as total_problem_items,
            coalesce(avg(review_scale_pages), 0) as average_review_scale_pages,
            coalesce(avg(problem_count), 0) as average_problem_count
          from filtered_records
        ),
        numbered_records as (
          select fr.*, row_number() over (
        """
            + orderBy
            + """
          ) as page_row_number
          from filtered_records fr
        ),
        page_records as (
          select *
          from numbered_records
          where page_row_number > ? and page_row_number <= ?
        )
        select
          summary.total_records,
          summary.total_problem_items,
          summary.average_review_scale_pages,
          summary.average_problem_count,
          page_records.id,
          page_records.project_name,
          page_records.title,
          page_records.module_name,
          page_records.review_type,
          page_records.review_date,
          page_records.review_owner,
          page_records.review_scale_pages,
          page_records.review_product,
          page_records.author_name,
          page_records.review_version,
          page_records.not_reach_standard_reason,
          page_records.source_file_name,
          page_records.weighted_defect_density,
          page_records.gitlab_project_id,
          page_records.gitlab_resource_iid,
          page_records.gitlab_resource_type,
          page_records.created_at,
          page_records.updated_at,
          page_records.deleted,
          coalesce(expert.expert_names, '') as review_experts_summary,
          coalesce(page_records.problem_count, 0) as problem_count,
          coalesce(page_records.total_workload_hours, 0) as total_workload_hours,
          coalesce(page_records.review_category_summary, '') as review_category_summary,
          coalesce(page_records.doc_specification_count, 0) as doc_specification_count,
          coalesce(page_records.integrity_count, 0) as integrity_count,
          coalesce(page_records.functionality_count, 0) as functionality_count,
          coalesce(page_records.feasibility_count, 0) as feasibility_count,
          coalesce(page_records.independent_review_workload, 0) as independent_review_workload,
          coalesce(page_records.independent_review_problem_count, 0) as independent_review_problem_count,
          coalesce(page_records.meeting_review_workload, 0) as meeting_review_workload,
          coalesce(page_records.meeting_review_problem_count, 0) as meeting_review_problem_count,
          coalesce(page_records.review_efficiency, 0) as review_efficiency,
          coalesce(page_records.review_rate, 0) as review_rate,
          page_records.page_row_number
        from summary
        left join page_records on true
        left join lateral (
          select string_agg(expert_name, '、' order by sort_order asc, id asc) as expert_names
          from review_record_experts
          where review_record_id = page_records.id and deleted = false
        ) expert on page_records.id is not null
        order by page_records.page_row_number asc nulls last
        """,
        rs -> {
          List<ReviewDataRecordRowResponse> records = new ArrayList<>();
          ReviewDataSummaryResponse summary = new ReviewDataSummaryResponse(0, 0, 0D, 0D);
          long total = 0L;
          while (rs.next()) {
            total = rs.getLong("total_records");
            summary =
                new ReviewDataSummaryResponse(
                    total,
                    rs.getLong("total_problem_items"),
                    rs.getDouble("average_review_scale_pages"),
                    rs.getDouble("average_problem_count"));
            if (rs.getObject("id") != null) {
              records.add(rowMapper.mapRecordRow(rs, records.size()));
            }
          }
          return new RecordPageResult(records, total, summary);
        },
        args.toArray());
  }

  public Map<Long, List<String>> loadProblemStatusesByRecordIds(
      List<ReviewDataRecordRowResponse> records) {
    List<Long> recordIds =
        records.stream().map(ReviewDataRecordRowResponse::id).filter(Objects::nonNull).toList();
    if (recordIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = recordIds.stream().map(id -> "?").collect(Collectors.joining(","));
    return jdbcTemplate.query(
        """
        select review_record_id, problem_status
        from review_problem_items
        where deleted = false and review_record_id in (
        """ + placeholders + ")",
        rs -> {
          Map<Long, List<String>> result = new java.util.HashMap<>();
          while (rs.next()) {
            result.computeIfAbsent(rs.getLong("review_record_id"), ignored -> new ArrayList<>())
                .add(TextQuerySupport.normalizeDisplay(rs.getString("problem_status")));
          }
          return result;
        },
        recordIds.toArray());
  }

  public boolean hasMissingSearchIndexes() {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
              from review_records
              where deleted = false and search_text is null
              limit 1
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  public boolean hasMissingTitleSearchIndexes() {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
              from review_records
              where deleted = false and title_search_text is null
              limit 1
            )
            """,
            Boolean.class);
    return Boolean.TRUE.equals(exists);
  }

  public boolean existsDuplicateRecord(
      String projectName,
      String title,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewVersion) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
              from review_records
              where deleted = false
                and project_name = ?
                and title = ?
                and review_type = ?
                and review_date = ?
                and review_version = ?
              limit 1
            )
            """,
            Boolean.class,
            projectName,
            title,
            reviewType,
            reviewDate,
            reviewVersion);
    return Boolean.TRUE.equals(exists);
  }

  public ReviewDataRecordRowResponse getRecordOrThrow(Long recordId) {
    try {
      return jdbcTemplate.queryForObject(
          queryBuilder.baseListSql() + " and r.id = ?", rowMapper::mapRecordRow, recordId);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalArgumentException("评审记录不存在: " + recordId);
    }
  }

  public record RecordPageResult(
      List<ReviewDataRecordRowResponse> records,
      long total,
      ReviewDataSummaryResponse summary) {}

}
