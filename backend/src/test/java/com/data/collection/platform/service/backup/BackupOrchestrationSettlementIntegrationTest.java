package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 备份运行收敛的隔离 PostgreSQL 集成验证：真实编排服务 + 真实仓库 + 真实事务边界，只使用合成进程替身
 * 与可控时钟，不连接平台应用库、不执行真实备份。
 *
 * <p>覆盖两类已确认的失租顺序：租约在 worker 结束前过期（无恢复器介入），以及恢复器先撤销再让 worker
 * 结束。两者都必须让运行历史与运行权在同一次结算里收敛，且不得出现"历史永远 RUNNING 且无恢复索引"
 * 或"运行权永久占用导致后续备份被拒"。
 */
class BackupOrchestrationSettlementIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-09-10T02:00:00Z");

  private static PostgreSQLContainer<?> postgres;
  private static DataSource dataSource;
  private static JdbcTemplate sharedJdbcTemplate;
  private static PlatformTransactionManager transactionManager;
  private static TransactionTemplate transactionTemplate;

  @TempDir Path backupRoot;

  private BackupRunRepository runRepository;
  private BackupStateRepository stateRepository;

  @BeforeAll
  static void startIsolatedDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("backup_settlement_test")
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
    runRepository = transactionalRunRepository(sharedJdbcTemplate, transactionManager);
    stateRepository = new BackupStateRepository(sharedJdbcTemplate, transactionTemplate);
    sharedJdbcTemplate.update("delete from backup_runs");
    sharedJdbcTemplate.update(
        "update backup_state set active_run_id = null, execution_token = null, execution_revoked = false, "
            + "process_id = null, process_started_at = null, lease_expires_at = null, updated_at = ? where id = 1",
        java.sql.Timestamp.from(NOW));
  }

  @Test
  void test_leaseExpiresBeforeWorkerEnd_settlesHistoryAndOwnerTogether() {
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    BackupOrchestrationService service =
        orchestration(
            now,
            (command, environment, timeout, context) -> {
              // 导出过程中租约到期：worker 随后失败收尾，没有任何恢复器参与。
              now.set(NOW.plusSeconds(leaseSeconds() + 1L));
              return new BackupProcessRunner.ProcessResult(1, "synthetic dump failure after lease expiry");
            });

    var response = service.triggerManual();

    assertThat(response.accepted()).isTrue();
    assertThat(runRepository.get(response.runId()))
        .hasValueSatisfying(
            run -> {
              assertThat(run.status()).isEqualTo("FAILED");
              assertThat(run.errorMessage()).contains("备份运行权已失效或撤销");
              assertThat(run.finishedAt()).isNotNull();
            });
    assertThat(stateRepository.activeRunId())
        .as("运行结束时必须同时交还运行权")
        .isEmpty();
    assertThat(stateRepository.revokeExpiredExecution(now.get()))
        .as("已结算的运行不能留在恢复器的候选集合里")
        .isEmpty();
    service.shutdown();
  }

  @Test
  void test_recoveryRevokesBeforeWorkerEnd_settlesWithoutBlockingTheNextRun() {
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    BackupOrchestrationService service =
        orchestration(
            now,
            (command, environment, timeout, context) -> {
              now.set(NOW.plusSeconds(leaseSeconds() + 1L));
              // 恢复器在 worker 结束前撤销执行身份。
              stateRepository.revokeExpiredExecution(now.get());
              return new BackupProcessRunner.ProcessResult(1, "synthetic dump failure after revocation");
            });

    var first = service.triggerManual();

    assertThat(first.accepted()).isTrue();
    assertThat(runRepository.get(first.runId()))
        .hasValueSatisfying(
            run -> {
              assertThat(run.status()).isEqualTo("FAILED");
              assertThat(run.errorMessage()).contains("备份运行权已失效或撤销");
            });
    assertThat(stateRepository.activeRunId()).isEmpty();
    assertThat(service.triggerManual().accepted())
        .as("已确认停止的旧执行不得永久占用运行权")
        .isTrue();
    service.shutdown();
  }

  @Test
  void test_settlementDatabaseFailure_keepsOwnerAndRecoveryIndexForLaterConvergence() {
    AtomicReference<Instant> now = new AtomicReference<>(NOW);
    BackupStateRepository failingStateRepository = spy(stateRepository);
    doThrow(new RuntimeException("database unavailable"))
        .when(failingStateRepository)
        .settleOwnedRun(anyLong(), anyString(), any(), any());
    BackupOrchestrationService service =
        orchestrationWithState(
            failingStateRepository,
            now,
            (command, environment, timeout, context) -> {
              now.set(NOW.plusSeconds(leaseSeconds() + 1L));
              return new BackupProcessRunner.ProcessResult(1, "synthetic dump failure after lease expiry");
            });

    var response = service.triggerManual();

    assertThat(runRepository.get(response.runId()))
        .as("结算未落库时历史不得被改写")
        .hasValueSatisfying(run -> assertThat(run.status()).isEqualTo("RUNNING"));
    assertThat(stateRepository.activeRunId())
        .as("结算未确认时保留运行权作为恢复索引")
        .isEqualTo(Optional.of(response.runId()));
    assertThat(stateRepository.settleOwnedRun(
            response.runId(), currentToken(), "recovery settled the stopped execution", now.get()))
        .as("恢复巡检仍能按 token 收敛该运行")
        .isTrue();
    assertThat(stateRepository.activeRunId()).isEmpty();
    service.shutdown();
  }

  private String currentToken() {
    return sharedJdbcTemplate.queryForObject(
        "select execution_token from backup_state where id = 1", String.class);
  }

  /** 与编排服务使用的租约长度一致，供替身在导出过程中推进时钟跨越租约边界。 */
  private static long leaseSeconds() {
    return new BackupConfigurationProperties().getLeaseSeconds();
  }

  private BackupOrchestrationService orchestration(
      AtomicReference<Instant> now, BackupProcessRunner runner) {
    return orchestrationWithState(stateRepository, now, runner);
  }

  private BackupOrchestrationService orchestrationWithState(
      BackupStateRepository effectiveStateRepository,
      AtomicReference<Instant> now,
      BackupProcessRunner runner) {
    BackupSettingsRepository settingsRepository = mock(BackupSettingsRepository.class);
    when(settingsRepository.load()).thenReturn(Optional.of(BackupSettings.defaults()));
    BackupConfigurationProperties properties = new BackupConfigurationProperties();
    properties.setRoot(backupRoot.toString());
    properties.setInstanceLabel("settlement");
    ExecutorService executor = mock(ExecutorService.class);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .execute(any());
    ScheduledExecutorService heartbeat = mock(ScheduledExecutorService.class);
    doAnswer(invocation -> mock(ScheduledFuture.class))
        .when(heartbeat)
        .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    return new BackupOrchestrationService(
        settingsRepository,
        runRepository,
        effectiveStateRepository,
        properties,
        new BackupCryptoSupport(properties),
        new BackupCommandFactory(properties),
        runner,
        endpoint -> {
          throw new AssertionError("本次验证不应发起远程备份操作");
        },
        BackupDatabaseTarget.from("jdbc:postgresql://unused:5432/test", "test", "test"),
        new ControllableClock(now),
        executor,
        heartbeat);
  }

  private static BackupRunRepository transactionalRunRepository(
      JdbcTemplate jdbcTemplate, PlatformTransactionManager manager) {
    ProxyFactory factory = new ProxyFactory(new BackupRunRepository(jdbcTemplate));
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (BackupRunRepository) factory.getProxy();
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
    sharedJdbcTemplate.update(
        "insert into backup_state (id, updated_at) values (1, ?)", java.sql.Timestamp.from(NOW));
    sharedJdbcTemplate.update(
        "insert into flyway_schema_history (installed_rank, version) values (1, '20260903.01')");
  }

  /** 由测试推进的时钟，用来在不等待真实时间的条件下制造租约边界。 */
  private static final class ControllableClock extends Clock {
    private final AtomicReference<Instant> current;

    private ControllableClock(AtomicReference<Instant> current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
