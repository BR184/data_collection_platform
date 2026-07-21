package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FlywayMigrationSmokeTest {

  @Test
  void shouldDefineUnifiedRunSchemaMigration() throws IOException {
    String migration = readMigration("V20260515_01__sync_orchestrator_core.sql");

    assertThat(migration).contains("create table if not exists sync_runs");
    assertThat(migration).contains("create table if not exists sync_run_table_tasks");
    assertThat(migration).contains("create table if not exists sync_run_table_states");
    assertThat(migration).contains("create table if not exists sync_run_events");
    assertThat(migration).contains("create table if not exists sync_worker_leases");

    assertThat(migration).contains("config_id bigint not null");
    assertThat(migration).contains("source_instance varchar(128) not null");
    assertThat(migration).contains("run_type varchar(64) not null");
    assertThat(migration).contains("status varchar(32) not null");
    assertThat(migration).contains("priority integer not null");
    assertThat(migration).contains("cancel_requested boolean not null default false");
    assertThat(migration).contains("thread_mode varchar(32) not null default 'fixed'");
    assertThat(migration).contains("thread_value numeric(8, 3) not null default 2");
    assertThat(migration).contains("started_at timestamp");
    assertThat(migration).contains("finished_at timestamp");

    assertThat(migration).contains("idx_sync_runs_dispatch");
    assertThat(migration).contains("idx_sync_runs_config_source_status");
    assertThat(migration).contains("idx_sync_runs_scope_status");
  }

  @Test
  void shouldDefineUnifiedRunTableProgressSchemaMigration() throws IOException {
    String migration = readMigration("V20260515_01__sync_orchestrator_core.sql");

    assertThat(migration).contains("run_id bigint not null references sync_runs(id)");
    assertThat(migration).contains("source_table varchar(255) not null");
    assertThat(migration).contains("rows_scanned bigint not null default 0");
    assertThat(migration).contains("rows_applied bigint not null default 0");
    assertThat(migration).contains("dirty_flag boolean not null default false");
    assertThat(migration).contains("dirty_reason text");
    assertThat(migration).contains("last_watermark_at timestamp");

    assertThat(migration).contains("idx_sync_run_table_tasks_dispatch");
    assertThat(migration).contains("idx_sync_run_table_tasks_run");
    assertThat(migration).contains("idx_sync_run_table_states_dirty");
  }

  @Test
  void shouldDropLegacyRuntimeTablesBeforeUnifiedSchema() throws IOException {
    String migration = readMigration("V20260515_00__remove_legacy_gitlab_sync_models.sql");

    assertLegacyDrop(migration, "table", "sync", "tasks");
    assertLegacyDrop(migration, "table", "sync", "states");
    assertLegacyDrop(migration, "sync", "jobs");
    assertLegacyDrop(migration, "sync", "logs");
    assertLegacyDrop(migration, "sync", "tasks");
  }

  @Test
  void shouldAddGitlabSyncThreadConfigMigration() throws IOException {
    String migration = readMigration("V20260515_02__gitlab_sync_thread_config.sql");

    assertThat(migration).contains("alter table gitlab_sync_configs");
    assertThat(migration).contains("sync_thread_mode varchar(32) not null default 'fixed'");
    assertThat(migration).contains("sync_thread_value numeric(8, 3) not null default 2");
    assertThat(migration).contains("max_sync_threads integer");
  }

  @Test
  void shouldDisableAllTableWhitelistModeMigration() throws IOException {
    String migration = readMigration("V20260519_02__disable_all_table_whitelist_mode.sql");

    assertThat(migration).contains("update gitlab_sync_configs");
    assertThat(migration).contains("whitelist_mode = 'recommended'");
    assertThat(migration).contains("where whitelist_mode = 'all'");
  }

  @Test
  void shouldDefineLdapIdentityAndLocalPermissionSchema() throws IOException {
    String identityMigration = readMigration(
        "V20260720_01__ldap_identity_and_local_permissions.sql");
    String directoryViewMigration = readMigration(
        "V20260720_02__ldap_user_directory_view.sql");

    assertThat(identityMigration).contains("create table if not exists platform_ldap_users");
    assertThat(identityMigration).contains("create table if not exists platform_ldap_roles");
    assertThat(identityMigration).contains("create table if not exists platform_permissions");
    assertThat(identityMigration).contains("alter table review_records add column if not exists created_by");
    assertThat(identityMigration).contains("alter table review_problem_items add column if not exists created_by");
    assertThat(directoryViewMigration).contains("create or replace view platform_ldap_user_directory");
    assertThat(directoryViewMigration).contains("string_agg(distinct r.role_code");
  }

  @Test
  void shouldDefineUnifiedReviewVisibleReadModels() throws IOException {
    String migration = readMigration(
        "V20260720_03__unified_review_visible_read_models.sql");

    assertThat(migration)
        .contains("create or replace view review_visible_records")
        .contains("create or replace view review_visible_problem_items")
        .contains("union all")
        .contains("review_data_match_mode_edit_links")
        .contains("review_data_match_mode_problem_edit_links");
  }

  @Test
  void shouldUseExactJsonRelationsForMatchModeReviews() throws IOException {
    String migration = readMigration(
        "V20260721_01__exact_review_match_mode_relations.sql");

    assertThat(migration)
        .contains("create or replace view review_data_match_mode_report_problem_refs")
        .contains("create or replace view review_data_match_mode_report_description_refs")
        .contains("jsonb_array_elements_text")
        .contains("problem.legacy_id = problem_ref.problem_legacy_id")
        .contains("description.legacy_id = description_ref.description_legacy_id")
        .doesNotContain("like '%' ||");

    String aggregateMigration = readMigration(
        "V20260721_02__aggregate_review_match_mode_relations_once.sql");
    assertThat(aggregateMigration)
        .contains("with description_summaries as")
        .contains("problem_summaries as")
        .contains("group by problem_ref.report_id")
        .contains("left join problem_summaries problem_summary")
        .doesNotContain("join lateral");
  }

  @Test
  void shouldDefineLdapRoleDisplayOrderMigration() throws IOException {
    String migration = readMigration(
        "V20260720_04__ldap_role_display_order.sql");

    assertThat(migration)
        .contains("add column if not exists display_order integer not null default 1000")
        .contains("-- 展示顺序只用于权限设置页面的角色排列");

    String seedMigration = readMigration(
        "V20260720_05__seed_ldap_role_display_order.sql");
    assertThat(seedMigration)
        .contains("when 'super_admin' then 10")
        .contains("when 'admin' then 20")
        .contains("when 'direct_manager' then 30")
        .contains("when 'tree_manager' then 40")
        .contains("when 'normal_user' then 50")
        .contains("-- 展示顺序只用于权限设置页面的角色排列");
  }

  @Test
  void shouldDefinePlatformPermissionDefaultsSnapshot() throws IOException {
    String migration = readMigration(
        "V20260720_06__platform_permission_defaults.sql");

    assertThat(migration)
        .contains("create table if not exists platform_default_role_permissions")
        .contains("select role_code, permission_code")
        .contains("默认权限快照只用于");
  }

  private String readMigration(String fileName) throws IOException {
    return Files.readString(
            Path.of("src", "main", "resources", "db", "migration", fileName), StandardCharsets.UTF_8)
        .toLowerCase();
  }

  private void assertLegacyDrop(String migration, String... nameParts) {
    assertThat(migration)
        .contains("drop table if exists " + "gitlab_" + String.join("_", nameParts) + " cascade");
  }
}
