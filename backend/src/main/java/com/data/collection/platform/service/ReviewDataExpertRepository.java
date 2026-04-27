package com.data.collection.platform.service;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewDataExpertRepository {
  private final JdbcTemplate jdbcTemplate;

  public ReviewDataExpertRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
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
    return jdbcTemplate.query(
        """
        select distinct expert_name
        from review_record_experts
        where deleted = false and coalesce(expert_name, '') <> ''
        order by expert_name asc
        """,
        (rs, rowNum) -> rs.getString("expert_name"));
  }
}
