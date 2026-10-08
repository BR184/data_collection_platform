package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import java.time.LocalDateTime;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 镜像互斥域租约回收的真实 PostgreSQL 证据。
 *
 * <p>夹具只建立回收依赖的两张表：运行与镜像表任务。断言覆盖合法等待不被误判、异常空租约可回收、
 * 取消语义不被改写、旧执行器不能续租复活，以及"运行状态与子任务执行权同事务撤销"。
 */
class SyncRunLeaseRecoveryIntegrationTest {
  private static final int LEASE_WINDOW_SECONDS = 60;

  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private SyncRunLeaseService leaseService;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("sync_run_lease_recovery_test");
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
    jdbcTemplate.execute("drop table if exists sync_run_table_tasks");
    jdbcTemplate.execute("drop table if exists sync_runs");
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          run_id varchar(64) not null,
          config_id bigint not null,
          source_instance varchar(64) not null,
          run_type varchar(32) not null,
          trigger_type varchar(32) not null,
          status varchar(32) not null,
          priority integer not null default 0,
          exclusive_scope varchar(64) not null,
          cancel_requested boolean not null default false,
          resolved_worker_count integer not null default 0,
          planned_table_count integer not null default 0,
          completed_table_count integer not null default 0,
          scanned_rows bigint not null default 0,
          applied_rows bigint not null default 0,
          run_after timestamp not null default current_timestamp,
          heartbeat_at timestamp,
          lease_owner varchar(128),
          lease_until timestamp,
          started_at timestamp,
          finished_at timestamp,
          error_message text,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_table_tasks (
          id bigint primary key,
          run_id bigint not null,
          source_table varchar(128) not null,
          status varchar(32) not null,
          lease_owner varchar(128),
          lease_until timestamp,
          heartbeat_at timestamp,
          last_error text,
          finished_at timestamp,
          updated_at timestamp not null default current_timestamp)
        """);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setHeartbeatTimeoutSeconds(LEASE_WINDOW_SECONDS);
    leaseService = transactionProxied(new SyncRunLeaseService(jdbcTemplate, properties), dataSource);
  }

  /**
   * 让真实 {@code @Transactional} 语义生效：直接 new 出的实例上注解不生效，代理才能验证"同事务撤销"。
   */
  private SyncRunLeaseService transactionProxied(
      SyncRunLeaseService target, DataSource dataSource) {
    PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return (SyncRunLeaseService) factory.getProxy();
  }

  @Test
  void legitimate_retrying_and_paused_without_lease_are_not_recovered() {
    insertRun(1L, "RETRYING", null, null, "current_timestamp - interval '2 hours'", null);
    insertRun(2L, "PAUSED", null, null, "current_timestamp - interval '2 hours'", null);
    jdbcTemplate.update(
        "update sync_runs set run_after = current_timestamp + interval '30 minutes' where id in (1, 2)");

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isZero();
    assertThat(statusOf(1L)).isEqualTo("RETRYING");
    assertThat(statusOf(2L)).isEqualTo("PAUSED");
  }

  @Test
  void active_run_without_lease_and_stale_heartbeat_is_recovered() {
    insertRun(3L, "RUNNING", null, null, "current_timestamp - interval '2 hours'", "current_timestamp - interval '2 hours'");

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isEqualTo(1);
    Map<String, Object> row = rowOf(3L);
    assertThat(row.get("status")).isEqualTo("TIMEOUT");
    assertThat(row.get("lease_owner")).isNull();
    assertThat(row.get("lease_until")).isNull();
    assertThat((String) row.get("error_message")).contains("held no lease while active");
    assertThat(row.get("finished_at")).isNotNull();
  }

  @Test
  void active_run_without_lease_but_fresh_heartbeat_is_kept() {
    insertRun(4L, "RUNNING", null, null, "current_timestamp", "current_timestamp - interval '5 seconds'");

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isZero();
    assertThat(statusOf(4L)).isEqualTo("RUNNING");
  }

  @Test
  void cancelling_run_without_lease_and_stale_heartbeat_is_recovered() {
    insertRun(5L, "CANCELLING", null, null, "current_timestamp - interval '3 hours'", "current_timestamp - interval '3 hours'");

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isEqualTo(1);
    assertThat(statusOf(5L)).isEqualTo("TIMEOUT");
  }

  @Test
  void active_runs_with_live_lease_are_kept() {
    insertRun(6L, "RUNNING", "owner-6", "current_timestamp + interval '1 hour'", "current_timestamp", "current_timestamp");
    insertRun(7L, "CANCELLING", "owner-7", "current_timestamp + interval '1 hour'", "current_timestamp", "current_timestamp");
    insertRun(8L, "RETRYING", "owner-8", "current_timestamp + interval '1 hour'", "current_timestamp", "current_timestamp");

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isZero();
    assertThat(statusOf(6L)).isEqualTo("RUNNING");
    assertThat(statusOf(7L)).isEqualTo("CANCELLING");
    assertThat(statusOf(8L)).isEqualTo("RETRYING");
  }

  @Test
  void expired_lease_revokes_run_and_its_active_tasks_together() {
    insertRun(9L, "RUNNING", "owner-9", "current_timestamp - interval '1 second'", "current_timestamp - interval '10 minutes'", "current_timestamp - interval '10 minutes'");
    insertTask(901L, 9L, "QUEUED", null, null);
    insertTask(902L, 9L, "RUNNING", "task-owner", "current_timestamp - interval '5 minutes'");
    insertTask(903L, 9L, "SUCCESS", null, null);

    int recovered = leaseService.recoverTimedOutRuns();

    assertThat(recovered).isEqualTo(1);
    assertThat(statusOf(9L)).isEqualTo("TIMEOUT");
    assertThat(statusOfTask(901L)).isEqualTo("TIMEOUT");
    assertThat(statusOfTask(902L)).isEqualTo("TIMEOUT");
    assertThat(statusOfTask(903L)).isEqualTo("SUCCESS");
    Map<String, Object> task = jdbcTemplate.queryForMap("select * from sync_run_table_tasks where id = 902");
    assertThat(task.get("lease_owner")).isNull();
    assertThat(task.get("lease_until")).isNull();
  }

  @Test
  void task_revocation_failure_rolls_back_run_recovery() {
    insertRun(10L, "RUNNING", "owner-10", "current_timestamp - interval '1 second'", "current_timestamp - interval '10 minutes'", "current_timestamp - interval '10 minutes'");
    insertTask(1001L, 10L, "RUNNING", "task-owner", "current_timestamp - interval '5 minutes'");
    // 故障注入：任务状态写入在真实表上不可能失败，用 CHECK 约束制造任一步失败以验证整体回滚。
    jdbcTemplate.execute(
        "alter table sync_run_table_tasks add constraint test_rejects_timeout check (status <> 'TIMEOUT')");

    assertThatThrownBy(() -> leaseService.recoverTimedOutRuns())
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    assertThat(statusOf(10L)).isEqualTo("RUNNING");
    assertThat(statusOfTask(1001L)).isEqualTo("RUNNING");
  }

  @Test
  void expired_owner_cannot_renew_or_finish_after_recovery() {
    insertRun(11L, "RUNNING", "owner-11", "current_timestamp - interval '1 second'", "current_timestamp - interval '10 minutes'", "current_timestamp - interval '10 minutes'");
    assertThat(leaseService.recoverTimedOutRuns()).isEqualTo(1);
    assertThat(statusOf(11L)).isEqualTo("TIMEOUT");

    assertThat(leaseService.heartbeat(11L, "owner-11", LEASE_WINDOW_SECONDS)).isZero();
    assertThat(leaseService.finishOwnedRun(completedRun(11L, "owner-11", SyncRunStatus.SUCCESS)))
        .isZero();
    assertThat(statusOf(11L)).isEqualTo("TIMEOUT");
    assertThat(rowOf(11L).get("lease_owner")).isNull();
  }

  @Test
  void cancelling_run_rejects_success_finalization_but_accepts_cancel_finalization() {
    insertRun(12L, "CANCELLING", "owner-12", "current_timestamp + interval '1 hour'", "current_timestamp", "current_timestamp");

    assertThat(leaseService.finishOwnedRun(completedRun(12L, "owner-12", SyncRunStatus.SUCCESS)))
        .isZero();
    assertThat(statusOf(12L)).isEqualTo("CANCELLING");

    assertThat(leaseService.finishOwnedRun(completedRun(12L, "owner-12", SyncRunStatus.CANCELLED)))
        .isEqualTo(1);
    assertThat(statusOf(12L)).isEqualTo("CANCELLED");
  }

  @Test
  void run_with_expired_lease_cannot_be_renewed_by_its_old_owner() {
    insertRun(13L, "RUNNING", "owner-13", "current_timestamp - interval '1 second'", "current_timestamp - interval '2 minutes'", "current_timestamp - interval '2 minutes'");

    assertThat(leaseService.heartbeat(13L, "owner-13", LEASE_WINDOW_SECONDS)).isZero();
    assertThat(statusOf(13L)).isEqualTo("RUNNING");
  }

  private void insertRun(
      long id,
      String status,
      String leaseOwner,
      String leaseUntil,
      String updatedAtExpression,
      String heartbeatAtExpression) {
    jdbcTemplate.update(
        """
        insert into sync_runs (
          id, run_id, config_id, source_instance, run_type, trigger_type, status,
          exclusive_scope, lease_owner, lease_until, heartbeat_at, updated_at)
        values (?, ?, 1, 'corp-main', 'FACT_REFRESH', 'MANUAL', ?, 'corp-main', ?, %s, %s, %s)
        """
            .formatted(
                literalOrNull(leaseUntil),
                literalOrNull(heartbeatAtExpression),
                literalOrNull(updatedAtExpression)),
        id,
        "run-" + id,
        status,
        leaseOwner);
  }

  private void insertTask(long id, long runId, String status, String leaseOwner, String leaseUntil) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks (
          id, run_id, source_table, status, lease_owner, lease_until, heartbeat_at)
        values (?, ?, 'gitlab_issues', ?, ?, %s, %s)
        """
            .formatted(literalOrNull(leaseUntil), literalOrNull(leaseUntil)),
        id,
        runId,
        status,
        leaseOwner);
  }

  private String literalOrNull(String expression) {
    return expression == null ? "null" : expression;
  }

  private SyncRun completedRun(long id, String leaseOwner, SyncRunStatus status) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setLeaseOwner(leaseOwner);
    run.setStatus(status);
    run.setPlannedTableCount(2);
    run.setCompletedTableCount(2);
    run.setScannedRows(10L);
    run.setAppliedRows(9L);
    run.setFinishedAt(LocalDateTime.now());
    run.setUpdatedAt(LocalDateTime.now());
    return run;
  }

  private String statusOf(Long id) {
    return jdbcTemplate.queryForObject("select status from sync_runs where id = ?", String.class, id);
  }

  private String statusOfTask(Long id) {
    return jdbcTemplate.queryForObject(
        "select status from sync_run_table_tasks where id = ?", String.class, id);
  }

  private Map<String, Object> rowOf(Long id) {
    return jdbcTemplate.queryForMap("select * from sync_runs where id = ?", id);
  }
}
