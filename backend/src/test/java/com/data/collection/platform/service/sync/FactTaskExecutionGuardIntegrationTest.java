package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.service.FactPublicationTransaction;
import com.data.collection.platform.service.FactTaskExecutionContext;
import com.data.collection.platform.service.FactTaskExecutionGuard;
import com.data.collection.platform.service.FactTaskLeaseLostException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 事实批次提交屏障的真实事务证据。
 *
 * <p>夹具只建立屏障依赖的三张表：父运行、事实任务与一张探针表。探针表用于证明被拒批次没有留下任何
 * 已提交写入；并发用例验证撤销执行权（{@code for update}）必须等待在途批次（{@code for key share}）。
 */
class FactTaskExecutionGuardIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private FactTaskExecutionContext context;
  private FactTaskExecutionGuard executionGuard;
  private FactPublicationTransaction publicationTransaction;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_task_execution_guard_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void resetSchema() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists fact_build_tasks");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute("drop table if exists fact_guard_probe");
    jdbcTemplate.execute(
        "create table sync_runs (id bigint primary key, run_type varchar(32) not null, "
            + "status varchar(32) not null, lease_owner varchar(128), lease_until timestamp, "
            + "cancel_requested boolean not null default false)");
    jdbcTemplate.execute(
        "create table fact_build_tasks (id bigint primary key, status varchar(32) not null, "
            + "lock_owner varchar(128), lease_until timestamp)");
    jdbcTemplate.execute("create table fact_guard_probe (id bigint primary key)");
    jdbcTemplate.update(
        "insert into sync_runs(id, run_type, status, lease_owner, lease_until, cancel_requested) "
            + "values (1, 'FACT_REFRESH', 'RUNNING', 'run-token', "
            + "clock_timestamp() + interval '1 hour', false)");
    jdbcTemplate.update(
        "insert into fact_build_tasks(id, status, lock_owner, lease_until) "
            + "values (100, 'RUNNING', 'task-token', clock_timestamp() + interval '1 hour')");

    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    context = new FactTaskExecutionContext();
    executionGuard = new FactTaskExecutionGuard(jdbcTemplate, context);
    publicationTransaction = new FactPublicationTransaction(executionGuard);
  }

  @Test
  void guarded_batch_commits_when_parent_and_task_execution_rights_hold() {
    inGuardedTransaction(() -> probeInsert(1L));

    assertThat(probeCount()).isOne();
  }

  @Test
  void guarded_batch_rolls_back_after_parent_run_requests_cancellation() {
    jdbcTemplate.update("update sync_runs set cancel_requested = true where id = 1");

    assertThatThrownBy(() -> inGuardedTransaction(() -> probeInsert(1L)))
        .isInstanceOf(FactTaskLeaseLostException.class)
        .hasMessageContaining("父事实运行");

    assertThat(probeCount()).isZero();
  }

  @Test
  void guarded_batch_rolls_back_when_parent_run_lease_expired() {
    jdbcTemplate.update(
        "update sync_runs set lease_until = clock_timestamp() - interval '1 second' where id = 1");

    assertThatThrownBy(() -> inGuardedTransaction(() -> probeInsert(1L)))
        .isInstanceOf(FactTaskLeaseLostException.class);

    assertThat(probeCount()).isZero();
  }

  @Test
  void guarded_batch_rolls_back_when_task_lease_is_taken_over() {
    jdbcTemplate.update(
        "update fact_build_tasks set lease_until = clock_timestamp() - interval '1 second' "
            + "where id = 100");

    assertThatThrownBy(() -> inGuardedTransaction(() -> probeInsert(1L)))
        .isInstanceOf(FactTaskLeaseLostException.class)
        .hasMessageContaining("任务租约");

    assertThat(probeCount()).isZero();
  }

  @Test
  void manual_publication_without_task_context_is_not_restricted() {
    jdbcTemplate.update("update sync_runs set cancel_requested = true where id = 1");
    jdbcTemplate.update(
        "update fact_build_tasks set lease_until = clock_timestamp() - interval '1 second' "
            + "where id = 100");

    publicationTransaction.execute(
        () -> {
          probeInsert(2L);
          return null;
        });

    assertThat(probeCount()).isOne();
  }

  @Test
  void revoking_execution_rights_waits_for_the_in_flight_guarded_transaction() throws Exception {
    CountDownLatch batchHoldsRights = new CountDownLatch(1);
    CountDownLatch releaseBatch = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> guardedBatch =
          pool.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        try (FactTaskExecutionContext.Scope ignored =
                            context.open(task("task-token"))) {
                          publicationTransaction.execute(
                              () -> {
                                try {
                                  probeInsert(1L);
                                  batchHoldsRights.countDown();
                                  awaitLatch(releaseBatch);
                                } catch (InterruptedException error) {
                                  Thread.currentThread().interrupt();
                                  throw new IllegalStateException(error);
                                }
                                return null;
                              });
                        }
                      }));
      assertThat(batchHoldsRights.await(10, TimeUnit.SECONDS)).isTrue();

      // 撤销执行权必须取 for update：在途批次持有 for key share 期间，撤销只能等待。
      Future<Long> revocation =
          pool.submit(
              () ->
                  jdbcTemplate.queryForObject(
                      "select id from sync_runs where id = 1 for update", Long.class));
      awaitBlockedLock();

      assertThat(revocation.isDone()).isFalse();
      releaseBatch.countDown();
      guardedBatch.get(10, TimeUnit.SECONDS);
      assertThat(revocation.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
      assertThat(probeCount()).isOne();
    } finally {
      releaseBatch.countDown();
      pool.shutdownNow();
    }
  }

  private void inGuardedTransaction(Runnable action) {
    transactionTemplate.executeWithoutResult(
        status -> {
          try (FactTaskExecutionContext.Scope ignored = context.open(task("task-token"))) {
            publicationTransaction.execute(
                () -> {
                  action.run();
                  return null;
                });
          }
        });
  }

  private void probeInsert(long id) {
    jdbcTemplate.update("insert into fact_guard_probe(id) values (?)", id);
  }

  private int probeCount() {
    return jdbcTemplate.queryForObject("select count(*) from fact_guard_probe", Integer.class);
  }

  private QueuedFactBuildTask task(String taskLeaseToken) {
    return new QueuedFactBuildTask(
        100L,
        1L,
        "run-token",
        300L,
        "alpha",
        "ISSUE",
        "alpha:issue",
        false,
        0,
        3,
        taskLeaseToken,
        LocalDateTime.now().plusMinutes(30));
  }

  /** 观察真实锁等待：撤销连接在批次持有共享行锁期间必须出现在 pg_stat_activity 的等待队列中。 */
  private void awaitBlockedLock() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(
              "select count(*) from pg_stat_activity "
                  + "where datname = current_database() and pid <> pg_backend_pid() "
                  + "and wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("撤销执行权的连接未出现在锁等待队列中");
  }

  private void awaitLatch(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("批次释放屏障超时");
    }
  }
}
