package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 验证已有事实库升级时能够补建空的客户里程碑目录。 */
@Testcontainers(disabledWithoutDocker = true)
class CustomerIssueMilestoneCatalogMigrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  @Test
  void shouldBootstrapCatalogWhenFactsWerePublishedBeforeRepairMigration() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("20260813.01")
        .load()
        .migrate();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    long catalogId = jdbcTemplate.queryForObject(
        "select id from issue_scope_catalogs where project_id = 325 and dimension = 'MILESTONE'",
        Long.class);

    jdbcTemplate.update(
        """
        insert into issue_fact(project_id, issue_id, issue_iid, milestone_title)
        values
          (325, 1, 1, 'CC2026 R3'),
          (325, 2, 2, 'CC2026R3'),
          (325, 3, 3, 'cc2026r3'),
          (325, 4, 4, 'Customer Special'),
          (325, 5, 5, 'customer special')
        """);

    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_groups where catalog_id = ?",
            Integer.class,
            catalogId))
        .isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where catalog_id = ?",
            Integer.class,
            catalogId))
        .isEqualTo(5);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_groups where catalog_id = ? and lower(business_key) = 'customer special'",
            Integer.class,
            catalogId))
        .isOne();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where catalog_id = ? and group_id = "
                + "(select id from issue_scope_groups where catalog_id = ? and business_key = 'CC2026R3')",
            Integer.class,
            catalogId,
            catalogId))
        .isEqualTo(3);
  }
}
