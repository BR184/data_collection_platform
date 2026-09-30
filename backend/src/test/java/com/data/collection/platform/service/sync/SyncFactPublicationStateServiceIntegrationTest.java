package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.FactType;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SyncFactPublicationStateServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncFactPublicationStateService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_publication_state_test");
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
    service =
        new SyncFactPublicationStateService(
            jdbcTemplate, mock(SyncRunAuthoritativeScopeRepository.class));
  }

  @Test
  void test_ready_family_with_pending_head_is_consumable() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', false)
        """);
    verifiedLabelEventSource();
    insertHead(501L, 9L, 4L);

    assertThat(service.isReady("alpha", FactType.ISSUE)).isTrue();
  }

  @Test
  void issue_family_is_not_consumable_when_label_events_have_no_full_verification() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', true)
        """);
    insertTableState("resource_label_events", null);

    assertThat(service.isReady("alpha", FactType.ISSUE)).isFalse();
  }

  @Test
  void issue_qualification_requires_verified_event_history_but_accepts_a_verified_empty_source() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', false)
        """);
    insertTableState("resource_label_events", null);

    assertThat(service.qualification("alpha", FactType.ISSUE))
        .satisfies(
            qualification -> {
              assertThat(qualification.readable()).isFalse();
              assertThat(qualification.reason()).contains("resource_label_events");
            });

    jdbcTemplate.update(
        "update sync_run_table_states set last_full_verified_at = current_timestamp "
            + "where source_instance = 'alpha' and source_table = 'resource_label_events'");

    assertThat(service.qualification("alpha", FactType.ISSUE))
        .satisfies(qualification -> assertThat(qualification.readable()).isTrue());
  }

  @Test
  void test_pending_publishable_source_is_degraded_with_pending_head_count() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', false)
        """);
    verifiedLabelEventSource();
    insertHead(601L, 9L, 4L);

    assertThat(service.qualification("alpha", FactType.ISSUE))
        .satisfies(
            qualification -> {
              assertThat(qualification.readable()).isTrue();
              assertThat(qualification.degraded()).isTrue();
              assertThat(qualification.pendingUpdates()).isEqualTo(1L);
            });
  }

  @Test
  void test_unsettled_full_publication_blocks_output_even_when_heads_are_converged() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', true)
        """);
    verifiedLabelEventSource();
    insertHead(701L, 9L, 9L);

    assertThat(service.qualification("alpha", FactType.ISSUE))
        .satisfies(
            qualification -> {
              assertThat(qualification.readable()).isFalse();
              assertThat(qualification.degraded()).isFalse();
              assertThat(qualification.reason()).contains("全量事实重建尚未结算");
            });
  }

  @Test
  void test_unpublished_head_versions_are_counted_from_version_fence() {
    insertHead(601L, 9L, 4L);
    insertHead(602L, 12L, 12L);

    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isEqualTo(1L);
  }

  @Test
  void test_published_head_versions_are_not_pending() {
    insertHead(701L, 9L, 9L);

    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isZero();
  }

  @Test
  void test_other_fact_type_heads_do_not_block_issue_convergence() {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'MERGE_REQUEST', 801, 7, 2)
        """);

    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isZero();
  }

  @Test
  void test_unregistered_root_is_not_counted() {
    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isZero();
  }

  @Test
  void test_journal_rows_never_change_the_authoritative_count() {
    insertHead(901L, 5L, 5L);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version, publication_status)
        values (601, 'alpha', 'ISSUE', 901, 5, 'PENDING')
        """);

    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isZero();
  }

  @Test
  void test_publication_upper_bound_reads_latest_change_version_of_family() {
    insertHead(1001L, 4L, 4L);
    insertHead(1002L, 12L, 12L);
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'MERGE_REQUEST', 1003, 99, 99)
        """);

    assertThat(service.publicationUpperBound("alpha", FactType.ISSUE)).isEqualTo(12L);
  }

  @Test
  void test_full_publication_settles_heads_only_up_to_frozen_covered_version() {
    insertHead(1101L, 10L, 0L);
    insertHead(1102L, 20L, 5L);
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', true)
        """);

    int updated = service.settleAfterFullPublication("alpha", FactType.ISSUE, 77L, 12L);

    assertThat(updated).isEqualTo(2);
    assertThat(headState(1101L))
        .containsEntry("published_version", 10L)
        .containsEntry("published_by_fact_build_task_id", 77L);
    assertThat(headState(1102L)).containsEntry("published_version", 12L);
    assertThat(fullPublicationRequested()).isFalse();
    assertThat(service.countUnpublishedTargets("alpha", FactType.ISSUE)).isEqualTo(1L);
  }

  @Test
  void test_full_publication_never_regresses_an_already_newer_published_version() {
    insertHead(1201L, 30L, 30L);

    int updated = service.settleAfterFullPublication("alpha", FactType.ISSUE, 88L, 20L);

    assertThat(updated).isZero();
    assertThat(headState(1201L)).containsEntry("published_version", 30L);
  }

  private void insertHead(long rootId, long latestVersion, long publishedVersion) {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'ISSUE', ?, ?, ?)
        """,
        rootId,
        latestVersion,
        publishedVersion);
  }

  private Map<String, Object> headState(long rootId) {
    return jdbcTemplate.queryForMap(
        """
        select latest_change_version, published_version, published_by_fact_build_task_id
          from fact_change_heads
         where source_instance = 'alpha' and fact_type = 'ISSUE' and root_id = ?
        """,
        rootId);
  }

  private boolean fullPublicationRequested() {
    Boolean requested =
        jdbcTemplate.queryForObject(
            """
            select full_publication_requested
              from source_fact_publication_states
             where source_instance = 'alpha' and fact_type = 'ISSUE'
            """,
            Boolean.class);
    return Boolean.TRUE.equals(requested);
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_table_states");
    jdbcTemplate.execute("drop table if exists sync_run_fact_targets");
    jdbcTemplate.execute("drop table if exists fact_change_heads");
    jdbcTemplate.execute("drop table if exists source_fact_publication_states");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          run_type varchar(32) not null,
          source_instance varchar(128) not null,
          status varchar(32) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_fact_targets (
          id bigint primary key,
          source_instance varchar(128) not null,
          fact_type varchar(64),
          root_id bigint,
          change_version bigint,
          publication_status varchar(32) not null,
          assigned_fact_run_id bigint,
          assigned_fact_build_task_id bigint,
          mirror_run_id bigint,
          updated_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute(
        """
        create table fact_change_heads (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          latest_change_version bigint not null,
          published_version bigint not null,
          published_by_fact_build_task_id bigint,
          updated_at timestamp not null default current_timestamp,
          primary key (source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table source_fact_publication_states (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          readiness_status varchar(16) not null,
          error_message text,
          full_publication_requested boolean not null default false,
          updated_at timestamp not null default current_timestamp,
          primary key (source_instance, fact_type)
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_states (
          source_instance varchar(128) not null,
          source_table varchar(128) not null,
          sync_enabled boolean not null,
          dirty_flag boolean not null,
          last_full_verified_at timestamp
        )
        """);
  }

  private void insertTableState(String sourceTable, java.time.LocalDateTime lastFullVerifiedAt) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_states(
            source_instance, source_table, sync_enabled, dirty_flag, last_full_verified_at)
        values ('alpha', ?, true, false, ?)
        """,
        sourceTable,
        lastFullVerifiedAt);
  }

  private void verifiedLabelEventSource() {
    insertTableState("resource_label_events", java.time.LocalDateTime.now());
  }
}
