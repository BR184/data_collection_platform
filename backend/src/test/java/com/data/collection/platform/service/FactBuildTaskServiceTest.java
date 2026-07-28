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

    int created = factBuildTaskService.enqueueMirrorRefreshTasks(config, false);
    int duplicate = factBuildTaskService.enqueueMirrorRefreshTasks(config, false);

    assertThat(created).isEqualTo(3);
    assertThat(duplicate).isZero();

    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTask("test-worker", 30);

    assertThat(task).isNotNull();
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

    int created = factBuildTaskService.enqueueMirrorRefreshTasks(config, false, 901L);

    assertThat(created).isEqualTo(3);
    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTaskForRun(901L, "test-worker", 30);
    assertThat(task).isNotNull();
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
  void shouldRecoverExpiredQueuedTaskLease() {
    GitlabSyncConfig config = config("corp-timeout");
    factBuildTaskService.enqueueMirrorRefreshTasks(config, true);
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
