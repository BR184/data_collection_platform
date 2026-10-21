package com.data.collection.platform.service;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataExpertRepository {
  private final JdbcTemplate jdbcTemplate;


  private final com.data.collection.platform.entity.read.DatasetReadSelection fixedRead;

  /** 固定页面关系读取对象；原仓储工厂仅供当前业务命令与安装控制面。 */
  public ReviewDataExpertRepository forRead(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    return new ReviewDataExpertRepository(this, selection);
  }

  private ReviewDataExpertRepository(ReviewDataExpertRepository factory, com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    this.jdbcTemplate = factory.jdbcTemplate;
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ReviewDataExpertRepository(JdbcTemplate jdbcTemplate) {
    this.fixedRead = null;
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<String> listRecordExperts(Long recordId) {
    return query(
        """
        select expert_name
        from review_record_experts
        where review_record_id = ? and deleted = false
        order by sort_order asc, id asc
        """,
        (rs, rowNum) -> rs.getString("expert_name"),
        recordId);
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

  public List<String> loadExpertOptions() {
    return query(
        """
        select distinct expert_name
        from review_record_experts
        where deleted = false and coalesce(expert_name, '') <> ''
        order by expert_name asc
        """,
        (rs, rowNum) -> rs.getString("expert_name"));
  }

  private <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return fixedRead == null ? jdbcTemplate.query(sql, mapper, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).query(fixedRead, sql, mapper, args);
  }
  private <T> T query(String sql, org.springframework.jdbc.core.ResultSetExtractor<T> extractor, Object... args) {
    return fixedRead == null ? jdbcTemplate.query(sql, extractor, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).query(fixedRead, sql, extractor, args);
  }
  private <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return fixedRead == null ? jdbcTemplate.queryForObject(sql, mapper, args)
        : new com.data.collection.platform.service.read.VersionedDatasetSql(jdbcTemplate).queryForObject(fixedRead, sql, mapper, args);
  }
}
