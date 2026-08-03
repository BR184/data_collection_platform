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

  @Test
  void shouldDefineLegacyPlatformFormalHandoverModel() throws IOException {
    String migration = readMigration(
        "V20260721_03__legacy_formal_handover_model.sql");

    assertThat(migration)
        .contains("add column if not exists authority")
        .contains("'legacy_managed', 'platform_owned'")
        .contains("create table if not exists review_data_match_mode_contents")
        .contains("create or replace view code_review_formal_records")
        .contains("source_system = 'legacy_platform'")
        .contains("drop column if exists review_data_read_mode");
  }

  @Test
  void shouldRestoreReviewCompatibilityReadSourceWithoutLegacyManagedCopies()
      throws IOException {
    String migration = readMigration(
        "V20260730_07__restore_review_match_mode_read_source.sql");

    assertThat(migration)
        .contains("add column if not exists review_data_read_mode")
        .contains("check (review_data_read_mode in ('compatibility', 'formal'))")
        .contains("delete from review_records record")
        .contains("where authority = 'legacy_managed'")
        .contains("check (authority = 'platform_owned')")
        .contains("drop column if exists last_handover_at")
        .contains("drop column if exists review_requested")
        .contains("drop column if exists review_status")
        .contains("create or replace view review_visible_records")
        .contains("create or replace view review_visible_problem_items");
  }

  @Test
  void shouldDefineCustomerMembershipAndResponseTemplateFacts() throws IOException {
    String schemaMigration = readMigration(
        "V20260722_01__customer_issue_customer_and_response_template_schema.sql");
    String aliasSeedMigration = readMigration(
        "V20260722_02__seed_customer_issue_customer_aliases.sql");
    String customerAliasUpdateMigration = readMigration(
        "V20260723_01__seed_customer_issue_customer_aliases.sql");

    assertThat(schemaMigration)
        .contains("add column if not exists customer_names text")
        .contains("add column if not exists planned_resolution_at timestamp")
        .contains("add column if not exists planned_merge_version_branch text")
        .contains("create table if not exists issue_customer_name_aliases")
        .contains("create table if not exists issue_fact_customer_members")
        .contains("idx_issue_fact_customer_members_customer_lookup");
    assertThat(aliasSeedMigration)
        .contains("('新世纪', '郑州新世纪')")
        .contains("on conflict (alias_name)");
    assertThat(customerAliasUpdateMigration)
        .contains("('极目数字（苏普耐）', '极目数字')")
        .contains("('极目数字(苏普耐)', '极目数字')")
        .contains("('极目数字（苏普耐）——新版本适配测试', '极目数字')")
        .contains("('极目数字(苏普耐)——新版本适配测试', '极目数字')")
        .contains("on conflict (alias_name)");
  }

  @Test
  void shouldAllowLongPageSnapshotSourceVersions() throws IOException {
    String migration = readMigration(
        "V20260803_01__page_record_snapshot_source_version_text.sql");

    assertThat(migration)
        .contains("alter table page_record_snapshots")
        .contains("alter column source_version type text")
        .contains("using source_version::text");
  }

  @Test
  void shouldDefineAndBackfillIndependentIssueHandlerFact() throws IOException {
    String schemaMigration = readMigration("V20260722_03__add_issue_fact_handler_name.sql");
    String backfillMigration = readMigration("V20260722_04__backfill_issue_fact_handler_name.sql");

    assertThat(schemaMigration).contains("add column if not exists handler_name text");
    assertThat(backfillMigration)
        .contains("set handler_name = assignee_name")
        .contains("nullif(btrim(handler_name), '') is null");
  }

  @Test
  void shouldDefineCustomerIssueResponseTimesIndexMigration() throws IOException {
    String migration = readMigration("V20260722_05__add_customer_issue_response_times_index.sql");

    assertThat(migration)
        .contains("create index if not exists idx_issue_fact_customer_response_times")
        .contains("on issue_fact(project_id, milestone_title, research_template_time, fixed_label_time)")
        .contains("where project_id = 325 and deleted = false");
  }

  @Test
  void shouldDefineRecoverableCodeReviewMetricEnrichmentSchemaAndMemberSeed()
      throws IOException {
    String schemaMigration = readMigration(
        "V20260730_01__code_review_metric_enrichment_schema.sql");
    String memberSeed = readMigration(
        "V20260730_02__seed_quality_ranking_members.sql");
    String queueRefinement = readMigration(
        "V20260730_03__refine_code_review_metric_enrichment_queue.sql");
    String legacyCompletion = readMigration(
        "V20260730_04__complete_legacy_code_review_metrics.sql");

    assertThat(schemaMigration)
        .contains("add column if not exists source_instance")
        .contains("add column if not exists added_lines")
        .contains("add column if not exists enrichment_status varchar(32) not null default 'pending'")
        .contains("idx_code_review_external_metrics_enrichment_queue")
        .contains("create table if not exists code_review_metric_enrichment_states")
        .contains("historical_cursor_merge_request_id")
        .contains("active_sync_run_id")
        .contains("active_run_cursor_merge_request_id")
        .contains("create table if not exists quality_board_member_scopes");
    assertThat(memberSeed)
        .contains("('quality_ranking', 'cc'")
        .contains("on conflict (topic_key, business_source, member_name) do nothing");
    assertThat(queueRefinement)
        .contains("alter column enrichment_status set default 'success'")
        .contains("on code_review_external_metrics(source_instance, enrichment_status");
    assertThat(legacyCompletion)
        .contains("set enrichment_status = 'success'")
        .contains("metric_source_updated_at is null");
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
