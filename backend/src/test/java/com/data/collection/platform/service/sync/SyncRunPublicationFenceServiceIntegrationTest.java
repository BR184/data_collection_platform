package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.WorkspaceRefreshRequest;
import com.data.collection.platform.entity.WorkspaceScopeSelection;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
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

class SyncRunPublicationFenceServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private SyncRunPublicationFenceService service;
  private SyncRunCompletionCommitService completionCommitService;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("publication_fence_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    dropSchema();
    createSchema();
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    service = new SyncRunPublicationFenceService(jdbcTemplate);
    completionCommitService =
        new SyncRunCompletionCommitService(
            service, new SyncRunLeaseService(jdbcTemplate));
  }

  @Test
  void test_zero_dml_manual_run_waits_for_older_unpublished_target_and_projection() {
    SyncRun run = insertOwnedRun(10L, WorkspaceRefreshRequest.global("system-test-board"));
    insertTarget(9L, 41L, 7L, "PENDING", null, null, 42L);

    captureOwnedRun(run);

    assertThat(fenceStatus(10L)).isEqualTo("PENDING");
    assertThat(fenceRequiredVersion(10L)).isEqualTo(7L);

    insertProjectionTask(60L, 50L, "PROJECT", "42", 3L, "QUEUED");
    publishTarget(9L, 41L, 7L, 50L);
    advanceAfterFactPublication();

    assertThat(fenceStatus(10L)).isEqualTo("PENDING");
    assertThat(fenceScopeStatus(10L, "PROJECT", "42")).isEqualTo("PENDING");

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set status = 'SUCCESS' where id = 60");
    advanceAfterProjectionTask(60L);

    assertThat(fenceStatus(10L)).isEqualTo("SUCCESS");
    assertThat(fenceScopeStatus(10L, "PROJECT", "42")).isEqualTo("SUCCESS");
  }

  @Test
  void test_project_selector_does_not_wait_for_another_project_target() {
    WorkspaceRefreshRequest request =
        new WorkspaceRefreshRequest(
            "system-test-project-board", WorkspaceScopeSelection.projects(Set.of(42L)));
    SyncRun run = insertOwnedRun(20L, request);
    insertTarget(18L, 51L, 8L, "PENDING", null, null, 42L);
    insertTarget(19L, 52L, 9L, "PENDING", null, null, 99L);

    captureOwnedRun(run);

    assertThat(fenceRequiredVersion(20L)).isEqualTo(8L);
    assertThat(fenceStatus(20L)).isEqualTo("PENDING");
    publishTarget(18L, 51L, 8L, 70L);
    advanceAfterFactPublication();

    assertThat(fenceStatus(20L)).isEqualTo("SUCCESS");
  }

  @Test
  void test_failed_projection_marks_fence_failed_and_later_success_recovers_it() {
    SyncRun run = insertOwnedRun(30L, WorkspaceRefreshRequest.global("customer-issue-board"));
    insertTarget(29L, 61L, 11L, "PUBLISHED", 11L, 80L, 42L);
    insertProjectionTask(81L, 80L, "PROJECT", "42", 4L, "FAILED");

    captureOwnedRun(run);

    assertThat(fenceStatus(30L)).isEqualTo("FAILED");

    jdbcTemplate.update(
        "update fact_projection_refresh_tasks set status = 'SUCCESS' where id = 81");
    advanceAfterProjectionTask(81L);

    assertThat(fenceStatus(30L)).isEqualTo("SUCCESS");
  }

  @Test
  void test_same_run_captures_every_registered_workspace() {
    SyncRun run = insertOwnedRunWithoutRequest(40L);
    WorkspaceRefreshRequest global =
        WorkspaceRefreshRequest.global("customer-issue-board");
    WorkspaceRefreshRequest project =
        new WorkspaceRefreshRequest(
            "system-test-project-board", WorkspaceScopeSelection.projects(Set.of(42L)));
    assertThat(registerRequest(40L, global)).isTrue();
    assertThat(registerRequest(40L, project)).isTrue();
    insertTarget(39L, 71L, 13L, "PENDING", null, null, 42L);

    captureOwnedRun(run);

    assertThat(fenceCount(40L)).isEqualTo(2);
    assertThat(fenceRequiredVersion(40L, "customer-issue-board")).isEqualTo(13L);
    assertThat(fenceRequiredVersion(40L, "system-test-project-board")).isEqualTo(13L);
  }

  @Test
  void test_registration_lock_acquired_first_is_captured_by_same_run() throws Exception {
    SyncRun run = insertOwnedRunWithoutRequest(50L);
    insertTarget(49L, 81L, 17L, "PENDING", null, null, 42L);
    WorkspaceRefreshRequest request =
        WorkspaceRefreshRequest.global("customer-issue-board");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch registrationLocked = new CountDownLatch(1);
    CountDownLatch releaseRegistration = new CountDownLatch(1);
    try {
      Future<Boolean> registration =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      status -> {
                        boolean registered =
                            service.registerRequest(50L, "alpha", refresh(request));
                        registrationLocked.countDown();
                        await(releaseRegistration);
                        return registered;
                      }));
      await(registrationLocked);
      Future<?> completion =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> completionCommitService.finishOwnedRun(run)));

      releaseRegistration.countDown();
      assertThat(registration.get(10, TimeUnit.SECONDS)).isTrue();
      completion.get(10, TimeUnit.SECONDS);

      assertThat(runStatus(50L)).isEqualTo("SUCCESS");
      assertThat(fenceRequiredVersion(50L)).isEqualTo(17L);
    } finally {
      releaseRegistration.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void test_completion_lock_acquired_first_rejects_late_registration() throws Exception {
    SyncRun run = insertOwnedRunWithoutRequest(60L);
    WorkspaceRefreshRequest request =
        WorkspaceRefreshRequest.global("customer-issue-board");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch completionLocked = new CountDownLatch(1);
    CountDownLatch releaseCompletion = new CountDownLatch(1);
    try {
      Future<?> completion =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        completionCommitService.finishOwnedRun(run);
                        completionLocked.countDown();
                        await(releaseCompletion);
                      }));
      await(completionLocked);
      Future<Boolean> registration =
          executor.submit(
              () ->
                  transactionTemplate.execute(
                      status -> service.registerRequest(60L, "alpha", refresh(request))));

      releaseCompletion.countDown();
      completion.get(10, TimeUnit.SECONDS);
      assertThat(registration.get(10, TimeUnit.SECONDS)).isFalse();

      assertThat(runStatus(60L)).isEqualTo("SUCCESS");
      assertThat(fenceCount(60L)).isZero();
    } finally {
      releaseCompletion.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void test_lost_lease_rolls_back_captured_fence_watermark() {
    SyncRun run = insertOwnedRun(70L, WorkspaceRefreshRequest.global("customer-issue-board"));
    insertTarget(69L, 91L, 19L, "PENDING", null, null, 42L);
    SyncRunLeaseService lostLeaseService = mock(SyncRunLeaseService.class);
    when(lostLeaseService.finishOwnedRun(run)).thenReturn(0);
    SyncRunCompletionCommitService failingCompletion =
        new SyncRunCompletionCommitService(service, lostLeaseService);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> failingCompletion.finishOwnedRun(run)))
        .isInstanceOf(SyncRunLeaseLostException.class);

    assertThat(runStatus(70L)).isEqualTo("RUNNING");
    assertThat(fenceRequiredVersion(70L)).isZero();
  }

  private SyncRun insertOwnedRun(long runId, WorkspaceRefreshRequest request) {
    SyncRun run = insertOwnedRunWithoutRequest(runId);
    assertThat(registerRequest(runId, request)).isTrue();
    return run;
  }

  private SyncRun insertOwnedRunWithoutRequest(long runId) {
    jdbcTemplate.update(
        """
        insert into sync_runs(
            id, source_instance, run_type, status, lease_owner, lease_until,
            planned_table_count, completed_table_count, scanned_rows, applied_rows,
            updated_at)
        values (?, 'alpha', 'TABLE_REFRESH', 'RUNNING', 'worker-a',
                current_timestamp + interval '1 hour', 0, 0, 0, 0, current_timestamp)
        """,
        runId);
    SyncRun run = new SyncRun();
    run.setId(runId);
    run.setRunType(SyncRunType.TABLE_REFRESH);
    run.setStatus(SyncRunStatus.SUCCESS);
    run.setSourceInstance("alpha");
    run.setLeaseOwner("worker-a");
    run.setLeaseUntil(LocalDateTime.now().plusHours(1));
    run.setPlannedTableCount(0);
    run.setCompletedTableCount(0);
    run.setScannedRows(0L);
    run.setAppliedRows(0L);
    run.setFinishedAt(LocalDateTime.now());
    run.setUpdatedAt(LocalDateTime.now());
    return run;
  }

  private void insertTarget(
      long mirrorRunId,
      long rootId,
      long changeVersion,
      String status,
      Long publishedVersion,
      Long taskId,
      long projectId) {
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id, change_version,
            project_id, publication_status, published_version,
            published_by_fact_build_task_id)
        values (?, 'alpha', 'ISSUE', ?, ?, ?, ?, ?, ?)
        """,
        mirrorRunId,
        rootId,
        changeVersion,
        projectId,
        status,
        publishedVersion,
        taskId);
  }

  private void publishTarget(
      long mirrorRunId, long rootId, long publishedVersion, long taskId) {
    jdbcTemplate.update(
        """
        update sync_run_fact_targets
           set publication_status = 'PUBLISHED',
               published_version = ?,
               published_by_fact_build_task_id = ?
         where mirror_run_id = ? and root_id = ?
        """,
        publishedVersion,
        taskId,
        mirrorRunId,
        rootId);
  }

  private void insertProjectionTask(
      long taskId,
      long factBuildTaskId,
      String scopeType,
      String scopeKey,
      long generation,
      String status) {
    jdbcTemplate.update(
        """
        insert into fact_projection_refresh_tasks(
            id, fact_build_task_id, source_instance, fact_type,
            scope_type, scope_key, target_generation, status)
        values (?, ?, 'alpha', 'ISSUE', ?, ?, ?, ?)
        """,
        taskId,
        factBuildTaskId,
        scopeType,
        scopeKey,
        generation,
        status);
  }

  private boolean registerRequest(long runId, WorkspaceRefreshRequest request) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> service.registerRequest(runId, "alpha", refresh(request))));
  }

  private SyncRunPayload.WorkspaceRefreshSpec refresh(WorkspaceRefreshRequest request) {
    return SyncRunPayload.WorkspaceRefreshSpec.from(request, List.of(FactType.ISSUE));
  }

  private void captureOwnedRun(SyncRun run) {
    transactionTemplate.executeWithoutResult(status -> service.captureOwnedRun(run));
  }

  private void advanceAfterFactPublication() {
    transactionTemplate.executeWithoutResult(
        status -> service.advanceAfterFactPublication("alpha", FactType.ISSUE));
  }

  private void advanceAfterProjectionTask(long taskId) {
    transactionTemplate.executeWithoutResult(
        status -> service.advanceAfterProjectionTask(taskId));
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("并发测试等待超时");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("并发测试被中断", error);
    }
  }

  private String fenceStatus(long runId) {
    return jdbcTemplate.queryForObject(
        "select status from sync_run_publication_fences where run_id = ?",
        String.class,
        runId);
  }

  private long fenceRequiredVersion(long runId) {
    Long value =
        jdbcTemplate.queryForObject(
            "select required_change_version from sync_run_publication_fences where run_id = ?",
            Long.class,
            runId);
    return value == null ? 0L : value;
  }

  private long fenceRequiredVersion(long runId, String workspaceKey) {
    Long value =
        jdbcTemplate.queryForObject(
            """
            select required_change_version
              from sync_run_publication_fences
             where run_id = ? and workspace_key = ?
            """,
            Long.class,
            runId,
            workspaceKey);
    return value == null ? 0L : value;
  }

  private int fenceCount(long runId) {
    Integer value =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_run_publication_fences where run_id = ?",
            Integer.class,
            runId);
    return value == null ? 0 : value;
  }

  private String runStatus(long runId) {
    return jdbcTemplate.queryForObject(
        "select status from sync_runs where id = ?", String.class, runId);
  }

  private String fenceScopeStatus(long runId, String scopeType, String scopeKey) {
    return jdbcTemplate.queryForObject(
        """
        select scope.status
          from sync_run_publication_fence_scopes scope
          join sync_run_publication_fences fence on fence.id = scope.fence_id
         where fence.run_id = ? and scope.scope_type = ? and scope.scope_key = ?
        """,
        String.class,
        runId,
        scopeType,
        scopeKey);
  }

  private void dropSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_publication_fence_scopes cascade");
    jdbcTemplate.execute("drop table if exists sync_run_publication_fences cascade");
    jdbcTemplate.execute("drop table if exists fact_projection_refresh_tasks cascade");
    jdbcTemplate.execute("drop table if exists sync_run_fact_targets cascade");
    jdbcTemplate.execute("drop table if exists issue_scope_groups cascade");
    jdbcTemplate.execute("drop table if exists issue_scope_catalogs cascade");
    jdbcTemplate.execute("drop table if exists sync_runs cascade");
  }

  private void createSchema() {
    jdbcTemplate.execute(
        """
        create table sync_runs (
          id bigint primary key,
          source_instance varchar(128) not null,
          run_type varchar(64) not null,
          status varchar(32) not null,
          lease_owner varchar(128),
          lease_until timestamp,
          planned_table_count integer,
          completed_table_count integer,
          scanned_rows bigint,
          applied_rows bigint,
          finished_at timestamp,
          error_message text,
          updated_at timestamp
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
          project_id bigint,
          publication_status varchar(32) not null,
          published_version bigint,
          published_by_fact_build_task_id bigint,
          primary key (mirror_run_id, source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table fact_projection_refresh_tasks (
          id bigint primary key,
          fact_build_task_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          scope_type varchar(64) not null,
          scope_key varchar(512) not null,
          target_generation bigint not null,
          status varchar(32) not null,
          error_message text
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_publication_fences (
          id bigserial primary key,
          run_id bigint not null,
          workspace_key varchar(128) not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          target_selector_type varchar(64) not null,
          target_selector_key varchar(512) not null,
          required_change_version bigint not null,
          status varchar(32) not null,
          error_message text,
          created_at timestamp not null,
          updated_at timestamp not null,
          completed_at timestamp,
          unique (run_id, workspace_key, fact_type, target_selector_type, target_selector_key)
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_publication_fence_scopes (
          fence_id bigint not null references sync_run_publication_fences(id),
          scope_type varchar(64) not null,
          scope_key varchar(512) not null,
          required_generation bigint not null,
          projection_task_id bigint,
          status varchar(32) not null,
          updated_at timestamp not null,
          primary key (fence_id, scope_type, scope_key)
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_scope_catalogs (
          id bigint primary key,
          project_id bigint not null,
          dimension varchar(64) not null
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_scope_groups (
          id bigint primary key,
          catalog_id bigint not null references issue_scope_catalogs(id)
        )
        """);
  }
}
