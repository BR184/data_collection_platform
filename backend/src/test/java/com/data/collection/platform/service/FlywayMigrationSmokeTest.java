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
      // 关闭 schema.sql 初始化，确保这个烟测只验证 Flyway 自己能否建出完整基础结构。
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
    // 搜索字段当前仍由 Java 生成，Flyway 负责保证 SQL 查询所需的影子列和索引存在。
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

  @Test
  void shouldApplyGitlabSyncCoreSchemaMigration() {
    // GitLab 同步链路是数据入口，必须先于事实构建和镜像注册表在空库中独立创建。
    assertThat(tableExists("gitlab_sync_configs")).isTrue();
    assertThat(tableExists("gitlab_sync_logs")).isTrue();
    assertThat(tableExists("gitlab_sync_tasks")).isTrue();
    assertThat(tableExists("gitlab_webhook_events")).isTrue();
    assertThat(tableExists("gitlab_mirror_records")).isTrue();
    assertThat(tableExists("collect_form_records")).isTrue();

    assertThat(columnExists("gitlab_sync_configs", "source_mode")).isTrue();
    assertThat(columnExists("gitlab_sync_configs", "source_instance")).isTrue();
    assertThat(columnExists("gitlab_sync_configs", "docker_container_name")).isTrue();
    assertThat(columnExists("gitlab_sync_tasks", "dedupe_key")).isTrue();
    assertThat(columnExists("gitlab_sync_tasks", "pending_resync")).isTrue();
    assertThat(columnExists("gitlab_sync_tasks", "payload_json")).isTrue();
    assertThat(columnExists("gitlab_mirror_records", "row_data")).isTrue();
    assertThat(columnExists("collect_form_records", "template_code")).isTrue();

    assertThat(indexExists("idx_gitlab_mirror_records_table")).isTrue();
    assertThat(indexExists("idx_collect_form_records_context")).isTrue();
    assertThat(indexExists("idx_gitlab_sync_logs_config")).isTrue();
    assertThat(indexExists("idx_gitlab_sync_tasks_scope_status")).isTrue();
    assertThat(indexExists("idx_gitlab_sync_tasks_dedupe")).isTrue();
    assertThat(indexExists("uk_gitlab_sync_configs_source_instance")).isTrue();
  }

  @Test
  void shouldApplyOperationalSupportSchemaMigration() {
    // 运营支撑表依赖同步核心表，覆盖集成测试事实、模块字典和镜像表注册等后续查询基础。
    assertThat(tableExists("code_review_external_metrics")).isTrue();
    assertThat(tableExists("integration_test_fact")).isTrue();
    assertThat(tableExists("testing_phase_calendar")).isTrue();
    assertThat(tableExists("module_dictionary")).isTrue();
    assertThat(tableExists("sys_table_registry")).isTrue();

    assertThat(columnExists("code_review_external_metrics", "defect_count_source")).isTrue();
    assertThat(columnExists("integration_test_fact", "parse_status")).isTrue();
    assertThat(columnExists("integration_test_fact", "validation_reason")).isTrue();
    assertThat(columnExists("testing_phase_calendar", "phase_start_at")).isTrue();
    assertThat(columnExists("module_dictionary", "standard_module_name")).isTrue();
    assertThat(columnExists("sys_table_registry", "column_snapshot")).isTrue();
    assertThat(columnExists("sys_table_registry", "preview_enabled")).isTrue();

    assertThat(indexExists("idx_code_review_external_metrics_context")).isTrue();
    assertThat(indexExists("idx_integration_test_fact_phase")).isTrue();
    assertThat(indexExists("idx_testing_phase_calendar_context")).isTrue();
    assertThat(indexExists("uk_module_dictionary_global")).isTrue();
    assertThat(indexExists("idx_sys_table_registry_preview")).isTrue();
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
