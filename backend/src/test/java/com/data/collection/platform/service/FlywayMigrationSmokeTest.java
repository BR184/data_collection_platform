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
  void shouldRemoveAbandonedLabelGroupingTables() throws IOException {
    String migration = readMigration("V20260609_02__remove_abandoned_label_grouping.sql");

    assertThat(migration).contains("drop table if exists tag_value_mapping cascade");
    assertThat(migration).contains("drop table if exists tag_value cascade");
    assertThat(migration).contains("drop table if exists tag_group cascade");
    assertThat(migration).contains("destructive-migration-reviewed:");
    assertThat(migration).contains("destructive-migration-recovery:");
  }

  @Test
  void shouldDefineSemanticSegmentationCoreSchema() throws IOException {
    String migration = readMigration("V20260609_03__semantic_segmentation_core_schema.sql");

    assertThat(migration).contains("create table if not exists semantic_scope_definition");
    assertThat(migration).contains("create table if not exists semantic_tag_group");
    assertThat(migration).contains("create table if not exists semantic_tag_value");
    assertThat(migration).contains("create table if not exists semantic_tag_value_mapping");
    assertThat(migration).contains("create table if not exists semantic_tag_group_build_run");
    assertThat(migration).contains("create table if not exists segment_definition");
    assertThat(migration).contains("create table if not exists segment_compute_run");
    assertThat(migration).contains("create table if not exists segment_member_current");
    assertThat(migration).contains("create table if not exists segment_member_stage");
    assertThat(migration).contains("create table if not exists segment_member_audit");
    assertThat(migration).contains("create table if not exists segment_snapshot");
    assertThat(migration).contains("create table if not exists segment_snapshot_member");

    assertThat(migration).contains("cache_ttl_seconds integer not null default 3600");
    assertThat(migration).contains("rule_doc_hash varchar(64)");
    assertThat(migration).contains("active_run_id bigint");
    assertThat(migration).contains("max_execution_time_ms integer not null default 30000");
    assertThat(migration).contains("max_allowed_members integer not null default 50000");

    assertThat(migration).contains("idx_semantic_tag_group_domain_key");
    assertThat(migration).contains("idx_semantic_tag_value_group");
    assertThat(migration).contains("idx_segment_definition_entity_scenario");
    assertThat(migration).contains("idx_segment_member_current_run");
    assertThat(migration).contains("idx_segment_member_stage_run");
    assertThat(migration).contains("idx_segment_compute_run_segment_status");
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
