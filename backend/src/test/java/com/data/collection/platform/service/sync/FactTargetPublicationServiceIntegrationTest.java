package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactPublicationTransaction;
import com.data.collection.platform.service.FactProjectionGenerationService;
import com.data.collection.platform.service.FactProjectionScopeResolver;
import com.data.collection.platform.service.FactTargetPublicationService;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FactTargetPublicationServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private FactBuildTaskService taskService;
  private SyncRunEventRecorder eventRecorder;
  private FactTargetPublicationService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_target_publication_test");
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
    dropSchema();
    createSchema();
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    taskService = mock(FactBuildTaskService.class);
    eventRecorder = mock(SyncRunEventRecorder.class);
    service =
        new FactTargetPublicationService(
            jdbcTemplate,
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate),
            mock(SyncRunPublicationFenceService.class),
            mock(SyncFactPublicationStateService.class),
            new FactPublicationTransaction(),
            taskService,
            eventRecorder,
            new GitlabMirrorProperties());
  }

  @Test
  void test_newer_head_exists_old_assignment_publishes_current_fact_and_covers_both_runs() {
    insertPublicationState("worker-a");

    FactBuildResponse response =
        transactionTemplate.execute(
            status ->
                service.publish(
                    task("worker-a"),
                    rootIds -> {
                      assertThat(rootIds).containsExactly(501L);
                      jdbcTemplate.update(
                          "update issue_fact set project_id = 99 where issue_id = 501");
                      return new FactBuildResponse("issue-target-batch", false, 1, "published");
                    }));

    assertThat(response).isNotNull();
    assertThat(response.affectedRows()).isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "select project_id from issue_fact where issue_id = 501", Long.class))
        .isEqualTo(99L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select published_version from fact_change_heads where root_id = 501",
                Long.class))
        .isEqualTo(12L);
    assertThat(
            jdbcTemplate.queryForList(
                "select publication_status from sync_run_fact_targets order by mirror_run_id",
                String.class))
        .containsExactly("PUBLISHED", "PUBLISHED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from fact_build_tasks where id = 100", String.class))
        .isEqualTo("SUCCESS");
    assertThat(
            jdbcTemplate.queryForList(
                """
                select scope_type || ':' || scope_key
                  from fact_projection_refresh_tasks
                 order by scope_type, scope_key
                """,
                String.class))
        .containsExactly("GLOBAL_VIEW:*", "PROJECT:42", "PROJECT:99");
  }

  @Test
  void test_fact_task_lease_lost_rolls_back_fact_generation_and_publication() {
    insertPublicationState("new-owner");

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status ->
                        service.publish(
                            task("expired-owner"),
                            rootIds -> {
                              jdbcTemplate.update(
                                  "update issue_fact set project_id = 99 where issue_id = 501");
                              return new FactBuildResponse(
                                  "issue-target-batch", false, 1, "published");
                            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("事实任务租约已失效：100");

    assertThat(
            jdbcTemplate.queryForObject(
                "select project_id from issue_fact where issue_id = 501", Long.class))
        .isEqualTo(42L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select published_version from fact_change_heads where root_id = 501",
                Long.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForList(
                "select publication_status from sync_run_fact_targets order by mirror_run_id",
                String.class))
        .containsExactly("QUEUED", "PENDING");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_projection_generations", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_projection_refresh_tasks", Integer.class))
        .isZero();
  }

  @Test
  void test_full_publication_commits_build_then_settles_epoch_and_task() {
    insertFullPublicationState("worker-a");
    when(taskService.renewTaskLease(any(), anyInt())).thenReturn(true);

    FactBuildResponse response =
        service.publishFull(
            fullTask("worker-a"),
            progress -> {
              jdbcTemplate.update(
                  "update issue_fact set project_id = 99 where issue_id = 501");
              progress.chunkCommitted(1, 1, 1);
              return new FactBuildResponse("issue", true, 1, "built");
            });

    assertThat(response.affectedRows()).isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "select project_id from issue_fact where issue_id = 501", Long.class))
        .isEqualTo(99L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from fact_build_tasks where id = 100", String.class))
        .isEqualTo("SUCCESS");
    assertThat(
            jdbcTemplate.queryForList(
                "select scope_type || ':' || scope_key from fact_projection_refresh_tasks",
                String.class))
        .containsExactly("FULL_EPOCH:*");
    org.mockito.Mockito.verify(eventRecorder)
        .record(
            org.mockito.ArgumentMatchers.eq(200L),
            org.mockito.ArgumentMatchers.eq(300L),
            org.mockito.ArgumentMatchers.eq("alpha"),
            org.mockito.ArgumentMatchers.eq("FACT_BUILD_COMPLETED"),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void test_full_publication_aborts_without_settlement_when_lease_stolen_mid_build() {
    insertFullPublicationState("new-owner");
    when(taskService.renewTaskLease(any(), anyInt())).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.publishFull(
                    fullTask("expired-owner"),
                    progress -> {
                      jdbcTemplate.update(
                          "update issue_fact set project_id = 99 where issue_id = 501");
                      progress.chunkCommitted(1, 1, 1);
                      return new FactBuildResponse("issue", true, 1, "built");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("事实任务租约已失效：100");

    assertThat(
            jdbcTemplate.queryForObject(
                "select project_id from issue_fact where issue_id = 501", Long.class))
        .isEqualTo(99L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from fact_build_tasks where id = 100", String.class))
        .isEqualTo("RUNNING");
    assertThat(
            jdbcTemplate.queryForList(
                "select publication_status from sync_run_fact_targets order by mirror_run_id",
                String.class))
        .containsExactly("QUEUED", "PENDING");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_projection_refresh_tasks", Integer.class))
        .isZero();
  }

  private void insertFullPublicationState(String databaseOwner) {
    insertPublicationState(databaseOwner);
    jdbcTemplate.update("update fact_build_tasks set full_build = true where id = 100");
  }

  private QueuedFactBuildTask fullTask(String owner) {
    return new QueuedFactBuildTask(
        100L,
        200L,
        300L,
        "alpha",
        "ISSUE",
        "issue",
        true,
        0,
        3,
        owner,
        LocalDateTime.now().plusMinutes(1));
  }

  private void insertPublicationState(String databaseOwner) {
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(
            id, status, lock_owner, affected_rows, updated_at)
        values (100, 'RUNNING', ?, 0, current_timestamp)
        """,
        databaseOwner);
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id,
            latest_change_version, published_version)
        values ('alpha', 'ISSUE', 501, 12, 0)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id,
            change_version, publication_status,
            assigned_fact_run_id, assigned_fact_build_task_id)
        values
          (1, 'alpha', 'ISSUE', 501, 11, 'QUEUED', 200, 100),
          (2, 'alpha', 'ISSUE', 501, 12, 'PENDING', null, null)
        """);
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_instance, issue_id, project_id, testing_phase, milestone_title)
        values ('alpha', 501, 42, null, null)
        """);
  }

  private QueuedFactBuildTask task(String owner) {
    return new QueuedFactBuildTask(
        100L,
        200L,
        300L,
        "alpha",
        "ISSUE",
        "issue-target-batch",
        false,
        0,
        3,
        owner,
        LocalDateTime.now().plusMinutes(1));
  }

  private void dropSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_events cascade");
    jdbcTemplate.execute("drop table if exists fact_projection_refresh_tasks cascade");
    jdbcTemplate.execute("drop table if exists fact_projection_generations cascade");
    jdbcTemplate.execute("drop table if exists sync_run_fact_targets cascade");
    jdbcTemplate.execute("drop table if exists fact_change_heads cascade");
    jdbcTemplate.execute("drop table if exists fact_build_tasks cascade");
    jdbcTemplate.execute("drop table if exists issue_scope_members cascade");
    jdbcTemplate.execute("drop table if exists issue_scope_groups cascade");
    jdbcTemplate.execute("drop table if exists issue_scope_catalogs cascade");
    jdbcTemplate.execute("drop table if exists issue_fact cascade");
  }

  private void createSchema() {
    jdbcTemplate.execute(
        """
        create table fact_build_tasks (
          id bigint primary key,
          status varchar(32) not null,
          lock_owner varchar(128),
          full_build boolean not null default false,
          affected_rows integer not null,
          message text,
          error_message text,
          heartbeat_at timestamp,
          lease_until timestamp,
          finished_at timestamp,
          updated_at timestamp not null
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
        create table sync_run_fact_targets (
          mirror_run_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          change_version bigint not null,
          publication_status varchar(32) not null,
          assigned_fact_run_id bigint,
          assigned_fact_build_task_id bigint,
          published_version bigint,
          published_by_fact_build_task_id bigint,
          published_at timestamp,
          updated_at timestamp not null default current_timestamp,
          primary key (mirror_run_id, source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table fact_projection_generations (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          scope_type varchar(64) not null,
          scope_key varchar(512) not null,
          generation bigint not null,
          updated_at timestamp not null,
          primary key (source_instance, fact_type, scope_type, scope_key)
        )
        """);
    jdbcTemplate.execute(
        """
        create table fact_projection_refresh_tasks (
          id bigserial primary key,
          fact_run_id bigint not null,
          fact_build_task_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          scope_type varchar(64) not null,
          scope_key varchar(512) not null,
          target_generation bigint not null,
          status varchar(32) not null,
          lease_owner varchar(128),
          lease_until timestamp,
          heartbeat_at timestamp,
          retry_count integer not null,
          max_retry_count integer not null,
          run_after timestamp not null,
          error_message text,
          finished_at timestamp,
          created_at timestamp not null,
          updated_at timestamp not null,
          unique (fact_build_task_id, scope_type, scope_key)
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_fact (
          source_instance varchar(128) not null,
          issue_id bigint not null,
          project_id bigint,
          testing_phase varchar(255),
          milestone_title varchar(255)
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_events (
          id bigserial primary key,
          run_id bigint,
          config_id bigint,
          source_instance varchar(128),
          event_type varchar(64) not null,
          message text,
          created_at timestamp not null default current_timestamp
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_scope_catalogs (
          id bigint primary key,
          project_id bigint not null,
          dimension varchar(64) not null,
          enabled boolean not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_scope_groups (
          id bigint primary key,
          catalog_id bigint not null,
          enabled boolean not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_scope_members (
          id bigint primary key,
          catalog_id bigint not null,
          group_id bigint not null,
          source_value varchar(255) not null,
          enabled boolean not null
        )
        """);
  }
}
