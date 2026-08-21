package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CustomerIssueMilestoneCatalogReconciliationServiceTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private static JdbcTemplate jdbcTemplate;
  private CustomerIssueMilestoneCatalogReconciliationService reconciliationService;
  private IssueScopeCatalogService catalogService;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");
    jdbcTemplate.update("delete from issue_fact");
    reconciliationService = new CustomerIssueMilestoneCatalogReconciliationService(jdbcTemplate);
    catalogService = new IssueScopeCatalogService(jdbcTemplate);
  }

  @Test
  void test_existing_manual_configuration_only_receives_matching_enabled_members() {
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

  @Test
  void test_empty_catalog_bootstraps_groups_and_members_from_published_facts() {
    long catalogId = jdbcTemplate.queryForObject(
        "insert into issue_scope_catalogs(project_id, project_name, dimension) values (325, 'CCProduct', 'MILESTONE') returning id",
        Long.class);
    insertFact(1L, 1, "CC2026 R3");
    insertFact(2L, 2, "CC2026R3");
    insertFact(3L, 3, "cc2026r3");
    insertFact(4L, 4, "客户专项版本");

    assertThat(reconciliationService.reconcilePublishedFactValues()).isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_groups where catalog_id = ?",
            Integer.class,
            catalogId))
        .isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where catalog_id = ?",
            Integer.class,
            catalogId))
        .isEqualTo(3);
    assertThat(catalogService.requireMemberValues(
            325L, IssueScopeDimension.MILESTONE, "CC2026R3"))
        .containsExactly("CC2026 R3", "CC2026R3");
    assertThat(catalogService.requireMemberValues(
            325L, IssueScopeDimension.MILESTONE, "客户专项版本"))
        .containsExactly("客户专项版本");
  }

  @Test
  void test_catalog_with_only_disabled_groups_is_not_bootstrapped() {
    long catalogId = jdbcTemplate.queryForObject(
        "insert into issue_scope_catalogs(project_id, project_name, dimension) values (325, 'CCProduct', 'MILESTONE') returning id",
        Long.class);
    insertGroup(catalogId, "CC2026R3", false, 1);
    insertFact(1L, 1, "CC2026 R3");

    assertThat(reconciliationService.reconcilePublishedFactValues()).isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where catalog_id = ?",
            Integer.class,
            catalogId))
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
