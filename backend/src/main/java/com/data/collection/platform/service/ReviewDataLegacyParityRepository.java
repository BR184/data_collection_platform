package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataContentResponse;
import com.data.collection.platform.entity.ReviewDataContentSaveRequest;
import com.data.collection.platform.entity.ReviewDataDescriptionResponse;
import com.data.collection.platform.entity.ReviewDataDescriptionSaveRequest;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataLegacyParityRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataLegacyParityRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<ReviewDataDescriptionResponse> listDescriptions(Long recordId) {
    return jdbcTemplate.query(
        """
        select id, review_record_id, review_product, review_version, author_name,
               review_scale_pages, unit, sort_order, updated_at
        from review_record_descriptions
        where review_record_id = ? and deleted = false
        order by sort_order asc, id asc
        """,
        this::mapDescription,
        recordId);
  }

  public List<ReviewDataContentResponse> listContents(Long recordId) {
    return jdbcTemplate.query(
        """
        select id, review_record_id, reviewer_name, assignment_content,
               independent_workload_hours, independent_problem_count,
               meeting_workload_hours, meeting_problem_count, sort_order, updated_at
        from review_record_contents
        where review_record_id = ? and deleted = false
        order by sort_order asc, id asc
        """,
        this::mapContent,
        recordId);
  }

  public Long insertDescription(Long recordId, ReviewDataDescriptionSaveRequest request) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  insert into review_record_descriptions(
                    review_record_id, review_product, review_version, author_name,
                    review_scale_pages, unit, sort_order, created_at, updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                  """,
                  new String[] {"id"});
          statement.setLong(1, recordId);
          statement.setString(2, normalizeText(request.reviewProduct()));
          statement.setString(3, normalizeText(request.reviewVersion()));
          statement.setString(4, normalizeText(request.authorName()));
          statement.setInt(5, ReviewDataNumberSupport.safeInt(request.reviewScalePages()));
          statement.setString(6, normalizeUnit(request.unit()));
          statement.setInt(7, safeSortOrder(request.sortOrder()));
          return statement;
        },
        keyHolder);
    return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
  }

  public Long insertContent(Long recordId, ReviewDataContentSaveRequest request) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  insert into review_record_contents(
                    review_record_id, reviewer_name, assignment_content,
                    independent_workload_hours, independent_problem_count,
                    meeting_workload_hours, meeting_problem_count,
                    sort_order, created_at, updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                  """,
                  new String[] {"id"});
          statement.setLong(1, recordId);
          statement.setString(2, normalizeText(request.reviewerName()));
          statement.setString(3, normalizeNullableText(request.assignmentContent()));
          statement.setBigDecimal(4, BigDecimal.valueOf(ReviewDataNumberSupport.safeDouble(request.independentWorkloadHours())));
          statement.setInt(5, ReviewDataNumberSupport.safeInt(request.independentProblemCount()));
          statement.setBigDecimal(6, BigDecimal.valueOf(ReviewDataNumberSupport.safeDouble(request.meetingWorkloadHours())));
          statement.setInt(7, ReviewDataNumberSupport.safeInt(request.meetingProblemCount()));
          statement.setInt(8, safeSortOrder(request.sortOrder()));
          return statement;
        },
        keyHolder);
    return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
  }

  public void replaceDescriptions(Long recordId, List<ReviewDataDescriptionSaveRequest> descriptions) {
    softDeleteDescriptions(recordId);
    List<ReviewDataDescriptionSaveRequest> safeDescriptions =
        descriptions == null ? List.of() : descriptions;
    for (int index = 0; index < safeDescriptions.size(); index++) {
      ReviewDataDescriptionSaveRequest description = safeDescriptions.get(index);
      insertDescription(
          recordId,
          new ReviewDataDescriptionSaveRequest(
              description.reviewProduct(),
              description.reviewVersion(),
              description.authorName(),
              description.reviewScalePages(),
              description.unit(),
              description.sortOrder() == null ? index : description.sortOrder()));
    }
  }

  public void replaceContents(Long recordId, List<ReviewDataContentSaveRequest> contents) {
    softDeleteContents(recordId);
    List<ReviewDataContentSaveRequest> safeContents = contents == null ? List.of() : contents;
    for (int index = 0; index < safeContents.size(); index++) {
      ReviewDataContentSaveRequest content = safeContents.get(index);
      insertContent(
          recordId,
          new ReviewDataContentSaveRequest(
              content.reviewerName(),
              content.assignmentContent(),
              content.independentWorkloadHours(),
              content.independentProblemCount(),
              content.meetingWorkloadHours(),
              content.meetingProblemCount(),
              content.sortOrder() == null ? index : content.sortOrder()));
    }
  }

  public void ensurePrimaryDescription(
      Long recordId,
      String reviewProduct,
      String reviewVersion,
      String authorName,
      Integer reviewScalePages) {
    List<ReviewDataDescriptionResponse> existing = listDescriptions(recordId);
    if (existing.isEmpty()) {
      insertDescription(
          recordId,
          new ReviewDataDescriptionSaveRequest(
              reviewProduct, reviewVersion, authorName, reviewScalePages, "页", 0));
      return;
    }
    ReviewDataDescriptionResponse first = existing.get(0);
    jdbcTemplate.update(
        """
        update review_record_descriptions
        set review_product = ?,
            review_version = ?,
            author_name = ?,
            review_scale_pages = ?,
            unit = coalesce(nullif(unit, ''), '页'),
            updated_at = current_timestamp
        where id = ?
        """,
        normalizeText(reviewProduct),
        normalizeText(reviewVersion),
        normalizeText(authorName),
        ReviewDataNumberSupport.safeInt(reviewScalePages),
        first.id());
  }

  private void softDeleteDescriptions(Long recordId) {
    jdbcTemplate.update(
        "update review_record_descriptions set deleted = true, updated_at = current_timestamp where review_record_id = ?",
        recordId);
  }

  private void softDeleteContents(Long recordId) {
    jdbcTemplate.update(
        "update review_record_contents set deleted = true, updated_at = current_timestamp where review_record_id = ?",
        recordId);
  }

  private ReviewDataDescriptionResponse mapDescription(ResultSet rs, int rowNum) throws SQLException {
    return new ReviewDataDescriptionResponse(
        rs.getLong("id"),
        rs.getLong("review_record_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("review_product")),
        TextQuerySupport.normalizeDisplay(rs.getString("review_version")),
        TextQuerySupport.normalizeDisplay(rs.getString("author_name")),
        rs.getInt("review_scale_pages"),
        TextQuerySupport.normalizeDisplay(rs.getString("unit")),
        rs.getInt("sort_order"),
        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
  }

  private ReviewDataContentResponse mapContent(ResultSet rs, int rowNum) throws SQLException {
    return new ReviewDataContentResponse(
        rs.getLong("id"),
        rs.getLong("review_record_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("reviewer_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("assignment_content")),
        rs.getBigDecimal("independent_workload_hours") == null
            ? 0D
            : rs.getBigDecimal("independent_workload_hours").doubleValue(),
        rs.getInt("independent_problem_count"),
        rs.getBigDecimal("meeting_workload_hours") == null
            ? 0D
            : rs.getBigDecimal("meeting_workload_hours").doubleValue(),
        rs.getInt("meeting_problem_count"),
        rs.getInt("sort_order"),
        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
  }

  private String normalizeText(String value) {
    return Objects.requireNonNullElse(TextQuerySupport.trimToNull(value), "");
  }

  private String normalizeNullableText(String value) {
    return TextQuerySupport.trimToNull(value);
  }

  private String normalizeUnit(String value) {
    return Objects.requireNonNullElse(TextQuerySupport.trimToNull(value), "页");
  }

  private int safeSortOrder(Integer value) {
    return value == null ? 0 : Math.max(0, value);
  }
}
