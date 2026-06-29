package com.data.collection.platform.service.statistics;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CustomerIssueMilestoneCatalogService {
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);
  private final JdbcTemplate jdbcTemplate;

  public CustomerIssueMilestoneCatalogService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<String> listMilestones() {
    String sql =
        """
        select distinct milestone_title
          from issue_fact
         where deleted = false
           and project_id = ?
           and created_at_source >= ?
           and coalesce(milestone_title, '') <> ''
         order by milestone_title desc
        """;
    return jdbcTemplate.queryForList(
            sql,
            String.class,
            LEGACY_CC_PRODUCT_PROJECT_ID,
            CUSTOMER_ISSUE_START_DATE)
        .stream()
        .map(value -> value == null ? "" : value.trim())
        .filter(StringUtils::hasText)
        .toList();
  }
}
