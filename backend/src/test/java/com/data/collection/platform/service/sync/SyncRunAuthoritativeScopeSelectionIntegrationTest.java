package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

  @Test
  void test_failed_scope_reuses_current_scope_from_another_signal() {
    jdbcTemplate.update("insert into sync_runs(id, payload_json) values (1, '{}'), (2, '{}')");
    enqueueLabelScope(1L, "issue-label-links");
    jdbcTemplate.update("update sync_run_authoritative_scopes set status = 'FAILED' where run_id = 1");
    enqueueLabelScope(2L, "label-event-issue-links");

    assertThat(repository.adoptFailedScopes(2L, "alpha", Set.of("label_links"))).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from sync_run_authoritative_scopes where run_id = 2 and status = 'QUEUED'",
        Integer.class)).isOne();
    assertThat(jdbcTemplate.queryForObject(
        "select recovered_by_run_id from sync_run_authoritative_scopes where run_id = 1",
        Long.class)).isEqualTo(2L);
  }

  @Test
  void test_multiple_failed_signals_share_one_recovery_scope() {
    jdbcTemplate.update("insert into sync_runs(id, payload_json) values (1, '{}'), (2, '{}'), (3, '{}')");
    enqueueLabelScope(1L, "issue-label-links");
    enqueueLabelScope(2L, "label-event-issue-links");
    jdbcTemplate.update("update sync_run_authoritative_scopes set status = 'FAILED'");

    assertThat(repository.adoptFailedScopes(3L, "alpha", Set.of("label_links"))).isOne();
    assertThat(repository.adoptFailedScopes(3L, "alpha", Set.of("label_links"))).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from sync_run_authoritative_scopes where run_id = 3 and status = 'QUEUED'",
        Integer.class)).isOne();
    assertThat(jdbcTemplate.queryForList(
        "select recovered_by_run_id from sync_run_authoritative_scopes where run_id in (1, 2) order by run_id",
        Long.class)).containsExactly(3L, 3L);
  }

  private void enqueueLabelScope(long runId, String relationKey) {
    repository.enqueueScopes(
        runId, "alpha", null, "label_links", relationKey,
        java.util.List.of(Map.of("target_type", "Issue", "target_id", 101L)));
  }

  @Test
  void test_failed_recovery_link_rolls_back_scope_and_can_be_retried() {
    jdbcTemplate.update("insert into sync_runs(id, payload_json) values (1, '{}'), (2, '{}')");
    enqueueLabelScope(1L, "issue-label-links");
    jdbcTemplate.update("update sync_run_authoritative_scopes set status = 'FAILED'");
    jdbcTemplate.execute(
        """
        create function reject_recovery_link() returns trigger language plpgsql as $$
        begin
          raise exception 'recovery link rejected by test';
        end;
        $$
        """);
    jdbcTemplate.execute(
        """
        create trigger reject_recovery_link before update of recovered_by_run_id
        on sync_run_authoritative_scopes for each row execute function reject_recovery_link()
        """);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
    // 与生产方法的事务边界一致，验证登记与旧记录关联不能只提交其中一半。
    try {
      assertThatThrownBy(() -> transaction.execute(status ->
          repository.adoptFailedScopes(2L, "alpha", Set.of("label_links"))))
          .isInstanceOf(org.springframework.dao.DataAccessException.class)
          .hasMessageContaining("recovery link rejected by test");
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from sync_run_authoritative_scopes where run_id = 2",
          Integer.class)).isZero();
      assertThat(jdbcTemplate.queryForObject(
          "select recovered_by_run_id from sync_run_authoritative_scopes where run_id = 1",
          Long.class)).isNull();
    } finally {
      jdbcTemplate.execute("drop trigger reject_recovery_link on sync_run_authoritative_scopes");
      jdbcTemplate.execute("drop function reject_recovery_link()");
    }
    Integer adopted = transaction.execute(status ->
        repository.adoptFailedScopes(2L, "alpha", Set.of("label_links")));
    assertThat(adopted).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select recovered_by_run_id from sync_run_authoritative_scopes where run_id = 1",
        Long.class)).isEqualTo(2L);
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
    jdbcTemplate.execute(
        """
        create unique index uk_sync_run_authoritative_scopes_canonical_scope
          on sync_run_authoritative_scopes(run_id, child_table, scope_signature)
        """);
  }
}
