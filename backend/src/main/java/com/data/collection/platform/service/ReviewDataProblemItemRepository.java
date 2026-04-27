package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataProblemItemRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataProblemItemRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public java.util.List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
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
      throw new IllegalArgumentException("璇勫闂涓嶅瓨鍦? " + itemId);
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
      throw new IllegalArgumentException("璇勫闂涓嶅瓨鍦? " + itemId);
    }
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

  private String normalizeText(String value) {
    return Objects.requireNonNullElse(TextQuerySupport.trimToNull(value), "");
  }

  private String normalizeNullableText(String value) {
    return TextQuerySupport.trimToNull(value);
  }

  private double safeDouble(Double value) {
    return value == null ? 0D : Math.max(value, 0D);
  }
}
