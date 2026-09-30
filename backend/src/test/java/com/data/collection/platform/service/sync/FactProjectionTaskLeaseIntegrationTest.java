package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.service.FactProjectionExecutionContext;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.FactProjectionTaskService;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.IssueProjectionScopeResolver;
import com.data.collection.platform.service.PageRecordSnapshotService;
import com.data.collection.platform.service.ProjectionTaskLeaseLostException;
import com.data.collection.platform.service.ProjectionTaskSupersededException;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 投影发布栅栏的真实事务证据。
 *
 * <p>夹具必须持有唯一 DataSource：事务管理器、guard 与全部快照服务共享同一实例，真快照写入才会落在发布事务内。
 * 每个连接由 DriverManagerDataSource 独立取得，因此并发场景天然使用两条真实连接；快照断言直接读取生产
 * {@code page_record_snapshots} 与 {@code statistic_board_snapshots} 两张真实存储表，不使用合成替代表。
 */
class FactProjectionTaskLeaseIntegrationTest {
  private static PostgresIntegrationTestDatabase database;
  private static DataSource dataSource;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private FactProjectionExecutionContext executionContext;
  private FactProjectionPublicationGuardService publicationGuardService;
  private PageRecordSnapshotService pageRecordSnapshotService;
  private StatisticBoardSnapshotService statisticBoardSnapshotService;
  private FactProjectionTaskService taskService;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_projection_task_lease_test");
    dataSource = database.dataSource();
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void resetSchema() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists page_record_snapshots");
    jdbcTemplate.execute("drop table if exists statistic_board_snapshots");
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
    // 与 V20260703_05、V20260803_01 保持一致的真实页面快照存储。
    jdbcTemplate.execute(
        "create table page_record_snapshots ("
            + "id bigserial primary key, page_key varchar(128) not null, snapshot_type varchar(64) not null, "
            + "scope_key varchar(512) not null, rule_version varchar(128) not null, source_version text, "
            + "request_hash varchar(64) not null, request_payload jsonb not null default '{}'::jsonb, "
            + "response_payload jsonb not null default '{}'::jsonb, status varchar(32) not null default 'READY', "
            + "error_message text, generated_at timestamp not null default current_timestamp, "
            + "refreshed_at timestamp not null default current_timestamp, "
            + "created_at timestamp not null default current_timestamp, "
            + "updated_at timestamp not null default current_timestamp, "
            + "constraint uk_page_record_snapshots_scope "
            + "unique (page_key, snapshot_type, scope_key, rule_version, request_hash))");
    // 与 V20260629_02、V20260629_04 保持一致的真实统计看板快照存储。
    jdbcTemplate.execute(
        "create table statistic_board_snapshots ("
            + "id bigserial primary key, board_key varchar(128) not null, scope_key varchar(512) not null, "
            + "rule_version varchar(128) not null, source_version varchar(128), filter_hash varchar(64) not null, "
            + "filter_payload jsonb not null default '{}'::jsonb, "
            + "applied_filter_payload jsonb not null default '{}'::jsonb, "
            + "row_payload jsonb not null default '[]'::jsonb, "
            + "meta_payload jsonb not null default '{}'::jsonb, definition_payload jsonb, "
            + "status varchar(32) not null default 'READY', error_message text, "
            + "generated_at timestamp not null default current_timestamp, "
            + "refreshed_at timestamp not null default current_timestamp, "
            + "created_at timestamp not null default current_timestamp, "
            + "updated_at timestamp not null default current_timestamp, "
            + "constraint uk_statistic_board_snapshots_scope "
            + "unique (board_key, scope_key, rule_version, filter_hash))");

    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    executionContext = new FactProjectionExecutionContext();
    publicationGuardService =
        new FactProjectionPublicationGuardService(jdbcTemplate, executionContext, transactionManager);
    JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
    pageRecordSnapshotService =
        new PageRecordSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(IssueProjectionScopeResolver.class),
            publicationGuardService);
    statisticBoardSnapshotService =
        new StatisticBoardSnapshotService(
            jdbcTemplate,
            jsonUtils,
            mock(FactProjectionVersionService.class),
            mock(SyncFactPublicationStateService.class),
            publicationGuardService,
            transactionManager);
    taskService = new FactProjectionTaskService(jdbcTemplate, mock(SyncRunPublicationFenceService.class));
  }

  @Test
  void expired_running_task_consumes_retry_budget_and_can_be_reclaimed_with_new_identity() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "old-token", "clock_timestamp() - interval '1 second'", 0, 3);

    assertThat(inTransaction(() -> taskService.recoverExpiredTasks())).isOne();
    assertThat(taskStatus()).isEqualTo("RETRY_WAITING");
    assertThat(taskRetryCount()).isOne();
    assertThat(taskRecoveryCount()).isOne();

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set run_after = clock_timestamp() - interval '1 second' where id = 1");
    QueuedFactProjectionTask claimed = inTransaction(() -> taskService.claimNext(1L, "parent-token", 60));

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

    assertThat(inTransaction(() -> taskService.claimNext(1L, "parent-token", 60))).isNull();
    assertThat(taskStatus()).isEqualTo("QUEUED");
  }

  @Test
  void terminal_parent_run_converges_expired_task_to_failure() {
    insertParentRun("TIMEOUT", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "expired-token", "clock_timestamp() - interval '1 second'", 0, 3);

    assertThat(inTransaction(() -> taskService.recoverExpiredTasks())).isOne();

    assertThat(taskStatus()).isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(
        "select error_message from fact_projection_refresh_tasks where id = 1", String.class))
        .contains("父事实运行已终止");
  }

  @Test
  void publication_guard_commits_real_page_and_board_snapshot_writes_of_the_current_owner() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "current-token", "clock_timestamp() + interval '1 hour'", 0, 3);

    try (FactProjectionExecutionContext.Scope ignored =
        executionContext.open(runningTask("current-token", 1L))) {
      publicationGuardService.writeSnapshot(
          () -> pageRecordSnapshotService.save(recordRequest("prewarm-page"), Map.of("ok", true)));
      publicationGuardService.writeSnapshot(
          () ->
              statisticBoardSnapshotService.save(
                  boardRequest("board-1"), "source-v1", boardResponse()));
    }

    assertThat(pageSnapshotStatus("prewarm-page")).isEqualTo("READY");
    assertThat(boardSnapshotCount()).isOne();
  }

  @Test
  void interrupted_prewarm_rolls_back_real_snapshot_writes_of_both_stores() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "current-token", "clock_timestamp() + interval '1 hour'", 0, 3);

    try (FactProjectionExecutionContext.Scope ignored =
        executionContext.open(runningTask("current-token", 1L))) {
      // 预热先成功写入页面快照，随后统计看板写入撞上真实列宽约束：同一发布事务必须整体回滚。
      assertThatThrownBy(
              () ->
                  publicationGuardService.writeSnapshot(
                      () -> {
                        pageRecordSnapshotService.save(recordRequest("prewarm-page"), Map.of("ok", true));
                        statisticBoardSnapshotService.save(
                            boardRequest("x".repeat(200)), "source-v1", boardResponse());
                      }))
          .isInstanceOf(DataAccessException.class);
    }

    assertThat(pageSnapshotCount()).isZero();
    assertThat(boardSnapshotCount()).isZero();
  }

  @Test
  void snapshot_write_requires_current_owner_and_exact_target_generation() {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "current-token", "clock_timestamp() + interval '1 hour'", 0, 3);

    try (FactProjectionExecutionContext.Scope ignored =
        executionContext.open(runningTask("current-token", 1L))) {
      publicationGuardService.writeSnapshot(
          () ->
              statisticBoardSnapshotService.save(
                  boardRequest("board-1"), "source-v1", boardResponse()));
    }
    assertThat(boardSnapshotCount()).isOne();

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set lease_owner = 'replacement-token' where id = 1");
    try (FactProjectionExecutionContext.Scope ignored =
        executionContext.open(runningTask("current-token", 1L))) {
      assertThatThrownBy(
              () ->
                  publicationGuardService.writeSnapshot(
                      () ->
                          statisticBoardSnapshotService.save(
                              boardRequest("board-late"), "source-v1", boardResponse())))
          .isInstanceOf(ProjectionTaskLeaseLostException.class);
    }
    assertThat(boardSnapshotCount()).isOne();

    jdbcTemplate.update("update fact_projection_generations set generation = 2 where scope_key = 'scope-1'");
    try (FactProjectionExecutionContext.Scope ignored =
        executionContext.open(runningTask("replacement-token", 1L))) {
      assertThatThrownBy(
              () ->
                  publicationGuardService.writeSnapshot(
                      () ->
                          statisticBoardSnapshotService.save(
                              boardRequest("board-late"), "source-v1", boardResponse())))
          .isInstanceOf(ProjectionTaskSupersededException.class);
    }
    assertThat(boardSnapshotCount()).isOne();
  }

  @Test
  void higher_generation_publication_waits_for_the_in_flight_publication_and_rejects_the_late_owner()
      throws Exception {
    insertParentRun("RUNNING", "parent-token");
    insertGeneration(1L);
    insertTask("RUNNING", "owner-token", "clock_timestamp() + interval '1 hour'", 0, 3);
    QueuedFactProjectionTask owner = runningTask("owner-token", 1L);

    CountDownLatch ownerInsideWrite = new CountDownLatch(1);
    CountDownLatch releaseOwner = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> ownerPublication =
          pool.submit(
              () -> {
                try (FactProjectionExecutionContext.Scope ignored = executionContext.open(owner)) {
                  publicationGuardService.writeSnapshot(
                      () -> {
                        statisticBoardSnapshotService.save(
                            boardRequest("board-1"), "source-v1", boardResponse());
                        ownerInsideWrite.countDown();
                        awaitLatch(releaseOwner);
                      });
                }
              });
      assertThat(ownerInsideWrite.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Integer> higherGenerationPublication =
          pool.submit(
              () ->
                  jdbcTemplate.update(
                      "update fact_projection_generations set generation = 2 where scope_key = 'scope-1'"));
      awaitBlockedRegistration();

      assertThat(higherGenerationPublication.isDone()).isFalse();
      releaseOwner.countDown();
      ownerPublication.get(10, TimeUnit.SECONDS);
      assertThat(higherGenerationPublication.get(10, TimeUnit.SECONDS)).isOne();

      assertThat(currentGeneration()).isEqualTo(2);
      assertThat(boardSnapshotCount()).isOne();

      // 调度器回收过期租约并重新领取：同一任务获得新执行身份。
      jdbcTemplate.update(
          "update fact_projection_refresh_tasks set lease_until = clock_timestamp() - interval '1 second' where id = 1");
      assertThat(inTransaction(() -> taskService.recoverExpiredTasks())).isOne();
      jdbcTemplate.update(
          "update fact_projection_refresh_tasks set run_after = clock_timestamp() - interval '1 second' where id = 1");
      QueuedFactProjectionTask reclaimed = inTransaction(() -> taskService.claimNext(1L, "parent-token", 60));
      assertThat(reclaimed).isNotNull();
      assertThat(reclaimed.leaseToken()).isNotEqualTo("owner-token");

      // 旧 owner 的迟到写入必须被拒，并且不得留下任何新快照行。
      try (FactProjectionExecutionContext.Scope ignored = executionContext.open(owner)) {
        assertThatThrownBy(
                () ->
                    publicationGuardService.writeSnapshot(
                        () ->
                            statisticBoardSnapshotService.save(
                                boardRequest("board-late"), "source-v1", boardResponse())))
            .isInstanceOf(ProjectionTaskSupersededException.class);
      }
      assertThat(boardSnapshotCount()).isOne();
    } finally {
      releaseOwner.countDown();
      pool.shutdownNow();
    }
  }

  private <T> T inTransaction(Supplier<T> action) {
    return transactionTemplate.execute(status -> action.get());
  }

  private QueuedFactProjectionTask claimAfter(CountDownLatch start, CountDownLatch ready)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("并发领取屏障超时");
    }
    return inTransaction(() -> taskService.claimNext(1L, "parent-token", 60));
  }

  /** 观察真实锁等待：另一条连接在发布事务持锁期间必须出现在 pg_stat_activity 的等待队列中。 */
  private void awaitBlockedRegistration() throws InterruptedException {
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
    throw new AssertionError("提升 generation 的连接未出现在锁等待队列中");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("发布事务释放屏障超时");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("发布事务等待被中断", error);
    }
  }

  private QueuedFactProjectionTask runningTask(String leaseToken, long targetGeneration) {
    return new QueuedFactProjectionTask(
        1L, 1L, 2L, scope(), targetGeneration, 0, 3, leaseToken, null);
  }

  private PageRecordSnapshotService.SnapshotRequest recordRequest(String pageKey) {
    return new PageRecordSnapshotService.SnapshotRequest(
        pageKey,
        PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
        "scope-1",
        "rule-1",
        () -> "source-v1",
        Map.of("page", 1));
  }

  private StatisticBoardSnapshotService.SnapshotRequest boardRequest(String boardKey) {
    return new StatisticBoardSnapshotService.SnapshotRequest(
        boardKey,
        "scope-1",
        "rule-1",
        StatisticBoardSnapshotService.SourceReadPlan.of(() -> Set.of(scope())),
        Map.of("stage", "system-test"),
        boardDefinition(),
        null);
  }

  private StatisticBoardResponse boardResponse() {
    return new StatisticBoardResponse(
        boardDefinition(),
        Map.of(),
        null,
        List.of(),
        new StatisticBoardMeta(LocalDateTime.now(), 1L, 0, 0, 0),
        null,
        null);
  }

  private StatisticBoardDefinition boardDefinition() {
    return new StatisticBoardDefinition(
        "board-1", "标题", "说明", "查询", "查询说明", "行", List.of(), List.of(), List.of(), 20, "无数据");
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

  private long currentGeneration() {
    return jdbcTemplate.queryForObject(
        "select generation from fact_projection_generations where scope_key = 'scope-1'", Long.class);
  }

  private String pageSnapshotStatus(String pageKey) {
    return jdbcTemplate.queryForObject(
        "select status from page_record_snapshots where page_key = ?", String.class, pageKey);
  }

  private int pageSnapshotCount() {
    return jdbcTemplate.queryForObject("select count(*) from page_record_snapshots", Integer.class);
  }

  private int boardSnapshotCount() {
    return jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots", Integer.class);
  }
}
