package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FactBuildTaskServiceTest {
  private static final long FACT_BUILD_LOCK_KEY = 2026043001L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FactBuildTaskService factBuildTaskService;
  @Autowired private FactRefreshImpactScopeService impactScopeService;
  @Autowired private DataSource dataSource;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from fact_build_tasks");
  }

  @Test
  void shouldRecordSuccessfulBuildTask() {
    FactBuildResponse response =
        factBuildTaskService.runGuarded(
            "issue", true, () -> new FactBuildResponse("issue", true, 12, "done"));

    assertThat(response.affectedRows()).isEqualTo(12);
    var latest = factBuildTaskService.latest("issue");
    assertThat(latest).isNotNull();
    assertThat(latest.scope()).isEqualTo("issue");
    assertThat(latest.full()).isTrue();
    assertThat(latest.status()).isEqualTo("SUCCESS");
    assertThat(latest.affectedRows()).isEqualTo(12);
    assertThat(latest.finishedAt()).isNotNull();
  }

  @Test
  void shouldPreserveSourceScopedBuildTask() {
    FactBuildResponse response =
        factBuildTaskService.runGuarded(
            "cc:merge-request", false, () -> new FactBuildResponse("cc:merge-request", false, 3, "done"));

    assertThat(response.affectedRows()).isEqualTo(3);
    var latest = factBuildTaskService.latest("cc:merge-request");
    assertThat(latest).isNotNull();
    assertThat(latest.scope()).isEqualTo("cc:merge-request");
    assertThat(latest.status()).isEqualTo("SUCCESS");
  }

  @Test
  void test_failed_build_rolls_back_fact_publication_but_keeps_failed_task_status() {
    jdbcTemplate.update(
        "delete from issue_scope_catalogs where project_id = 999999 and dimension = 'MILESTONE'");

    assertThatThrownBy(
            () ->
                factBuildTaskService.runGuarded(
                    "issue",
                    true,
                    () -> {
                      jdbcTemplate.update(
                          "insert into issue_scope_catalogs(project_id, project_name, dimension) values (999999, 'transaction-test', 'MILESTONE')");
                      throw new IllegalStateException("publication failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("publication failed");

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_catalogs where project_id = 999999",
            Integer.class))
        .isZero();
    assertThat(factBuildTaskService.latest("issue").status()).isEqualTo("FAILED");
  }

  @Test
  void test_other_connections_see_previous_version_until_publication_commits() {
    long projectId = 999998L;
    jdbcTemplate.update(
        "delete from issue_scope_catalogs where project_id = ? and dimension = 'MILESTONE'",
        projectId);

    factBuildTaskService.runGuarded(
        "issue",
        true,
        () -> {
          jdbcTemplate.update(
              "insert into issue_scope_catalogs(project_id, project_name, dimension) values (?, 'visibility-test', 'MILESTONE')",
              projectId);
          try (var connection = dataSource.getConnection()) {
            assertThat(countCatalogs(connection, projectId)).isZero();
          } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
          }
          return new FactBuildResponse("issue", true, 1, "published");
        });

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_catalogs where project_id = ?",
            Integer.class,
            projectId))
        .isOne();
    jdbcTemplate.update("delete from issue_scope_catalogs where project_id = ?", projectId);
  }

  private int countCatalogs(java.sql.Connection connection, long projectId)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "select count(*) from issue_scope_catalogs where project_id = ?")) {
      statement.setLong(1, projectId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  @Test
  void shouldSkipWhenAnotherBuildHoldsLock() {
    FactBuildResponse response =
        jdbcTemplate.execute(
            (ConnectionCallback<FactBuildResponse>)
                connection -> {
                  try (PreparedStatement lock =
                      connection.prepareStatement("select pg_advisory_lock(?)")) {
                    lock.setLong(1, FACT_BUILD_LOCK_KEY);
                    lock.execute();
                  }
                  try {
                    return factBuildTaskService.runGuarded(
                        "merge-request",
                        false,
                        () -> new FactBuildResponse("merge-request", false, 99, "should not run"));
                  } finally {
                    try (PreparedStatement unlock =
                        connection.prepareStatement("select pg_advisory_unlock(?)")) {
                      unlock.setLong(1, FACT_BUILD_LOCK_KEY);
                      unlock.execute();
                    }
                  }
                });

    assertThat(response.affectedRows()).isZero();
    var latest = factBuildTaskService.latest("merge-request");
    assertThat(latest).isNotNull();
    assertThat(latest.status()).isEqualTo("SKIPPED");
  }

  @Test
  void shouldEnqueueDeduplicateClaimAndFinishMirrorRefreshTasks() {
    GitlabSyncConfig config = config("corp-main");

    int created = factBuildTaskService.enqueueFactRefreshTasks(config, false, 900L);
    int duplicate = factBuildTaskService.enqueueFactRefreshTasks(config, false, 900L);

    assertThat(created).isEqualTo(3);
    assertThat(duplicate).isZero();

    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTask("test-worker", 30);

    assertThat(task).isNotNull();
    assertThat(task.factRunId()).isEqualTo(900L);
    assertThat(task.configId()).isEqualTo(config.getId());
    assertThat(task.sourceInstance()).startsWith("corp_main_");
    assertThat(task.factType()).isEqualTo("ISSUE");
    assertThat(task.scope()).endsWith(":issue");
    assertThat(task.full()).isFalse();
    assertThat(task.leaseUntil()).isAfter(LocalDateTime.now());

    factBuildTaskService.finishQueuedTask(task.id(), "SUCCESS", 7, "done", null);

    var latest = factBuildTaskService.latest(task.scope());
    assertThat(latest.status()).isEqualTo("SUCCESS");
    assertThat(latest.affectedRows()).isEqualTo(7);
    assertThat(latest.finishedAt()).isNotNull();
  }

  @Test
  void shouldBindQueuedMirrorRefreshTasksToSyncRun() {
    GitlabSyncConfig config = config("corp-sync-run");

    int created = factBuildTaskService.enqueueFactRefreshTasks(config, false, 901L);

    assertThat(created).isEqualTo(3);
    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(901L, "test-worker", 30);
    assertThat(task).isNotNull();
    assertThat(task.factRunId()).isEqualTo(901L);
    assertThat(task.configId()).isEqualTo(config.getId());
    assertThat(task.factType()).isEqualTo("ISSUE");
    String runId =
        jdbcTemplate.queryForObject(
            "select run_id from fact_build_tasks where id = ?",
            String.class,
            task.id());
    assertThat(runId).isEqualTo("901");
  }

  @Test
  void test_fact_child_impact_query_reads_parent_table_tasks_from_database() {
    GitlabSyncConfig config = config("fact-parent-lineage");
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long parentRunId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
              run_id, config_id, source_instance, run_type, trigger_type, status, priority,
              exclusive_scope, planned_table_count, completed_table_count, applied_rows
            ) values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', 90, ?, 1, 1, 1)
            returning id
            """,
            Long.class,
            "test_parent_" + suffix,
            config.getId(),
            sourceInstance,
            "test:mirror:" + suffix);
    Long factRunId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
              run_id, config_id, source_instance, run_type, trigger_type, status, priority,
              exclusive_scope, parent_run_id
            ) values (?, ?, ?, 'FACT_REFRESH', 'SCHEDULE', 'RUNNING', 10, ?, ?)
            returning id
            """,
            Long.class,
            "test_fact_" + suffix,
            config.getId(),
            sourceInstance,
            "test:fact:" + suffix,
            parentRunId);
    Long tableTaskId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_run_table_tasks(
              run_id, config_id, source_instance, source_table, mirror_table, task_type,
              status, row_strategy, rows_scanned, rows_applied
            ) values (?, ?, ?, 'issues', 'ods_gitlab_issues', 'TABLE_REFRESH',
                      'SUCCESS', 'INCREMENTAL', 1, 1)
            returning id
            """,
            Long.class,
            parentRunId,
            config.getId(),
            sourceInstance);
    long issueId = 1_500_000_000L + Math.floorMod(tableTaskId, 500_000_000L);
    jdbcTemplate.execute(
        "alter table ods_gitlab_issues add column if not exists mirror_task_id bigint");

    try {
      jdbcTemplate.update(
          """
          insert into ods_gitlab_issues(
            id, iid, project_id, mirror_task_id, mirror_deleted
          ) values (?, 32129, 9, ?, false)
          """,
          issueId,
          tableTaskId);
      FactRefreshImpactScopeService.ImpactScope result =
          impactScopeService.resolve(factRunId, config.getId(), sourceInstance, "ISSUE");

      assertThat(result.fallbackRequired()).isFalse();
      assertThat(result.targets())
          .containsExactly(new FactRefreshImpactScopeService.Target(9L, 32129L));
    } finally {
      jdbcTemplate.update("delete from ods_gitlab_issues where id = ?", issueId);
      jdbcTemplate.update("delete from sync_runs where id in (?, ?)", factRunId, parentRunId);
      jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
    }
  }

  @Test
  void shouldRecoverExpiredQueuedTaskLease() {
    GitlabSyncConfig config = config("corp-timeout");
    factBuildTaskService.enqueueFactRefreshTasks(config, true, 902L);
    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTask("test-worker", 30);
    jdbcTemplate.update(
        """
        update fact_build_tasks
           set lease_until = current_timestamp - interval '1 minute'
         where id = ?
        """,
        task.id());

    int recovered = factBuildTaskService.recoverTimedOutQueuedTasks();
    QueuedFactBuildTask reclaimed = factBuildTaskService.claimNextQueuedTask("next-worker", 30);

    assertThat(recovered).isEqualTo(1);
    assertThat(reclaimed.id()).isEqualTo(task.id());
    assertThat(reclaimed.factRunId()).isEqualTo(902L);
    assertThat(reclaimed.retryCount()).isEqualTo(1);
    assertThat(reclaimed.full()).isTrue();
  }

  private GitlabSyncConfig config(String sourcePrefix) {
    String sourceInstance = sourcePrefix + "-" + UUID.randomUUID();
    Long id = jdbcTemplate.queryForObject(
        """
        insert into gitlab_sync_configs(name, source_instance, source_mode, db_name, db_username, db_password)
        values (?, ?, 'DOCKER', 'gitlabhq_production', 'gitlab_ro', 'secret')
        returning id
        """,
        Long.class,
        sourceInstance,
        sourceInstance);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(id);
    config.setSourceInstance(sourceInstance);
    return config;
  }
}
