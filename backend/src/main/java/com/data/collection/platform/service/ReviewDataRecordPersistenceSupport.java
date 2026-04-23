package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordPersistenceSupport {
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

  public ReviewDataRecordPersistenceSupport(JdbcTemplate jdbcTemplate) {
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

  public Map<Long, List<String>> loadProblemStatusesByRecordIds(List<ReviewDataRecordRowResponse> records) {
    List<Long> recordIds = records.stream().map(ReviewDataRecordRowResponse::id).filter(Objects::nonNull).toList();
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
      return jdbcTemplate.queryForObject(
          BASE_LIST_SQL + " and r.id = ?",
          this::mapRecordRow,
          recordId);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalArgumentException("评审记录不存在: " + recordId);
    }
  }

  public List<String> listRecordExperts(Long recordId) {
    return jdbcTemplate.query(
        """
        select expert_name
        from review_record_experts
        where review_record_id = ? and deleted = false
        order by sort_order asc, id asc
        """,
        (rs, rowNum) -> rs.getString("expert_name"),
        recordId);
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    return jdbcTemplate.query(
        """
        select
          id,
          review_record_id,
          reviewer_name,
          workload_hours,
          review_category,
          document_position,
          problem_category,
          problem_description,
          suggested_solution,
          owner_name,
          rejection_reason,
          problem_status,
          updated_at
        from review_problem_items
        where review_record_id = ? and deleted = false
        order by updated_at desc, id desc
        """,
        this::mapProblemItem,
        recordId);
  }

  public ReviewDataProblemItemResponse getProblemItemOrThrow(Long recordId, Long itemId) {
    try {
      return jdbcTemplate.queryForObject(
          """
          select
            id,
            review_record_id,
            reviewer_name,
            workload_hours,
            review_category,
            document_position,
            problem_category,
            problem_description,
            suggested_solution,
            owner_name,
            rejection_reason,
            problem_status,
            updated_at
          from review_problem_items
          where id = ? and review_record_id = ? and deleted = false
          """,
          this::mapProblemItem,
          itemId,
          recordId);
    } catch (EmptyResultDataAccessException exception) {
      throw new IllegalArgumentException("评审问题不存在: " + itemId);
    }
  }

  public void assertRecordExists(Long recordId) {
    Integer exists =
        jdbcTemplate.queryForObject(
            "select count(*) from review_records where id = ? and deleted = false",
            Integer.class,
            recordId);
    if (exists == null || exists == 0) {
      throw new IllegalArgumentException("评审记录不存在: " + recordId);
    }
  }

  public void assertProblemItemExists(Long recordId, Long itemId) {
    Integer exists =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from review_problem_items
            where review_record_id = ? and id = ? and deleted = false
            """,
            Integer.class,
            recordId,
            itemId);
    if (exists == null || exists == 0) {
      throw new IllegalArgumentException("评审问题不存在: " + itemId);
    }
  }

  public void replaceExperts(Long recordId, List<String> experts) {
    jdbcTemplate.update("delete from review_record_experts where review_record_id = ?", recordId);
    List<String> normalized =
        experts == null
            ? List.of()
            : experts.stream()
                .map(TextQuerySupport::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    for (int index = 0; index < normalized.size(); index++) {
      jdbcTemplate.update(
          """
          insert into review_record_experts(
            review_record_id,
            expert_name,
            sort_order,
            deleted,
            created_at,
            updated_at
          ) values (?, ?, ?, false, current_timestamp, current_timestamp)
          """,
          recordId,
          normalized.get(index),
          index);
    }
  }

  public void touchRecord(Long recordId) {
    jdbcTemplate.update(
        "update review_records set updated_at = current_timestamp where id = ?",
        recordId);
  }

  public List<String> loadExpertOptions() {
    return jdbcTemplate.query(
        """
        select distinct expert_name
        from review_record_experts
        where deleted = false and coalesce(expert_name, '') <> ''
        order by expert_name asc
        """,
        (rs, rowNum) -> rs.getString("expert_name"));
  }

  public Long insertRecord(
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewOwner,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  insert into review_records(
                    project_name,
                    title,
                    module_name,
                    review_type,
                    review_date,
                    review_owner,
                    review_scale_pages,
                    review_product,
                    author_name,
                    review_version,
                    created_at,
                    updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                  """,
                  new String[] {"id"});
          statement.setString(1, normalizeText(projectName));
          statement.setString(2, normalizeText(title));
          statement.setString(3, normalizeText(moduleName));
          statement.setString(4, normalizeText(reviewType));
          statement.setDate(5, reviewDate == null ? null : Date.valueOf(reviewDate));
          statement.setString(6, normalizeText(reviewOwner));
          statement.setInt(7, safeInt(reviewScalePages));
          statement.setString(8, normalizeText(reviewProduct));
          statement.setString(9, normalizeText(authorName));
          statement.setString(10, normalizeText(reviewVersion));
          return statement;
        },
        keyHolder);
    return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
  }

  public void updateRecord(
      Long recordId,
      String projectName,
      String title,
      String moduleName,
      String reviewType,
      java.time.LocalDate reviewDate,
      String reviewOwner,
      Integer reviewScalePages,
      String reviewProduct,
      String authorName,
      String reviewVersion) {
    jdbcTemplate.update(
        """
        update review_records
        set
          project_name = ?,
          title = ?,
          module_name = ?,
          review_type = ?,
          review_date = ?,
          review_owner = ?,
          review_scale_pages = ?,
          review_product = ?,
          author_name = ?,
          review_version = ?,
          updated_at = current_timestamp
        where id = ?
        """,
        normalizeText(projectName),
        normalizeText(title),
        normalizeText(moduleName),
        normalizeText(reviewType),
        reviewDate == null ? null : Date.valueOf(reviewDate),
        normalizeText(reviewOwner),
        safeInt(reviewScalePages),
        normalizeText(reviewProduct),
        normalizeText(authorName),
        normalizeText(reviewVersion),
        recordId);
  }

  public void softDeleteRecord(Long recordId) {
    jdbcTemplate.update(
        "update review_records set deleted = true, updated_at = current_timestamp where id = ?",
        recordId);
  }

  public Long insertProblemItem(
      Long recordId,
      String reviewerName,
      Double workloadHours,
      String reviewCategory,
      String documentPosition,
      String problemCategory,
      String problemDescription,
      String suggestedSolution,
      String ownerName,
      String rejectionReason,
      String problemStatus) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  insert into review_problem_items(
                    review_record_id,
                    reviewer_name,
                    workload_hours,
                    review_category,
                    document_position,
                    problem_category,
                    problem_description,
                    suggested_solution,
                    owner_name,
                    rejection_reason,
                    problem_status,
                    created_at,
                    updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                  """,
                  new String[] {"id"});
          statement.setLong(1, recordId);
          statement.setString(2, normalizeText(reviewerName));
          statement.setBigDecimal(3, BigDecimal.valueOf(safeDouble(workloadHours)));
          statement.setString(4, normalizeText(reviewCategory));
          statement.setString(5, normalizeNullableText(documentPosition));
          statement.setString(6, normalizeText(problemCategory));
          statement.setString(7, normalizeText(problemDescription));
          statement.setString(8, normalizeNullableText(suggestedSolution));
          statement.setString(9, normalizeNullableText(ownerName));
          statement.setString(10, normalizeNullableText(rejectionReason));
          statement.setString(11, normalizeText(problemStatus));
          return statement;
        },
        keyHolder);
    return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
  }

  public void updateProblemItem(
      Long recordId,
      Long itemId,
      String reviewerName,
      Double workloadHours,
      String reviewCategory,
      String documentPosition,
      String problemCategory,
      String problemDescription,
      String suggestedSolution,
      String ownerName,
      String rejectionReason,
      String problemStatus) {
    jdbcTemplate.update(
        """
        update review_problem_items
        set
          reviewer_name = ?,
          workload_hours = ?,
          review_category = ?,
          document_position = ?,
          problem_category = ?,
          problem_description = ?,
          suggested_solution = ?,
          owner_name = ?,
          rejection_reason = ?,
          problem_status = ?,
          updated_at = current_timestamp
        where id = ? and review_record_id = ?
        """,
        normalizeText(reviewerName),
        BigDecimal.valueOf(safeDouble(workloadHours)),
        normalizeText(reviewCategory),
        normalizeNullableText(documentPosition),
        normalizeText(problemCategory),
        normalizeText(problemDescription),
        normalizeNullableText(suggestedSolution),
        normalizeNullableText(ownerName),
        normalizeNullableText(rejectionReason),
        normalizeText(problemStatus),
        itemId,
        recordId);
  }

  public void softDeleteProblemItem(Long recordId, Long itemId) {
    jdbcTemplate.update(
        """
        update review_problem_items
        set deleted = true, updated_at = current_timestamp
        where id = ? and review_record_id = ?
        """,
        itemId,
        recordId);
  }

  public String normalizeText(String value) {
    return Objects.requireNonNullElse(TextQuerySupport.trimToNull(value), "");
  }

  public String normalizeNullableText(String value) {
    return TextQuerySupport.trimToNull(value);
  }

  public int safeInt(Integer value) {
    return value == null ? 0 : Math.max(value, 0);
  }

  public double safeDouble(Double value) {
    return value == null ? 0D : Math.max(value, 0D);
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

  private ReviewDataProblemItemResponse mapProblemItem(ResultSet rs, int rowNum) throws SQLException {
    return new ReviewDataProblemItemResponse(
        rs.getLong("id"),
        rs.getLong("review_record_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("reviewer_name")),
        rs.getBigDecimal("workload_hours") == null
            ? 0D
            : rs.getBigDecimal("workload_hours").doubleValue(),
        TextQuerySupport.normalizeDisplay(rs.getString("review_category")),
        TextQuerySupport.normalizeDisplay(rs.getString("document_position")),
        TextQuerySupport.normalizeDisplay(rs.getString("problem_category")),
        TextQuerySupport.normalizeDisplay(rs.getString("problem_description")),
        TextQuerySupport.normalizeDisplay(rs.getString("suggested_solution")),
        TextQuerySupport.normalizeDisplay(rs.getString("owner_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("rejection_reason")),
        TextQuerySupport.normalizeDisplay(rs.getString("problem_status")),
        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
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
