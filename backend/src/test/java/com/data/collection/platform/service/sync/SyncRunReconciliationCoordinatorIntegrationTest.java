package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunReconciliationCoordinatorIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncRunReconciliationCoordinator coordinator;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("scope_stage_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists sync_run_authoritative_scopes cascade");
    jdbcTemplate.execute("drop table if exists sync_run_table_tasks cascade");
    jdbcTemplate.execute("drop table if exists sync_run_table_states cascade");
    jdbcTemplate.execute("drop table if exists sync_runs cascade");
    createSchema();
    coordinator = new SyncRunReconciliationCoordinator(jdbcTemplate);
  }

  @Test
  void test_incomplete_scan_producer_blocks_reconciliation_planning() {
    insertRun(1L, "INCREMENTAL_SYNC");
    insertScanTask(1L, 11L, "issues", "SUCCESS");
    insertScanTask(1L, 12L, "notes", "RUNNING");

    assertThat(coordinator.planIfReady(1L)).isZero();
    assertThat(reconciliationCount(1L)).isZero();
  }

  @Test
  void test_retry_waiting_or_failed_scope_never_looks_like_an_empty_queue() {
    insertRun(2L, "INCREMENTAL_SYNC");
    insertScanTask(2L, 21L, "issues", "SUCCESS");
    insertScope(2L, "issue_assignees", "RETRY_WAITING");

    assertThat(coordinator.planIfReady(2L)).isZero();
    jdbcTemplate.update(
        "update sync_run_authoritative_scopes set status = 'FAILED' where run_id = 2");
    assertThat(coordinator.planIfReady(2L)).isZero();
    assertThat(reconciliationCount(2L)).isZero();
  }

  @Test
  void test_all_producers_and_scopes_success_plan_one_task_per_table_once() {
    insertRun(3L, "INCREMENTAL_SYNC");
    insertScanTask(3L, 31L, "issues", "SUCCESS");
    insertScanTask(3L, 32L, "notes", "SUCCESS");
    insertScope(3L, "issue_assignees", "SUCCESS");

    assertThat(coordinator.planIfReady(3L)).isEqualTo(2);
    assertThat(coordinator.planIfReady(3L)).isZero();
    assertThat(reconciliationCount(3L)).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForList(
                "select source_table from sync_run_table_tasks "
                    + "where run_id = 3 and task_stage = 'RECONCILE' order by source_table",
                String.class))
        .containsExactly("issues", "notes");
  }

  @Test
  void test_system_hook_run_does_not_plan_full_table_reconciliation() {
    insertRun(4L, "SYSTEM_HOOK");
    insertScanTask(4L, 41L, "issues", "SUCCESS");

    assertThat(coordinator.planIfReady(4L)).isZero();
    assertThat(reconciliationCount(4L)).isZero();
  }

  private void createSchema() {
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          run_type varchar(64) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_states (
          id bigint primary key
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_tasks (
          id bigserial primary key,
          run_id bigint not null references sync_runs(id),
          config_id bigint not null,
          state_id bigint references sync_run_table_states(id),
          source_instance varchar(128) not null,
          source_table varchar(255) not null,
          mirror_table varchar(255) not null,
          task_type varchar(64) not null,
          status varchar(32) not null,
          row_strategy varchar(64) not null,
          task_stage varchar(32) not null,
          parent_task_id bigint,
          watermark_at timestamp,
          cursor_updated_at timestamp,
          cursor_pk text,
          scan_upper_bound_at timestamp,
          page_number integer,
          lookup_scope_json text,
          batch_size integer not null,
          run_after timestamp not null,
          retry_count integer not null,
          max_retry_count integer not null,
          rows_scanned bigint not null,
          rows_applied bigint not null,
          created_at timestamp not null,
          updated_at timestamp not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_authoritative_scopes (
          id bigserial primary key,
          run_id bigint not null references sync_runs(id),
          child_table varchar(255) not null,
          status varchar(32) not null
        )
        """);
  }

  private void insertRun(long runId, String runType) {
    jdbcTemplate.update(
        "insert into sync_runs(id, run_type) values (?, ?)", runId, runType);
  }

  private void insertScanTask(long runId, long taskId, String table, String status) {
    jdbcTemplate.update(
        "insert into sync_run_table_states(id) values (?)", taskId);
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            id, run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, page_number, batch_size, run_after,
            retry_count, max_retry_count, rows_scanned, rows_applied, created_at, updated_at)
        values (?, ?, 1, ?, 'alpha', ?, ?, 'INCREMENTAL_SYNC', ?, 'INCREMENTAL',
                'SCAN', 1, 500, current_timestamp, 0, 3, 0, 0,
                current_timestamp, current_timestamp)
        """,
        taskId,
        runId,
        taskId,
        table,
        "ods_gitlab_" + table,
        status);
  }

  private void insertScope(long runId, String childTable, String status) {
    jdbcTemplate.update(
        "insert into sync_run_authoritative_scopes(run_id, child_table, status) values (?, ?, ?)",
        runId,
        childTable,
        status);
  }

  private int reconciliationCount(long runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_run_table_tasks "
                + "where run_id = ? and task_stage = 'RECONCILE'",
            Integer.class,
            runId);
    return count == null ? 0 : count;
  }
}
