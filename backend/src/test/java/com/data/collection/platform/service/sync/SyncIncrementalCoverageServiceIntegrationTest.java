package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncIncrementalCoverageServiceIntegrationTest {
  private static final LocalDateTime UPPER_BOUND =
      LocalDateTime.of(2026, 8, 5, 20, 0);
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncIncrementalCoverageService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("incremental_coverage_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(database.dataSource());
    resetSchema();
    service = new SyncIncrementalCoverageService(jdbcTemplate);
  }

  @Test
  void test_required_tables_at_fixed_upper_bounds_are_fresh() {
    insertCompleteIssueTask();
    insertCompleteLabelEventTask();

    SyncIncrementalCoverageService.CoverageResult result = service.evaluate(1L);

    assertThat(result.complete()).isTrue();
    assertThat(result.message()).isEqualTo("快速增量数据已追平");
  }

  @Test
  void test_missing_issues_is_incomplete() {
    insertCompleteLabelEventTask();

    assertThat(service.evaluate(1L).message())
        .contains("缺少 issues 或 resource_label_events");
  }

  @Test
  void test_missing_label_events_is_incomplete() {
    insertCompleteIssueTask();

    assertThat(service.evaluate(1L).message())
        .contains("缺少 issues 或 resource_label_events");
  }

  @Test
  void test_missing_source_upper_bound_is_incomplete() {
    insertTask(
        "issues", "INCREMENTAL", null, UPPER_BOUND, null, null, "SUCCESS", "SCAN");
    insertCompleteLabelEventTask();

    assertThat(service.evaluate(1L).message()).contains("未固化的来源扫描上界");
  }

  @Test
  void test_checkpoint_before_source_upper_bound_is_incomplete() {
    insertTask(
        "issues",
        "INCREMENTAL",
        UPPER_BOUND,
        UPPER_BOUND.minusMinutes(1),
        null,
        null,
        "SUCCESS",
        "SCAN");
    insertCompleteLabelEventTask();

    assertThat(service.evaluate(1L).message()).contains("checkpoint");
  }

  @Test
  void test_reconciliation_task_makes_fast_incremental_incomplete() {
    insertCompleteIssueTask();
    insertCompleteLabelEventTask();
    insertTask(
        "issues", "RECONCILE_ONLY", null, null, null, null, "SUCCESS", "RECONCILE");

    assertThat(service.evaluate(1L).message()).contains("全表删除对账");
  }

  @Test
  void test_unfinished_authoritative_scope_is_incomplete() {
    insertCompleteIssueTask();
    insertCompleteLabelEventTask();
    jdbcTemplate.update(
        "insert into sync_run_authoritative_scopes(run_id, status) values (1, 'RETRYING')");

    assertThat(service.evaluate(1L).message()).contains("权威范围");
  }

  private void insertCompleteIssueTask() {
    insertTask(
        "issues", "INCREMENTAL", UPPER_BOUND, UPPER_BOUND, null, null, "SUCCESS", "SCAN");
  }

  private void insertCompleteLabelEventTask() {
    insertTask(
        "resource_label_events",
        "MONOTONIC_PRIMARY_KEY",
        null,
        null,
        "[\"12\"]",
        "[\"12\"]",
        "SUCCESS",
        "SCAN");
  }

  private void insertTask(
      String sourceTable,
      String rowStrategy,
      LocalDateTime upperBoundAt,
      LocalDateTime cursorUpdatedAt,
      String upperBoundPk,
      String cursorPk,
      String status,
      String stage) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
          run_id, source_table, status, task_stage, parent_task_id,
          lookup_scope_json, row_strategy, scan_upper_bound_at,
          cursor_updated_at, scan_upper_bound_pk, cursor_pk, page_number)
        values (1, ?, ?, ?, null, '', ?, ?, ?, ?, ?, 1)
        """,
        sourceTable,
        status,
        stage,
        rowStrategy,
        upperBoundAt == null ? null : Timestamp.valueOf(upperBoundAt),
        cursorUpdatedAt == null ? null : Timestamp.valueOf(cursorUpdatedAt),
        upperBoundPk,
        cursorPk);
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_authoritative_scopes");
    jdbcTemplate.execute("drop table if exists sync_run_table_tasks");
    jdbcTemplate.execute(
        """
        create table sync_run_table_tasks (
          id bigserial primary key,
          run_id bigint not null,
          source_table varchar(128) not null,
          status varchar(32) not null,
          task_stage varchar(32) not null,
          parent_task_id bigint,
          lookup_scope_json text,
          row_strategy varchar(64) not null,
          scan_upper_bound_at timestamp,
          cursor_updated_at timestamp,
          scan_upper_bound_pk text,
          cursor_pk text,
          page_number integer
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_authoritative_scopes (
          id bigserial primary key,
          run_id bigint not null,
          status varchar(32) not null
        )
        """);
  }
}
