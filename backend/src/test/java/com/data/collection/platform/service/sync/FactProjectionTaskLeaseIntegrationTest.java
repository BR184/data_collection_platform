package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.service.FactProjectionExecutionContext;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.FactProjectionTaskService;
import com.data.collection.platform.service.ProjectionTaskLeaseLostException;
import com.data.collection.platform.service.ProjectionTaskSupersededException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class FactProjectionTaskLeaseIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private FactProjectionTaskService taskService;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_projection_task_lease_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void resetSchema() {
    jdbcTemplate = new JdbcTemplate(database.dataSource());
    jdbcTemplate.execute("drop table if exists snapshot_writes");
    jdbcTemplate.execute("drop table if exists fact_projection_refresh_tasks");
    jdbcTemplate.execute("drop table if exists fact_projection_generations");
    jdbcTemplate.execute("drop table if exists fact_build_tasks");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute(
        "create table sync_runs (id bigint primary key, status varchar(32) not null, "
            + "lease_owner varchar(128), lease_until timestamp)");
    jdbcTemplate.execute("create table fact_build_tasks (id bigint primary key)");
    jdbcTemplate.execute(
        "create table fact_projection_generations (source_instance varchar(128) not null, "
            + "fact_type varchar(64) not null, scope_type varchar(64) not null, "
            + "scope_key varchar(512) not null, generation bigint not null, "
            + "primary key (source_instance, fact_type, scope_type, scope_key))");
    jdbcTemplate.execute(
        "create table fact_projection_refresh_tasks (id bigint primary key, fact_run_id bigint not null, "
            + "fact_build_task_id bigint not null, source_instance varchar(128) not null, "
            + "fact_type varchar(64) not null, scope_type varchar(64) not null, "
            + "scope_key varchar(512) not null, target_generation bigint not null, "
            + "status varchar(32) not null, lease_owner varchar(128), lease_until timestamp, "
            + "heartbeat_at timestamp, retry_count integer not null, max_retry_count integer not null, "
            + "recovery_count integer not null default 0, run_after timestamp not null, "
            + "error_message text, started_at timestamp, finished_at timestamp, updated_at timestamp)");
    jdbcTemplate.execute("create table snapshot_writes (id bigserial primary key)");
    taskService =
        new FactProjectionTaskService(
            jdbcTemplate, mock(SyncRunPublicationFenceService.class));
  }

  @Test
  void expired_running_task_consumes_retry_budget_and_can_be_reclaimed_with_new_identity() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "old-token", "clock_timestamp() - interval '1 second'", 0, 3);

    assertThat(taskService.recoverExpiredTasks()).isOne();
    assertThat(taskStatus()).isEqualTo("RETRY_WAITING");
    assertThat(taskRetryCount()).isOne();
    assertThat(taskRecoveryCount()).isOne();

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set run_after = clock_timestamp() - interval '1 second' where id = 1");
    QueuedFactProjectionTask claimed = taskService.claimNext(1L, "parent-token", 60);

    assertThat(claimed).isNotNull();
    assertThat(claimed.leaseToken()).startsWith("projection-").isNotEqualTo("old-token");
    assertThat(taskStatus()).isEqualTo("RUNNING");
  }

  @Test
  void concurrent_claims_grant_only_one_live_execution_identity() throws Exception {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("QUEUED", null, "clock_timestamp() + interval '1 hour'", 0, 3);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<QueuedFactProjectionTask>> attempts =
          List.of(
              pool.submit(() -> claimAfter(start, ready)),
              pool.submit(() -> claimAfter(start, ready)));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      QueuedFactProjectionTask first = attempts.getFirst().get(10, TimeUnit.SECONDS);
      QueuedFactProjectionTask second = attempts.get(1).get(10, TimeUnit.SECONDS);

      assertThat(java.util.stream.Stream.of(first, second).filter(java.util.Objects::nonNull)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void paused_parent_run_cannot_claim_its_projection_tasks() {
    insertParentRun("PAUSED", "parent-token");
    insertGeneration(1L);
    insertTask("QUEUED", null, "clock_timestamp() - interval '1 second'", 0, 3);

    assertThat(taskService.claimNext(1L, "parent-token", 60)).isNull();
    assertThat(taskStatus()).isEqualTo("QUEUED");
  }

  @Test
  void terminal_parent_run_converges_expired_task_to_failure() {
    insertParentRun("TIMEOUT", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "expired-token", "clock_timestamp() - interval '1 second'", 0, 3);

    assertThat(taskService.recoverExpiredTasks()).isOne();

    assertThat(taskStatus()).isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(
        "select error_message from fact_projection_refresh_tasks where id = 1", String.class))
        .contains("父事实运行已终止");
  }

  @Test
  void snapshot_write_requires_current_owner_and_exact_target_generation() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "current-token", "clock_timestamp() + interval '1 hour'", 0, 3);
    QueuedFactProjectionTask currentTask =
        new QueuedFactProjectionTask(
            1L,
            1L,
            2L,
            scope(),
            1L,
            0,
            3,
            "current-token",
            null);
    FactProjectionExecutionContext executionContext = new FactProjectionExecutionContext();
    FactProjectionPublicationGuardService guard =
        new FactProjectionPublicationGuardService(
            jdbcTemplate,
            executionContext,
            new DataSourceTransactionManager(database.dataSource()));

    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(currentTask)) {
      guard.writeSnapshot(() -> jdbcTemplate.update("insert into snapshot_writes default values"));
    }
    assertThat(snapshotWriteCount()).isOne();

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set lease_owner = 'replacement-token' where id = 1");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(currentTask)) {
      assertThatThrownBy(
              () -> guard.writeSnapshot(() -> jdbcTemplate.update("insert into snapshot_writes default values")))
          .isInstanceOf(ProjectionTaskLeaseLostException.class);
    }
    assertThat(snapshotWriteCount()).isOne();

    jdbcTemplate.update("update fact_projection_generations set generation = 2 where scope_key = 'scope-1'");
    QueuedFactProjectionTask replacementTask =
        new QueuedFactProjectionTask(
            1L,
            1L,
            2L,
            scope(),
            1L,
            0,
            3,
            "replacement-token",
            null);
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(replacementTask)) {
      assertThatThrownBy(
              () -> guard.writeSnapshot(() -> jdbcTemplate.update("insert into snapshot_writes default values")))
          .isInstanceOf(ProjectionTaskSupersededException.class);
    }
    assertThat(snapshotWriteCount()).isOne();
  }

  private QueuedFactProjectionTask claimAfter(CountDownLatch start, CountDownLatch ready)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("并发领取屏障超时");
    }
    return taskService.claimNext(1L, "parent-token", 60);
  }

  private void insertParentRun(String status, String leaseToken) {
    jdbcTemplate.update(
        "insert into sync_runs values (1, ?, ?, clock_timestamp() + interval '1 hour')",
        status,
        leaseToken);
    jdbcTemplate.update("insert into fact_build_tasks values (2)");
  }

  private void insertGeneration(long generation) {
    jdbcTemplate.update(
        "insert into fact_projection_generations values ('alpha', 'ISSUE', 'PROJECT', 'scope-1', ?)",
        generation);
  }

  private void insertTask(
      String status, String leaseToken, String leaseUntilSql, int retryCount, int maxRetryCount) {
    jdbcTemplate.update(
        "insert into fact_projection_refresh_tasks(id, fact_run_id, fact_build_task_id, "
            + "source_instance, fact_type, scope_type, scope_key, target_generation, status, "
            + "lease_owner, lease_until, heartbeat_at, retry_count, max_retry_count, recovery_count, "
            + "run_after, updated_at) values (1, 1, 2, 'alpha', 'ISSUE', 'PROJECT', 'scope-1', "
            + "1, ?, ?, " + leaseUntilSql + ", current_timestamp, ?, ?, 0, current_timestamp, current_timestamp)",
        status,
        leaseToken,
        retryCount,
        maxRetryCount);
  }

  private FactProjectionScope scope() {
    return new FactProjectionScope("alpha", FactType.ISSUE, ProjectionScopeType.PROJECT, "scope-1");
  }

  private String taskStatus() {
    return jdbcTemplate.queryForObject(
        "select status from fact_projection_refresh_tasks where id = 1", String.class);
  }

  private int taskRetryCount() {
    return jdbcTemplate.queryForObject(
        "select retry_count from fact_projection_refresh_tasks where id = 1", Integer.class);
  }

  private int taskRecoveryCount() {
    return jdbcTemplate.queryForObject(
        "select recovery_count from fact_projection_refresh_tasks where id = 1", Integer.class);
  }

  private int snapshotWriteCount() {
    return jdbcTemplate.queryForObject("select count(*) from snapshot_writes", Integer.class);
  }
}
