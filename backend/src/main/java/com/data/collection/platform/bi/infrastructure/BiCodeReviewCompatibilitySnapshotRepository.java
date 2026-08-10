package com.data.collection.platform.bi.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/** 读取 BI 独占人工走查兼容快照的已发布版本。 */
public final class BiCodeReviewCompatibilitySnapshotRepository {
  private final JdbcTemplate jdbcTemplate;

  public BiCodeReviewCompatibilitySnapshotRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 返回只随成功发布递增的快照版本；失败同步不会改变该值。 */
  public String sourceVersion() {
    Long version =
        jdbcTemplate.queryForObject(
            "select published_version from bi_code_review_compatibility_sync_state where id = 1",
            Long.class);
    return "bi-code-review-compatibility:" + (version == null ? 0L : version);
  }
}
