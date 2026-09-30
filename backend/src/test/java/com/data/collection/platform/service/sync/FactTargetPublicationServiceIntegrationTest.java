package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactProjectionGenerationService;
import com.data.collection.platform.service.FactProjectionScopeResolver;
import com.data.collection.platform.service.FactPublicationTransaction;
import com.data.collection.platform.service.FactTargetPublicationService;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FactTargetPublicationServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private FactBuildTaskService taskService;
  private SyncRunEventRecorder eventRecorder;
  private SyncFactPublicationStateService publicationStateService;
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
    publicationStateService = mock(SyncFactPublicationStateService.class);
    service =
        new FactTargetPublicationService(
            jdbcTemplate,
            new FactProjectionScopeResolver(jdbcTemplate),
            new FactProjectionGenerationService(jdbcTemplate),
            mock(SyncRunPublicationFenceService.class),
            publicationStateService,
            new FactPublicationTransaction(),
            taskService,
            eventRecorder,
            new GitlabMirrorProperties());
  }

  @Test
  void test_assigned_root_publishes_current_fact_and_advances_version_fence() {
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
                """
                select published_version
                  from fact_change_heads
                 where root_id = 501
                """,
                Long.class))
        .isEqualTo(12L);
    assertThat(publishedByTaskId(501L)).isEqualTo(100L);
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
  void test_already_published_fence_is_not_rebuilt_again() {
    insertPublicationState("worker-a");
    jdbcTemplate.update(
        "update fact_change_heads set published_version = latest_change_version where root_id = 501");

    FactBuildResponse response =
        transactionTemplate.execute(
            status ->
                service.publish(
                    task("worker-a"),
                    rootIds -> {
                      throw new AssertionError("版本栅栏已收敛时不得重新构建事实");
                    }));

    assertThat(response.affectedRows()).isZero();
    assertThat(response.message()).contains("事实目标已由当前版本覆盖");
    assertThat(publishedByTaskId(501L)).isNull();
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
                """
                select published_version
                  from fact_change_heads
                 where root_id = 501
                """,
                Long.class))
        .isZero();
    assertThat(publishedByTaskId(501L)).isNull();
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
  void test_concurrent_publishers_on_same_root_one_advances_and_one_yields() throws Exception {
    insertPublicationState("worker-a");
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(
            id, status, lock_owner, affected_rows, updated_at)
        values (101, 'RUNNING', 'worker-b', 0, current_timestamp)
        """);
    jdbcTemplate.update(
        """
        insert into fact_build_task_roots(task_id, source_instance, fact_type, root_id)
        values (101, 'alpha', 'ISSUE', 501)
        """);

    CountDownLatch firstLocked = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<FactBuildResponse> first =
          pool.submit(
              () ->
                  transactionTemplate.execute(
                      status ->
                          service.publish(
                              task("worker-a"),
                              rootIds -> {
                                assertThat(rootIds).containsExactly(501L);
                                firstLocked.countDown();
                                awaitLatch(releaseFirst);
                                jdbcTemplate.update(
                                    "update issue_fact set project_id = 99 where issue_id = 501");
                                return new FactBuildResponse(
                                    "issue-target-batch", false, 1, "published");
                              })));

      assertThat(firstLocked.await(10, TimeUnit.SECONDS))
          .as("先到的发布者必须在等待期间持根行锁")
          .isTrue();
      Future<FactBuildResponse> second =
          pool.submit(
              () ->
                  transactionTemplate.execute(
                      status ->
                          service.publish(
                              taskOf(101L, "worker-b"),
                              rootIds -> {
                                throw new AssertionError("后到的发布者必须在先提交者让位后按已覆盖完成");
                              })));

      // 根行锁必须挡住后到的发布者：先提交者让位之前，第二个事务不能完成。
      Thread.sleep(500);
      assertThat(second.isDone()).as("后到的发布者必须先被根行锁挡住").isFalse();
      releaseFirst.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS).affectedRows()).isOne();
      FactBuildResponse secondResponse = second.get(10, TimeUnit.SECONDS);
      assertThat(secondResponse.affectedRows()).isZero();
      assertThat(secondResponse.message()).contains("已由当前版本覆盖");
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(
            jdbcTemplate.queryForObject(
                """
                select published_version
                  from fact_change_heads
                 where root_id = 501
                """,
                Long.class))
        .isEqualTo(12L);
    assertThat(publishedByTaskId(501L)).isEqualTo(100L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from fact_build_tasks where id = 101", String.class))
        .isEqualTo("SUCCESS");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("等待并发发布协调信号超时");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待并发发布协调信号被中断", error);
    }
  }

  @Test
  void test_full_publication_commits_build_then_settles_epoch_and_task() {
    insertFullPublicationState("worker-a");
    when(taskService.renewTaskLease(any(), anyInt())).thenReturn(true);
    when(publicationStateService.publicationUpperBound("alpha", FactType.ISSUE)).thenReturn(12L);

    FactBuildResponse response =
        service.publishFull(
            fullTask("worker-a"),
            progress -> {
              // 覆盖版本必须在构建之前冻结：构建期间新登记的更高版本不得被本次全量结算吞掉。
              verify(publicationStateService).publicationUpperBound("alpha", FactType.ISSUE);
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
    InOrder order = inOrder(publicationStateService);
    order.verify(publicationStateService).publicationUpperBound("alpha", FactType.ISSUE);
    order
        .verify(publicationStateService)
        .settleAfterFullPublication("alpha", FactType.ISSUE, 100L, 12L);
    verify(eventRecorder)
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
    verify(publicationStateService, org.mockito.Mockito.never())
        .settleAfterFullPublication(any(), any(), any(), anyLong());
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_projection_refresh_tasks", Integer.class))
        .isZero();
  }

  private Long publishedByTaskId(long rootId) {
    return jdbcTemplate.queryForObject(
        "select published_by_fact_build_task_id from fact_change_heads where root_id = ?",
        Long.class,
        rootId);
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
            source_instance, fact_type, root_id, project_id,
            latest_change_version, published_version)
        values ('alpha', 'ISSUE', 501, 42, 12, 0)
        """);
    jdbcTemplate.update(
        """
        insert into fact_build_task_roots(task_id, source_instance, fact_type, root_id)
        values (100, 'alpha', 'ISSUE', 501)
        """);
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_instance, issue_id, project_id, testing_phase, milestone_title)
        values ('alpha', 501, 42, null, null)
        """);
  }

  private QueuedFactBuildTask task(String owner) {
    return taskOf(100L, owner);
  }

  private QueuedFactBuildTask taskOf(long taskId, String owner) {
    return new QueuedFactBuildTask(
        taskId,
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
    jdbcTemplate.execute("drop table if exists fact_build_task_roots cascade");
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
          project_id bigint,
          latest_change_version bigint not null,
          published_version bigint not null,
          published_by_fact_build_task_id bigint,
          updated_at timestamp not null default current_timestamp,
          primary key (source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table fact_build_task_roots (
          task_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          primary key (task_id, root_id)
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
