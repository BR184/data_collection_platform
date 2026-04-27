package com.data.collection.platform.service;

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
          string_agg(expert_name, '銆? order by sort_order asc, id asc) as expert_names
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
      String reviewExpert) {
    StringBuilder sql = new StringBuilder(BASE_LIST_SQL);
    List<Object> args = new ArrayList<>();

    appendContains(sql, args, "r.title", title);
    appendContains(sql, args, "r.project_name", projectName);
    appendContains(sql, args, "r.module_name", moduleName);
    appendContains(sql, args, "r.review_owner", reviewOwner);
    appendEqText(sql, args, "r.review_type", reviewType);
    appendProblemStatusFilter(sql, args, problemStatus);
    appendReviewExpertFilter(sql, args, reviewExpert);

    return jdbcTemplate.query(sql.toString(), this::mapRecordRow, args.toArray());
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

  public ReviewDataRecordRowResponse getRecordOrThrow(Long recordId) {
    try {
      return jdbcTemplate.queryForObject(BASE_LIST_SQL + " and r.id = ?", this::mapRecordRow, recordId);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalArgumentException("璇勫璁板綍涓嶅瓨鍦? " + recordId);
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
}
