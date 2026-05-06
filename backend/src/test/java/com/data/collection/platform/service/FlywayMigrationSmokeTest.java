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
    assertThat(tableExists("fact_build_tasks")).isTrue();
    assertThat(successfulMigrationCount()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void shouldApplySearchAndFactQuerySchemaMigration() {
    assertThat(tableExists("review_records")).isTrue();
    assertThat(tableExists("issue_fact")).isTrue();
    assertThat(tableExists("merge_request_fact")).isTrue();

    assertThat(columnExists("review_records", "search_spell")).isTrue();
    assertThat(columnExists("review_records", "title_search_initials")).isTrue();
    assertThat(columnExists("issue_fact", "reason_category")).isTrue();
    assertThat(columnExists("issue_fact", "is_illegal")).isTrue();
    assertThat(columnExists("issue_fact", "phase_filter_value")).isTrue();
    assertThat(columnExists("issue_fact", "search_spell")).isTrue();
    assertThat(columnExists("merge_request_fact", "owner_search_initials")).isTrue();

    assertThat(indexExists("idx_review_records_search_spell_trgm")).isTrue();
    assertThat(indexExists("idx_issue_fact_search_spell_trgm")).isTrue();
    assertThat(indexExists("idx_issue_fact_illegal_list_updated")).isTrue();
    assertThat(indexExists("idx_merge_request_fact_owner_search_initials_trgm")).isTrue();
  }

  private boolean tableExists(String tableName) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
             from information_schema.tables
             where table_schema = 'qaflex_test_migration'
               and table_name = ?
            """,
            Integer.class,
            tableName);
    return count != null && count > 0;
  }

  private boolean columnExists(String tableName, String columnName) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from information_schema.columns
             where table_schema = 'qaflex_test_migration'
               and table_name = ?
               and column_name = ?
            """,
            Integer.class,
            tableName,
            columnName);
    return count != null && count > 0;
  }

  private boolean indexExists(String indexName) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from pg_indexes
             where schemaname = 'qaflex_test_migration'
               and indexname = ?
            """,
            Integer.class,
            indexName);
    return count != null && count > 0;
  }

  private int successfulMigrationCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from qaflex_test_migration.flyway_schema_history
             where success = true
            """,
            Integer.class);
    return count == null ? 0 : count;
  }
}
