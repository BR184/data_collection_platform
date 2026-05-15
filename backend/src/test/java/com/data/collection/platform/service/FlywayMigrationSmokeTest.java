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
