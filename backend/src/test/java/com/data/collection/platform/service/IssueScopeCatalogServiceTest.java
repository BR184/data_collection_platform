package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.IssueScopeGroupSaveRequest;
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
class IssueScopeCatalogServiceTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private static JdbcTemplate jdbcTemplate;
  private IssueScopeCatalogService catalogService;
  private IssueScopeDefinitionService definitionService;

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
    catalogService = new IssueScopeCatalogService(jdbcTemplate);
    definitionService = new IssueScopeDefinitionService(jdbcTemplate);
  }

  @Test
  void test_customer_milestone_group_expands_exact_values_and_uses_admin_order_as_default() {
    long catalogId = insertCatalog(325L, "CCProduct", "MILESTONE");
    long r4 = insertGroup(catalogId, "CC2026R4", "四季度版本", 1, true);
    long r3 = insertGroup(catalogId, "CC2026R3", "三季度版本", 2, true);
    insertMember(catalogId, r4, "CC2026R4", 1, true);
    insertMember(catalogId, r4, "CC2026 R4", 2, true);
    insertMember(catalogId, r3, "CC2026R3", 1, true);

    assertThat(catalogService.defaultBusinessKey(325L, IssueScopeDimension.MILESTONE))
        .isEqualTo("CC2026R4");
    assertThat(
            catalogService.requireMemberValues(
                325L, IssueScopeDimension.MILESTONE, "CC2026R4"))
        .containsExactly("CC2026R4", "CC2026 R4");
    assertThat(
            catalogService.matches(
                325L, IssueScopeDimension.MILESTONE, "CC2026R4", "CC2026 R4"))
        .isTrue();
  }

  @Test
  void test_display_name_update_keeps_business_matching_and_disabled_group_disappears() {
    long catalogId = insertCatalog(9L, "CrownCAD", "TESTING_PHASE");
    long groupId = insertGroup(catalogId, "CC2026R4", "旧显示名", 1, true);
    insertMember(catalogId, groupId, "CC2026R4第一轮系统测试", 1, true);

    definitionService.updateGroup(
        groupId,
        new IssueScopeGroupSaveRequest(
            catalogId, "CC2026R4", "新显示名", 1, true, "仅修改展示"));

    IssueScopeCatalogService.ScopeGroup updated =
        catalogService
            .findEnabledGroup(9L, IssueScopeDimension.TESTING_PHASE, "CC2026R4")
            .orElseThrow();
    assertThat(updated.displayName()).isEqualTo("新显示名");
    assertThat(updated.members()).extracting(IssueScopeCatalogService.ScopeMember::sourceValue)
        .containsExactly("CC2026R4第一轮系统测试");

    definitionService.setGroupEnabled(groupId, false);
    assertThat(catalogService.listEnabledBusinessKeys(9L, IssueScopeDimension.TESTING_PHASE))
        .isEmpty();
  }

  @Test
  void test_business_key_cannot_change_after_creation() {
    long catalogId = insertCatalog(9L, "CrownCAD", "TESTING_PHASE");
    long groupId = insertGroup(catalogId, "CC2026R4", "R4", 1, true);

    assertThatThrownBy(
            () ->
                definitionService.updateGroup(
                    groupId,
                    new IssueScopeGroupSaveRequest(
                        catalogId, "CC2026R5", "R5", 1, true, null)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("业务键创建后不可修改");
  }

  private long insertCatalog(long projectId, String projectName, String dimension) {
    return jdbcTemplate.queryForObject(
        "insert into issue_scope_catalogs(project_id, project_name, dimension) values (?, ?, ?) returning id",
        Long.class,
        projectId,
        projectName,
        dimension);
  }

  private long insertGroup(
      long catalogId, String businessKey, String displayName, int sortOrder, boolean enabled) {
    return jdbcTemplate.queryForObject(
        "insert into issue_scope_groups(catalog_id, business_key, display_name, sort_order, enabled) values (?, ?, ?, ?, ?) returning id",
        Long.class,
        catalogId,
        businessKey,
        displayName,
        sortOrder,
        enabled);
  }

  private void insertMember(
      long catalogId, long groupId, String sourceValue, int sortOrder, boolean enabled) {
    jdbcTemplate.update(
        "insert into issue_scope_members(catalog_id, group_id, source_value, display_name, sort_order, enabled) values (?, ?, ?, ?, ?, ?)",
        catalogId,
        groupId,
        sourceValue,
        sourceValue,
        sortOrder,
        enabled);
  }
}
