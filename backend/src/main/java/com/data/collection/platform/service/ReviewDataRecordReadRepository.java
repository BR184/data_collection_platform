package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataRecordReadRepository {
  private static final String BASE_LIST_SQL =
      """
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
        r.gitlab_project_id,
        r.gitlab_resource_iid,
        r.gitlab_resource_type,
        r.updated_at,
        r.deleted,
        coalesce(expert.expert_names, '') as review_experts_summary,
        coalesce(problem.problem_count, 0) as problem_count
      from review_records r
      left join (
        select
          review_record_id,
          string_agg(expert_name, '、' order by sort_order asc, id asc) as expert_names
        from review_record_experts
        where deleted = false
        group by review_record_id
      ) expert on expert.review_record_id = r.id
      left join (
        select
          review_record_id,
          count(*)::integer as problem_count
        from review_problem_items
        where deleted = false
        group by review_record_id
      ) problem on problem.review_record_id = r.id
      where r.deleted = false
      """;

  private final JdbcTemplate jdbcTemplate;

  public ReviewDataRecordReadRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
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
    StringBuilder sql = new StringBuilder(BASE_LIST_SQL);
    List<Object> args = new ArrayList<>();

    appendContains(sql, args, "r.title", title);
    appendContains(sql, args, "r.project_name", projectName);
    appendContains(sql, args, "r.module_name", moduleName);
    appendContains(sql, args, "r.review_owner", reviewOwner);
    appendEqText(sql, args, "r.review_type", reviewType);
    appendProblemStatusFilter(sql, args, problemStatus);
    appendReviewExpertFilter(sql, args, reviewExpert);
    appendKeywordSearch(sql, args, keyword);

    return jdbcTemplate.query(sql.toString(), this::mapRecordRow, args.toArray());
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
    SqlParts from = buildFilteredFromSql(
        title, projectName, moduleName, reviewOwner, reviewType, problemStatus, reviewExpert, keyword, filterGroup);
    String orderBy = buildWindowOrderBy(sortField, sortOrder);
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
            r.gitlab_project_id,
            r.gitlab_resource_iid,
            r.gitlab_resource_type,
            r.updated_at,
            r.deleted,
            coalesce(problem.problem_count, 0) as problem_count,
            case when r.review_scale_pages <= 0 then 0 else coalesce(problem.problem_count, 0)::numeric / r.review_scale_pages end as problem_density
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
          page_records.gitlab_project_id,
          page_records.gitlab_resource_iid,
          page_records.gitlab_resource_type,
          page_records.updated_at,
          page_records.deleted,
          coalesce(expert.expert_names, '') as review_experts_summary,
          coalesce(page_records.problem_count, 0) as problem_count,
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
              records.add(mapRecordRow(rs, records.size()));
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
      return jdbcTemplate.queryForObject(BASE_LIST_SQL + " and r.id = ?", this::mapRecordRow, recordId);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalArgumentException("评审记录不存在: " + recordId);
    }
  }

  private ReviewDataRecordRowResponse mapRecordRow(ResultSet rs, int rowNum) throws SQLException {
    Integer reviewScalePages = (Integer) rs.getObject("review_scale_pages");
    Integer problemCount = (Integer) rs.getObject("problem_count");
    return new ReviewDataRecordRowResponse(
        rs.getLong("id"),
        TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("title")),
        TextQuerySupport.normalizeDisplay(rs.getString("module_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("review_type")),
        rs.getDate("review_date") == null ? null : rs.getDate("review_date").toLocalDate(),
        TextQuerySupport.normalizeDisplay(rs.getString("review_owner")),
        TextQuerySupport.normalizeDisplay(rs.getString("review_experts_summary")),
        reviewScalePages,
        TextQuerySupport.normalizeDisplay(rs.getString("review_product")),
        TextQuerySupport.normalizeDisplay(rs.getString("author_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("review_version")),
        problemCount == null ? 0 : problemCount,
        calculateProblemDensity(problemCount, reviewScalePages),
        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime(),
        rs.getBoolean("deleted"),
        (Long) rs.getObject("gitlab_project_id"),
        (Long) rs.getObject("gitlab_resource_iid"),
        TextQuerySupport.trimToNull(rs.getString("gitlab_resource_type")));
  }

  private Double calculateProblemDensity(Integer problemCount, Integer reviewScalePages) {
    if (problemCount == null || reviewScalePages == null || reviewScalePages <= 0) {
      return 0D;
    }
    return problemCount.doubleValue() / reviewScalePages.doubleValue();
  }

  private SqlParts buildFilteredFromSql(
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String keyword,
      StatisticFilterGroup filterGroup) {
    StringBuilder sql =
        new StringBuilder(
            """
             from review_records r
             left join lateral (
               select count(*)::integer as problem_count
               from review_problem_items
               where review_record_id = r.id and deleted = false
             ) problem on true
             where r.deleted = false
            """);
    List<Object> args = new ArrayList<>();
    appendContains(sql, args, "r.title", title);
    appendContains(sql, args, "r.project_name", projectName);
    appendContains(sql, args, "r.module_name", moduleName);
    appendContains(sql, args, "r.review_owner", reviewOwner);
    appendEqText(sql, args, "r.review_type", reviewType);
    appendProblemStatusFilter(sql, args, problemStatus);
    appendReviewExpertFilter(sql, args, reviewExpert);
    appendKeywordSearch(sql, args, keyword);
    appendFilterGroup(sql, args, filterGroup);
    return new SqlParts(sql.toString(), args);
  }

  private String buildWindowOrderBy(String sortField, String sortOrder) {
    String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
    String expression =
        switch (sortField) {
          case "title" -> "fr.title";
          case "projectName" -> "fr.project_name";
          case "moduleName" -> "fr.module_name";
          case "reviewType" -> "fr.review_type";
          case "reviewDate" -> "fr.review_date";
          case "reviewOwner" -> "fr.review_owner";
          case "reviewScalePages" -> "fr.review_scale_pages";
          case "problemCount" -> "fr.problem_count";
          case "problemDensity" -> "fr.problem_density";
          default -> "fr.updated_at";
        };
    return " order by " + expression + " " + direction + " nulls last, fr.id asc";
  }

  private void appendContains(StringBuilder sql, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    sql.append(" and lower(coalesce(").append(column).append(", '')) like ?");
    args.add("%" + normalized.toLowerCase(Locale.ROOT) + "%");
  }

  private void appendEqText(StringBuilder sql, List<Object> args, String column, String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return;
    }
    sql.append(" and ").append(column).append(" = ?");
    args.add(normalized);
  }

  private void appendProblemStatusFilter(StringBuilder sql, List<Object> args, String problemStatus) {
    String normalized = TextQuerySupport.trimToNull(problemStatus);
    if (normalized == null) {
      return;
    }
    sql.append(
        """
         and exists (
          select 1
          from review_problem_items problem_filter
          where problem_filter.review_record_id = r.id
            and problem_filter.deleted = false
            and problem_filter.problem_status = ?
        )
        """);
    args.add(normalized);
  }

  private void appendReviewExpertFilter(StringBuilder sql, List<Object> args, String reviewExpert) {
    String normalized = TextQuerySupport.trimToNull(reviewExpert);
    if (normalized == null) {
      return;
    }
    sql.append(
        """
         and exists (
          select 1
          from review_record_experts expert_filter
          where expert_filter.review_record_id = r.id
            and expert_filter.deleted = false
            and expert_filter.expert_name = ?
        )
        """);
    args.add(normalized);
  }

  private void appendKeywordSearch(StringBuilder sql, List<Object> args, String keyword) {
    List<String> candidates = ReviewDataSearchIndexSupport.keywordCandidates(keyword);
    if (candidates.isEmpty()) {
      return;
    }
    List<String> predicates = new ArrayList<>();
    for (String ignored : candidates) {
      predicates.add("r.search_text like ?");
      predicates.add("r.search_compact like ?");
      predicates.add("r.search_spell like ?");
      predicates.add("r.search_initials like ?");
    }
    sql.append(" and (").append(String.join(" or ", predicates)).append(")");
    for (String candidate : candidates) {
      String pattern = "%" + candidate + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
  }

  private void appendFilterGroup(StringBuilder sql, List<Object> args, StatisticFilterGroup filterGroup) {
    ReviewDataFilterGroupSqlSupport.toSql(filterGroup)
        .filter(filter -> TextQuerySupport.trimToNull(filter.predicate()) != null)
        .ifPresent(
            filter -> {
              sql.append(" and (").append(filter.predicate()).append(")");
              args.addAll(filter.args());
            });
  }

  public record RecordPageResult(
      List<ReviewDataRecordRowResponse> records,
      long total,
      ReviewDataSummaryResponse summary) {}

  private record SqlParts(String sql, List<Object> args) {}
}
