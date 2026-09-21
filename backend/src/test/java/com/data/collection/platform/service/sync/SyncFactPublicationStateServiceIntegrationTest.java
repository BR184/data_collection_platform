package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
  void test_failed_fact_run_releases_only_its_source_target_assignments() {
    jdbcTemplate.update(
        """
        insert into sync_runs(id, run_type, source_instance, status) values
          (11, 'FACT_REFRESH', 'alpha', 'FAILED'),
          (12, 'FACT_REFRESH', 'alpha', 'RUNNING'),
          (13, 'FACT_REFRESH', 'beta', 'CANCELLED')
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, publication_status,
            assigned_fact_run_id, assigned_fact_build_task_id) values
          (101, 'alpha', 'QUEUED', 11, 1001),
          (102, 'alpha', 'QUEUED', 12, 1002),
          (103, 'beta', 'QUEUED', 13, 1003),
          (104, 'alpha', 'PUBLISHED', 11, 1004)
        """);

    int released = service.releaseFailedFactAssignments("alpha");

    assertThat(released).isOne();
    assertThat(targetState(101L))
        .containsEntry("publication_status", "PENDING")
        .containsEntry("assigned_fact_run_id", null)
        .containsEntry("assigned_fact_build_task_id", null);
    assertThat(targetState(102L))
        .containsEntry("publication_status", "QUEUED")
        .containsEntry("assigned_fact_run_id", 12L);
    assertThat(targetState(103L))
        .containsEntry("publication_status", "QUEUED")
        .containsEntry("assigned_fact_run_id", 13L);
    assertThat(targetState(104L))
        .containsEntry("publication_status", "PUBLISHED")
        .containsEntry("assigned_fact_run_id", 11L);
  }

  @Test
  void test_ready_family_with_pending_target_is_consumable() {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            source_instance, fact_type, readiness_status, full_publication_requested)
        values ('alpha', 'ISSUE', 'READY', false)
        """);
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'ISSUE', 501, 9, 4)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version,
            publication_status)
        values (201, 'alpha', 'ISSUE', 501, 9, 'PENDING')
        """);

    assertThat(service.isReady("alpha", com.data.collection.platform.entity.FactType.ISSUE))
        .isTrue();
  }

  @Test
  void test_unpublished_target_versions_are_counted_regardless_of_registering_mirror_run() {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'ISSUE', 601, 9, 4),
               ('alpha', 'ISSUE', 602, 12, 12)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version,
            publication_status, mirror_run_id)
        values (301, 'alpha', 'ISSUE', 601, 9, 'PENDING', 11),
               (302, 'alpha', 'ISSUE', 601, 9, 'PUBLISHED', 12),
               (303, 'alpha', 'ISSUE', 602, 12, 'PUBLISHED', 12)
        """);

    assertThat(
            service.countUnpublishedTargets(
                "alpha", com.data.collection.platform.entity.FactType.ISSUE))
        .isEqualTo(2L);
  }

  @Test
  void test_published_target_versions_are_not_pending() {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'ISSUE', 701, 9, 9)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version, publication_status)
        values (401, 'alpha', 'ISSUE', 701, 9, 'PENDING')
        """);

    assertThat(
            service.countUnpublishedTargets(
                "alpha", com.data.collection.platform.entity.FactType.ISSUE))
        .isZero();
  }

  @Test
  void test_other_fact_type_targets_do_not_block_issue_convergence() {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version)
        values ('alpha', 'MERGE_REQUEST', 801, 7, 2)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version, publication_status)
        values (501, 'alpha', 'MERGE_REQUEST', 801, 7, 'PENDING')
        """);

    assertThat(
            service.countUnpublishedTargets(
                "alpha", com.data.collection.platform.entity.FactType.ISSUE))
        .isZero();
  }

  @Test
  void test_target_without_change_head_is_not_counted() {
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            id, source_instance, fact_type, root_id, change_version, publication_status)
        values (601, 'alpha', 'ISSUE', 901, 5, 'PENDING')
        """);

    assertThat(
            service.countUnpublishedTargets(
                "alpha", com.data.collection.platform.entity.FactType.ISSUE))
        .isZero();
  }

  private java.util.Map<String, Object> targetState(long id) {
    return jdbcTemplate.queryForMap(
        """
        select publication_status, assigned_fact_run_id, assigned_fact_build_task_id
          from sync_run_fact_targets where id = ?
        """,
        id);
  }

  private void resetSchema() {
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
          primary key (source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table source_fact_publication_states (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          readiness_status varchar(16) not null,
          full_publication_requested boolean not null default false,
          primary key (source_instance, fact_type)
        )
        """);
  }
}
