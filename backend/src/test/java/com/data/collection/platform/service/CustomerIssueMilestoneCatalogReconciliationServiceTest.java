package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CustomerIssueMilestoneCatalogReconciliationServiceTest {
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueMilestoneCatalogReconciliationService reconciliationService;
  @Autowired private IssueScopeCatalogService catalogService;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");
    jdbcTemplate.update("delete from issue_fact");
  }

  @Test
  void test_current_fact_variant_is_added_without_creating_or_enabling_scopes() {
    long catalogId = jdbcTemplate.queryForObject(
        "insert into issue_scope_catalogs(project_id, project_name, dimension) values (325, 'CCProduct', 'MILESTONE') returning id",
        Long.class);
    long enabledGroup = insertGroup(catalogId, "CC2026R3", true, 1);
    long disabledGroup = insertGroup(catalogId, "CC2026R4", false, 2);
    insertMember(catalogId, enabledGroup, "CC2026R3");
    insertMember(catalogId, disabledGroup, "CC2026R4");
    insertFact(1L, 1, "CC2026 R3");
    insertFact(2L, 2, "cc2026r3");
    insertFact(3L, 3, "CC2026 R4");
    insertFact(4L, 4, "CC2026 R5");

    assertThat(reconciliationService.reconcilePublishedFactValues()).isOne();
    assertThat(catalogService.requireMemberValues(
            325L, IssueScopeDimension.MILESTONE, "CC2026R3"))
        .containsExactly("CC2026R3", "CC2026 R3");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where lower(source_value) = 'cc2026r3'",
            Integer.class))
        .isOne();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_groups where business_key = 'CC2026R5'",
            Integer.class))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where source_value = 'CC2026 R4'",
            Integer.class))
        .isZero();
  }

  private long insertGroup(long catalogId, String businessKey, boolean enabled, int sortOrder) {
    return jdbcTemplate.queryForObject(
        "insert into issue_scope_groups(catalog_id, business_key, display_name, sort_order, enabled) values (?, ?, ?, ?, ?) returning id",
        Long.class,
        catalogId,
        businessKey,
        businessKey,
        sortOrder,
        enabled);
  }

  private void insertMember(long catalogId, long groupId, String sourceValue) {
    jdbcTemplate.update(
        "insert into issue_scope_members(catalog_id, group_id, source_value, display_name, sort_order, enabled) values (?, ?, ?, ?, 1, true)",
        catalogId,
        groupId,
        sourceValue,
        sourceValue);
  }

  private void insertFact(long issueId, int issueIid, String milestoneTitle) {
    jdbcTemplate.update(
        "insert into issue_fact(project_id, issue_id, issue_iid, milestone_title) values (325, ?, ?, ?)",
        issueId,
        issueIid,
        milestoneTitle);
  }
}
