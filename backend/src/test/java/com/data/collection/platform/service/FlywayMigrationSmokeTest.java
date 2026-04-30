package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.schemas=qaflex_test_migration",
      "spring.flyway.default-schema=qaflex_test_migration",
      "spring.flyway.create-schemas=true",
      "spring.sql.init.mode=never"
    })
class FlywayMigrationSmokeTest {
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void shouldApplyFactBuildTaskMigration() {
    Integer tableCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from information_schema.tables
             where table_schema = 'qaflex_test_migration'
               and table_name = 'fact_build_tasks'
            """,
            Integer.class);
    Integer historyCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from qaflex_test_migration.flyway_schema_history
             where success = true
            """,
            Integer.class);

    assertThat(tableCount).isEqualTo(1);
    assertThat(historyCount).isGreaterThanOrEqualTo(1);
  }
}
