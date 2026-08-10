package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.common.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncRunAuthoritativeScopeSelectionIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncRunAuthoritativeScopeRepository repository;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("authoritative_scope_selection_test");
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
    repository =
        new SyncRunAuthoritativeScopeRepository(
            jdbcTemplate, new JsonUtils(new ObjectMapper()));
  }

  @Test
  void test_reconcile_only_selection_survives_without_scan_task() {
    jdbcTemplate.update("insert into sync_runs(id, payload_json) values (1, '{}')");
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(run_id, source_table, task_stage)
        values (1, 'issues', 'SCAN')
        """);

    repository.snapshotSelectedSourceTables(
        1L, java.util.List.of("issues", "issue_assignees"));

    assertThat(repository.selectedSourceTables(1L))
        .containsExactly("issue_assignees", "issues");
  }

  @Test
  void test_failed_scope_is_adopted_once_by_next_run_for_selected_table() {
    jdbcTemplate.update("insert into sync_runs(id, payload_json) values (1, '{}'), (2, '{}')");
    repository.enqueueScopes(
        1L,
        "alpha",
        null,
        "issue_label_links",
        "issue-label-owner",
        java.util.List.of(Map.of("issue_id", 101L)));
    repository.enqueueScopes(
        1L,
        "alpha",
        null,
        "notes",
        "issue-note-owner",
        java.util.List.of(Map.of("noteable_id", 101L)));
    jdbcTemplate.update(
        "update sync_run_authoritative_scopes set status = 'FAILED' where run_id = 1");

    int adopted = repository.adoptFailedScopes(2L, "alpha", Set.of("issue_label_links"));
    int duplicate = repository.adoptFailedScopes(2L, "alpha", Set.of("issue_label_links"));

    assertThat(adopted).isOne();
    assertThat(duplicate).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from sync_run_authoritative_scopes
                 where run_id = 2 and child_table = 'issue_label_links' and status = 'QUEUED'
                """,
                Integer.class))
        .isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select recovered_by_run_id from sync_run_authoritative_scopes
                 where run_id = 1 and child_table = 'issue_label_links'
                """,
                Long.class))
        .isEqualTo(2L);
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select recovered_by_run_id from sync_run_authoritative_scopes
                 where run_id = 1 and child_table = 'notes'
                """,
                Long.class))
        .isNull();
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_authoritative_scopes");
    jdbcTemplate.execute("drop table if exists sync_run_table_tasks");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          payload_json text
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_tasks (
          id bigserial primary key,
          run_id bigint not null,
          source_table varchar(128) not null,
          task_stage varchar(32) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_authoritative_scopes (
          id bigserial primary key,
          run_id bigint not null,
          source_instance varchar(128) not null,
          child_table varchar(255) not null,
          relation_key varchar(128) not null,
          scope_signature varchar(1024) not null,
          lookup_scope_json text not null,
          task_id bigint,
          status varchar(32) not null default 'QUEUED',
          lease_owner varchar(128),
          lease_expires_at timestamp,
          heartbeat_at timestamp,
          retry_count integer not null default 0,
          max_retry_count integer not null default 3,
          recovery_count integer not null default 0,
          run_after timestamp not null default current_timestamp,
          error_message text,
          started_at timestamp,
          finished_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp,
          recovered_by_run_id bigint,
          recovered_at timestamp,
          unique (run_id, child_table, relation_key, scope_signature)
        )
        """);
  }
}
