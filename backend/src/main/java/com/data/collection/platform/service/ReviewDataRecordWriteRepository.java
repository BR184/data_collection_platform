package com.data.collection.platform.service;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
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
      throw new IllegalArgumentException("评审记录不存在: " + recordId);
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
    TextQuerySupport.SearchIndex searchIndex =
        ReviewDataSearchIndexSupport.buildRecordIndex(
            title, projectName, moduleName, reviewOwner, reviewType, List.of());
    TextQuerySupport.SearchIndex titleSearchIndex = TextQuerySupport.buildSearchIndex(title);
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
                    search_text,
                    search_compact,
                    search_spell,
                    search_initials,
                    title_search_text,
                    title_search_compact,
                    title_search_spell,
                    title_search_initials,
                    created_at,
                    updated_at
                  ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
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
          statement.setString(11, searchIndex.normalized());
          statement.setString(12, searchIndex.compact());
          statement.setString(13, searchIndex.spell());
          statement.setString(14, searchIndex.initials());
          statement.setString(15, titleSearchIndex.normalized());
          statement.setString(16, titleSearchIndex.compact());
          statement.setString(17, titleSearchIndex.spell());
          statement.setString(18, titleSearchIndex.initials());
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
    TextQuerySupport.SearchIndex searchIndex =
        ReviewDataSearchIndexSupport.buildRecordIndex(
            title, projectName, moduleName, reviewOwner, reviewType, List.of());
    TextQuerySupport.SearchIndex titleSearchIndex = TextQuerySupport.buildSearchIndex(title);
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
          search_text = ?,
          search_compact = ?,
          search_spell = ?,
          search_initials = ?,
          title_search_text = ?,
          title_search_compact = ?,
          title_search_spell = ?,
          title_search_initials = ?,
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
        searchIndex.normalized(),
        searchIndex.compact(),
        searchIndex.spell(),
        searchIndex.initials(),
        titleSearchIndex.normalized(),
        titleSearchIndex.compact(),
        titleSearchIndex.spell(),
        titleSearchIndex.initials(),
        recordId);
  }

  public void refreshSearchIndex(Long recordId) {
    Map<String, Object> record =
        jdbcTemplate.queryForMap(
            """
            select title, project_name, module_name, review_owner, review_type
            from review_records
            where id = ?
            """,
            recordId);
    List<String> experts =
        jdbcTemplate.query(
            """
            select expert_name
            from review_record_experts
            where review_record_id = ? and deleted = false
            order by sort_order asc, id asc
            """,
            (rs, rowNum) -> rs.getString("expert_name"),
            recordId);
    TextQuerySupport.SearchIndex searchIndex =
        ReviewDataSearchIndexSupport.buildRecordIndex(
            Objects.toString(record.get("title"), ""),
            Objects.toString(record.get("project_name"), ""),
            Objects.toString(record.get("module_name"), ""),
            Objects.toString(record.get("review_owner"), ""),
            Objects.toString(record.get("review_type"), ""),
            experts);
    TextQuerySupport.SearchIndex titleSearchIndex =
        TextQuerySupport.buildSearchIndex(Objects.toString(record.get("title"), ""));
    jdbcTemplate.update(
        """
        update review_records
        set search_text = ?,
            search_compact = ?,
            search_spell = ?,
            search_initials = ?,
            title_search_text = ?,
            title_search_compact = ?,
            title_search_spell = ?,
            title_search_initials = ?
        where id = ?
        """,
        searchIndex.normalized(),
        searchIndex.compact(),
        searchIndex.spell(),
        searchIndex.initials(),
        titleSearchIndex.normalized(),
        titleSearchIndex.compact(),
        titleSearchIndex.spell(),
        titleSearchIndex.initials(),
        recordId);
  }

  public void refreshMissingSearchIndexes(int limit) {
    int safeLimit = limit <= 0 ? 200 : Math.min(limit, 1000);
    List<Long> recordIds =
        jdbcTemplate.queryForList(
            """
            select id
            from review_records
            where deleted = false and (search_text is null or title_search_text is null)
            order by id asc
            limit ?
            """,
            Long.class,
            safeLimit);
    for (Long recordId : recordIds) {
      refreshSearchIndex(recordId);
    }
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
