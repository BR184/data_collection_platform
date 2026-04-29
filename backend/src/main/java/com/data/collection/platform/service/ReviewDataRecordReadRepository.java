package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataSummaryResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
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
      int page,
      int size,
      String sortField,
      String sortOrder) {
    SqlParts from = buildFilteredFromSql(
        title, projectName, moduleName, reviewOwner, reviewType, problemStatus, reviewExpert, keyword);
    String orderBy = buildOrderBy(sortField, sortOrder);
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    int offset = (safePage - 1) * safeSize;

    List<Object> pageArgs = new ArrayList<>(from.args());
    pageArgs.add(safeSize);
    pageArgs.add(offset);
    List<ReviewDataRecordRowResponse> records =
        jdbcTemplate.query(
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
              r.updated_at,
              r.deleted,
              coalesce(expert.expert_names, '') as review_experts_summary,
              coalesce(problem.problem_count, 0) as problem_count
            """
                + from.sql()
                + orderBy
                + " limit ? offset ?",
            this::mapRecordRow,
            pageArgs.toArray());

    Long total =
        jdbcTemplate.queryForObject(
            "select count(*) " + from.sql(), Long.class, from.args().toArray());
    ReviewDataSummaryResponse summary =
        jdbcTemplate.queryForObject(
            """
            select
              count(*) as total_records,
              coalesce(sum(coalesce(problem.problem_count, 0)), 0) as total_problem_items,
              coalesce(avg(r.review_scale_pages), 0) as average_review_scale_pages,
              coalesce(avg(coalesce(problem.problem_count, 0)), 0) as average_problem_count
            """
                + from.sql(),
            (rs, rowNum) ->
                new ReviewDataSummaryResponse(
                    rs.getLong("total_records"),
                    rs.getLong("total_problem_items"),
                    rs.getDouble("average_review_scale_pages"),
                    rs.getDouble("average_problem_count")),
            from.args().toArray());
    return new RecordPageResult(records, total == null ? 0 : total, summary);
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
        rs.getBoolean("deleted"));
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
      String keyword) {
    StringBuilder sql =
        new StringBuilder(
            """
             from review_records r
             left join lateral (
               select string_agg(expert_name, '、' order by sort_order asc, id asc) as expert_names
               from review_record_experts
               where review_record_id = r.id and deleted = false
             ) expert on true
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
    return new SqlParts(sql.toString(), args);
  }

  private String buildOrderBy(String sortField, String sortOrder) {
    String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
    String expression =
        switch (sortField) {
          case "title" -> "r.title";
          case "projectName" -> "r.project_name";
          case "moduleName" -> "r.module_name";
          case "reviewType" -> "r.review_type";
          case "reviewDate" -> "r.review_date";
          case "reviewOwner" -> "r.review_owner";
          case "reviewScalePages" -> "r.review_scale_pages";
          case "problemCount" -> "coalesce(problem.problem_count, 0)";
          case "problemDensity" ->
              "case when r.review_scale_pages <= 0 then 0 else coalesce(problem.problem_count, 0)::numeric / r.review_scale_pages end";
          default -> "r.updated_at";
        };
    return " order by " + expression + " " + direction + " nulls last, r.id asc";
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

  public record RecordPageResult(
      List<ReviewDataRecordRowResponse> records,
      long total,
      ReviewDataSummaryResponse summary) {}

  private record SqlParts(String sql, List<Object> args) {}
}
