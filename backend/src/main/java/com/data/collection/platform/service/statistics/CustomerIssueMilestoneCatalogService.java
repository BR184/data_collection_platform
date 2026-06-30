package com.data.collection.platform.service.statistics;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueMilestoneCatalogService {
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final LocalDate CUSTOMER_ISSUE_START_DATE = LocalDate.of(2026, 1, 1);
  private final JdbcTemplate jdbcTemplate;

  public CustomerIssueMilestoneCatalogService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<String> listMilestones() {
    Set<String> milestones = new LinkedHashSet<>(listMirrorMilestones());
    milestones.addAll(listFactMilestones());
    return milestones.stream().filter(StringUtils::hasText).toList();
  }

  private List<String> listMirrorMilestones() {
    String sql =
        """
        select distinct nullif(btrim(m.title), '') as milestone_title
          from ods_gitlab_issues i
          join ods_gitlab_milestones m
            on m.id = i.milestone_id
           and coalesce(m.mirror_deleted, false) = false
         where coalesce(i.mirror_deleted, false) = false
           and i.project_id = ?
           and nullif(btrim(m.title), '') is not null
         order by milestone_title desc
        """;
    return queryMilestones(sql, "ods_gitlab_milestones", LEGACY_CC_PRODUCT_PROJECT_ID);
  }

  private List<String> listFactMilestones() {
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
    return queryMilestones(sql, "issue_fact", LEGACY_CC_PRODUCT_PROJECT_ID, CUSTOMER_ISSUE_START_DATE);
  }

  private List<String> queryMilestones(String sql, String sourceName, Object... args) {
    try {
      return jdbcTemplate.queryForList(sql, String.class, args)
        .stream()
        .map(value -> value == null ? "" : value.trim())
        .filter(StringUtils::hasText)
        .toList();
    } catch (DataAccessException error) {
      log.debug("Customer issue milestone source {} is unavailable", sourceName, error);
      return List.of();
    }
  }
}
