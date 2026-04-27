package com.data.collection.platform.service;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataRecordWriteRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataRecordWriteRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void assertRecordExists(Long recordId) {
    Integer exists =
        jdbcTemplate.queryForObject(
            "select count(*) from review_records where id = ? and deleted = false",
            Integer.class,
            recordId);
    if (exists == null || exists == 0) {
      throw new IllegalArgumentException("璇勫璁板綍涓嶅瓨鍦? " + recordId);
    }
  }

  public void touchRecord(Long recordId) {
    jdbcTemplate.update("update review_records set updated_at = current_timestamp where id = ?", recordId);
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

  private String normalizeText(String value) {
    return Objects.requireNonNullElse(TextQuerySupport.trimToNull(value), "");
  }

  private int safeInt(Integer value) {
    return value == null ? 0 : Math.max(value, 0);
  }
}
