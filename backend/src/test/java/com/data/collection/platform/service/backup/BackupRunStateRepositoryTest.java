package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** 备份运行权与运行历史的隔离 PostgreSQL 集成验证；不会连接平台应用库。 */
class BackupRunStateRepositoryTest {
  private static final ZoneId ZONE = BackupOrchestrationService.PLATFORM_ZONE;
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");

  private static PostgreSQLContainer<?> postgres;
  private static DataSource dataSource;
  private static JdbcTemplate sharedJdbcTemplate;
  private static PlatformTransactionManager transactionManager;
  private static TransactionTemplate transactionTemplate;

  private JdbcTemplate jdbcTemplate;
  private BackupRunRepository runRepository;
  private BackupStateRepository stateRepository;

  @BeforeAll
  static void startIsolatedDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("backup_run_state_test")
            .withUsername("test")
            .withPassword("test");
    try {
      postgres.start();
    } catch (RuntimeException unavailable) {
      postgres.close();
      Assumptions.assumeTrue(false, "当前环境没有可用的隔离 PostgreSQL：" + unavailable.getMessage());
      return;
    }
    dataSource =
        new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    sharedJdbcTemplate = new JdbcTemplate(dataSource);
    transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    createSchema();
  }

  @AfterAll
  static void stopIsolatedDatabase() {
    if (postgres != null) {
      postgres.close();
    }
  }

  @BeforeEach
  void resetRows() {
    jdbcTemplate = sharedJdbcTemplate;
    runRepository = transactionalRunRepository(jdbcTemplate, transactionManager);
    stateRepository = new BackupStateRepository(jdbcTemplate, transactionTemplate);
    jdbcTemplate.update("delete from backup_runs");
    jdbcTemplate.update(
        "update backup_state set active_run_id = null, execution_token = null, execution_revoked = false, "
            + "process_id = null, process_started_at = null, lease_expires_at = null, updated_at = ? where id = 1",
        java.sql.Timestamp.from(NOW));
  }

  @Test
  void test_runLifecycle_persistsStagesAndTerminalStates() {
    long runId = runRepository.nextRunId();
    String executionToken = startRun(runId, "MANUAL", "LOCAL", NOW);
    runRepository.updateStage(runId, executionToken, BackupOrchestrationService.STAGE_DUMP, NOW);

    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("RUNNING");
      assertThat(run.stage()).isEqualTo("DUMP");
      assertThat(run.triggerType()).isEqualTo("MANUAL");
    });

    Instant finished = NOW.plusSeconds(30);
    runRepository.finishSuccess(
        runId,
        executionToken,
        "/opt/qaflex-backups/testinst/x.dump",
        "x.dump",
        123L,
        "abc",
        "16.4",
        "20260903.01",
        NOW,
        finished);
    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("SUCCESS");
      assertThat(run.fileBytes()).isEqualTo(123L);
      assertThat(run.durationMs()).isEqualTo(30_000L);
      assertThat(run.sha256()).isEqualTo("abc");
    });
    assertThat(runRepository.lastFinished()).hasValueSatisfying(run -> assertThat(run.id()).isEqualTo(runId));
    assertThat(runRepository.maxSuccessfulBytes()).isEqualTo(OptionalLong.of(123L));
    assertThat(runRepository.successfulArtifacts("LOCAL"))
        .containsExactly(new BackupRunRepository.SuccessfulArtifact(
            "/opt/qaflex-backups/testinst/x.dump", "x.dump"));
    stateRepository.release(runId, executionToken, finished);
  }

  @Test
  void test_failedCandidate_neverAppearsInSuccessfulArtifactSet() {
    long runId = runRepository.nextRunId();
    String executionToken = startRun(runId, "MANUAL", "LOCAL", NOW);
    String candidatePath = "/isolated/backups/candidate.dump";

    runRepository.recordCandidateArtifact(
        runId, executionToken, candidatePath, "candidate.dump", NOW.plusSeconds(1));
    runRepository.finishFailure(
        runId, executionToken, "upload interrupted", NOW, NOW.plusSeconds(2));

    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("FAILED");
      assertThat(run.targetPath()).isEqualTo(candidatePath);
      assertThat(run.fileName()).isEqualTo("candidate.dump");
    });
    assertThat(runRepository.successfulArtifacts("LOCAL")).isEmpty();
    stateRepository.release(runId, executionToken, NOW.plusSeconds(3));
  }

  @Test
  void test_recoveryAfterSuccess_preservesRegisteredArtifact() {
    long runId = runRepository.nextRunId();
    String executionToken = startRun(runId, "MANUAL", "LOCAL", NOW);
    String targetPath = "/opt/qaflex-backups/testinst/recovered.dump";
    runRepository.finishSuccess(
        runId,
        executionToken,
        targetPath,
        "recovered.dump",
        321L,
        "sha256",
        "16.4",
        "20260903.01",
        NOW,
        NOW.plusSeconds(10));
    jdbcTemplate.update(
        "update backup_state set lease_expires_at = ? where id = 1 and active_run_id = ?",
        java.sql.Timestamp.from(NOW.minusSeconds(1)),
        runId);

    BackupStateRepository.ExpiredExecution expired =
        stateRepository.revokeExpiredExecution(NOW).orElseThrow();
    assertThat(stateRepository.completeExpiredRecovery(expired, "recovered", NOW.plusSeconds(20)))
        .isTrue();

    assertThat(runRepository.get(runId)).hasValueSatisfying(run -> {
      assertThat(run.status()).isEqualTo("SUCCESS");
      assertThat(run.fileName()).isEqualTo("recovered.dump");
      assertThat(run.sha256()).isEqualTo("sha256");
    });
    assertThat(runRepository.successfulArtifacts("LOCAL"))
        .containsExactly(new BackupRunRepository.SuccessfulArtifact(targetPath, "recovered.dump"));
    assertThat(stateRepository.activeRunId()).isEmpty();
  }

  @Test
  void test_pgRestoreList_acceptsDumpCreatedFromIsolatedDatabase() throws Exception {
    org.testcontainers.containers.Container.ExecResult result =
        postgres.execInContainer(
            "sh",
            "-c",
            "PGPASSWORD=test pg_dump -h 127.0.0.1 -U test -d backup_run_state_test "
                + "-Fc -f /tmp/backup-run-state-test.dump && "
                + "pg_restore --list /tmp/backup-run-state-test.dump");

    assertThat(result.getExitCode()).isZero();
    assertThat(result.getStdout()).contains("; Archive created at");
  }

  @Test
  void test_scheduleDueCheck_respectsDayBoundsInPlatformZone() {
    long runId = runRepository.nextRunId();
    String executionToken = startRun(
        runId, "SCHEDULE", "LOCAL", Instant.parse("2026-09-09T19:30:00Z"));

    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 10), ZONE)).isTrue();
    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 9), ZONE)).isFalse();
    assertThat(runRepository.existsScheduleTriggeredOn(LocalDate.of(2026, 9, 11), ZONE)).isFalse();
    stateRepository.release(runId, executionToken, NOW);
  }

  @Test
  void test_stateRunGuard_acquireHeartbeatReleaseRoundTrip() {
    Instant leaseUntil = NOW.plusSeconds(300);
    String token = UUID.randomUUID().toString();
    assertThat(stateRepository.tryStartRun(101L, token, leaseUntil, NOW)).isTrue();
    assertThat(stateRepository.activeRunId()).isEqualTo(Optional.of(101L));
    assertThat(stateRepository.tryStartRun(102L, UUID.randomUUID().toString(), leaseUntil, NOW)).isFalse();

    stateRepository.heartbeat(101L, token, NOW.plusSeconds(600), NOW);
    assertThat(stateRepository.revokeExpiredExecution(NOW)).isEmpty();

    stateRepository.release(101L, token, NOW);
    assertThat(stateRepository.activeRunId()).isEmpty();
    String nextToken = UUID.randomUUID().toString();
    assertThat(stateRepository.tryStartRun(102L, nextToken, leaseUntil, NOW)).isTrue();
    stateRepository.release(102L, nextToken, NOW);
  }

  @Test
  void test_stateRunGuard_expiredLease_releasesOnlyAfterStopConfirmation() {
    Instant expiredLease = NOW.minusSeconds(1);
    String token = UUID.randomUUID().toString();
    assertThat(stateRepository.tryStartRun(201L, token, expiredLease, NOW.minusSeconds(600))).isTrue();

    BackupStateRepository.ExpiredExecution execution =
        stateRepository.revokeExpiredExecution(NOW).orElseThrow();
    assertThat(execution.runId()).isEqualTo(201L);
    assertThat(stateRepository.activeRunId()).isEqualTo(Optional.of(201L));
    assertThat(stateRepository.completeExpiredRecovery(execution, "stopped", NOW)).isTrue();
    assertThat(stateRepository.activeRunId()).isEmpty();
  }

  @Test
  void test_historyPagination_ordersByStartedAtDesc() {
    long first = runRepository.nextRunId();
    String firstToken = startRun(first, "MANUAL", "LOCAL", NOW);
    stateRepository.release(first, firstToken, NOW);
    long second = runRepository.nextRunId();
    String secondToken = startRun(second, "MANUAL", "LOCAL", NOW.plusSeconds(10));

    assertThat(runRepository.page(1, 10).stream().map(BackupRun::id).toList())
        .containsExactly(second, first);
    assertThat(runRepository.count()).isEqualTo(2);
    assertThat(runRepository.page(2, 1).stream().map(BackupRun::id).toList()).containsExactly(first);
    assertThat(runRepository.page(2, 2)).isEmpty();
    stateRepository.release(second, secondToken, NOW.plusSeconds(10));
  }

  @Test
  void test_successCommit_whenRecoveryRevokesUnderLock_rejectsLateWriter() throws Exception {
    long runId = runRepository.nextRunId();
    String executionToken = startRun(runId, "MANUAL", "LOCAL", NOW);
    CountDownLatch revocationLocked = new CountDownLatch(1);
    CountDownLatch releaseRevocation = new CountDownLatch(1);
    CountDownLatch commitAttemptStarted = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> recovery = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.update(
            "update backup_state set execution_revoked = true where id = 1 and active_run_id = ? "
                + "and execution_token = ?",
            runId,
            executionToken);
        revocationLocked.countDown();
        await(releaseRevocation);
      }));
      assertThat(revocationLocked.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Boolean> lateCommit = pool.submit(() -> {
        commitAttemptStarted.countDown();
        try {
          runRepository.finishSuccess(
              runId,
              executionToken,
              "/isolated/backup.dump",
              "backup.dump",
              10L,
              "abc",
              "16.4",
              "20260903.01",
              NOW,
              NOW.plusSeconds(20));
          return true;
        } catch (BackupLeaseLostException lost) {
          return false;
        }
      });
      assertThat(commitAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseLockWait(jdbcTemplate, lateCommit);
      releaseRevocation.countDown();
      recovery.get(5, TimeUnit.SECONDS);

      assertThat(lateCommit.get(5, TimeUnit.SECONDS)).isFalse();
      assertThat(runRepository.get(runId)).hasValueSatisfying(run -> assertThat(run.status()).isEqualTo("RUNNING"));
      BackupStateRepository.ExpiredExecution expired =
          new BackupStateRepository.ExpiredExecution(runId, executionToken, null, null);
      assertThat(stateRepository.completeExpiredRecovery(expired, "revoked", NOW.plusSeconds(30))).isTrue();
    } finally {
      releaseRevocation.countDown();
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private String startRun(long runId, String triggerType, String storageMode, Instant startedAt) {
    String executionToken = UUID.randomUUID().toString();
    assertThat(stateRepository.tryStartRun(
        runId, executionToken, startedAt.plusSeconds(300), startedAt)).isTrue();
    runRepository.insertRunning(runId, executionToken, triggerType, storageMode, startedAt);
    return executionToken;
  }

  private static BackupRunRepository transactionalRunRepository(
      JdbcTemplate jdbcTemplate, PlatformTransactionManager manager) {
    ProxyFactory factory = new ProxyFactory(new BackupRunRepository(jdbcTemplate));
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (BackupRunRepository) factory.getProxy();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("并发数据库事务同步超时");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("并发数据库事务被中断", interrupted);
    }
  }

  private static void awaitDatabaseLockWait(JdbcTemplate jdbcTemplate, Future<Boolean> commit)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline && !commit.isDone()) {
      Integer waits =
          jdbcTemplate.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                  + "and query like 'select id from backup_state%'",
              Integer.class);
      if (waits != null && waits > 0) {
        return;
      }
      Thread.sleep(10L);
    }
    assertThat(commit.isDone()).as("提交会话应等待恢复事务持有的 backup_state 行锁").isFalse();
  }

  private static void createSchema() {
    sharedJdbcTemplate.execute("create sequence backup_runs_id_seq");
    sharedJdbcTemplate.execute(
        "create table backup_state (id integer primary key, active_run_id bigint, "
            + "execution_token varchar(64), execution_revoked boolean not null default false, "
            + "process_id bigint, process_started_at timestamptz, lease_expires_at timestamptz, "
            + "updated_at timestamptz not null default now(), "
            + "check ((active_run_id is null) = (execution_token is null)))");
    sharedJdbcTemplate.execute(
        "create table backup_runs (id bigint primary key, trigger_type varchar(32) not null, "
            + "status varchar(16) not null, storage_mode varchar(16) not null, stage varchar(32) not null, "
            + "target_path text, file_name text, file_bytes bigint, sha256 varchar(128), "
            + "pg_server_version varchar(64), flyway_version varchar(64), started_at timestamptz not null, "
            + "finished_at timestamptz, duration_ms bigint, error_message text)");
    sharedJdbcTemplate.execute(
        "create table flyway_schema_history (installed_rank integer primary key, version varchar(64))");
    sharedJdbcTemplate.update("insert into backup_state (id, updated_at) values (1, ?)",
        java.sql.Timestamp.from(NOW));
    sharedJdbcTemplate.update("insert into flyway_schema_history (installed_rank, version) values (1, '20260903.01')");
  }
}
