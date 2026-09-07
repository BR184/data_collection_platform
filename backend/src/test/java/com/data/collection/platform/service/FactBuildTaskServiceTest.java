package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.UUID;
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
  void test_failed_build_records_failed_task_status() {
    assertThatThrownBy(
            () ->
                factBuildTaskService.runGuarded(
                    "issue",
                    true,
                    () -> {
                      throw new IllegalStateException("publication failed");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("publication failed");

    assertThat(factBuildTaskService.latest("issue").status()).isEqualTo("FAILED");
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
  void test_full_refresh_tasks_are_deduplicated_and_claimed_with_owner() {
    GitlabSyncConfig config = config("corp-main");

    int created = factBuildTaskService.enqueueFullFactRefreshTasks(config, 900L);
    int duplicate = factBuildTaskService.enqueueFullFactRefreshTasks(config, 900L);

    assertThat(created).isEqualTo(3);
    assertThat(duplicate).isZero();

    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTask("test-worker", 30);

    assertThat(task).isNotNull();
    assertThat(task.factRunId()).isEqualTo(900L);
    assertThat(task.configId()).isEqualTo(config.getId());
    assertThat(task.sourceInstance())
        .isEqualTo(GitlabSourceInstanceSupport.sourceInstanceOf(config));
    assertThat(task.factType()).isEqualTo("ISSUE");
    assertThat(task.scope()).isEqualTo("issue");
    assertThat(task.full()).isTrue();
    assertThat(task.leaseOwner()).isEqualTo("test-worker");
    assertThat(task.leaseUntil()).isAfter(LocalDateTime.now());
  }

  @Test
  void test_full_refresh_tasks_are_bound_to_fact_run() {
    GitlabSyncConfig config = config("corp-sync-run");

    int created = factBuildTaskService.enqueueFullFactRefreshTasks(config, 901L);

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
  void test_pending_versioned_targets_are_assigned_as_bounded_fact_task() {
    GitlabSyncConfig config = config("fact-target-assignment");
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long parentRunId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
              run_id, config_id, source_instance, run_type, trigger_type, status, priority,
              exclusive_scope, planned_table_count, completed_table_count, applied_rows
            ) values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'RUNNING', 90, ?, 1, 1, 1)
            returning id
            """,
            Long.class,
            "test_parent_" + suffix,
            config.getId(),
            sourceInstance,
            "test:mirror:" + suffix);
    Long secondParentRunId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
              run_id, config_id, source_instance, run_type, trigger_type, status, priority,
              exclusive_scope, planned_table_count, completed_table_count, applied_rows
            ) values (?, ?, ?, 'INCREMENTAL_SYNC', 'SCHEDULE', 'SUCCESS', 90, ?, 1, 1, 1)
            returning id
            """,
            Long.class,
            "test_parent_second_" + suffix,
            config.getId(),
            sourceInstance,
            "test:mirror-second:" + suffix);
    Long factRunId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
              run_id, config_id, source_instance, run_type, trigger_type, status, priority,
              exclusive_scope
            ) values (?, ?, ?, 'FACT_REFRESH', 'SCHEDULE', 'RUNNING', 10, ?)
            returning id
            """,
            Long.class,
            "test_fact_" + suffix,
            config.getId(),
            sourceInstance,
            "test:fact:" + suffix);
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            config_id, source_instance, fact_type, latest_mirror_run_id,
            ready_mirror_run_id, readiness_status)
        values (?, ?, 'ISSUE', ?, ?, 'READY')
        """,
        config.getId(),
        sourceInstance,
        secondParentRunId,
        secondParentRunId);
    long issueId = 1_500_000_000L + Math.floorMod(parentRunId, 500_000_000L);
    long secondIssueId = issueId + 1L;

    try {
      jdbcTemplate.update(
          """
          insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version
          ) values (?, 'ISSUE', ?, 11, 0)
          """,
          sourceInstance,
          issueId);
      jdbcTemplate.update(
          """
          insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version
          ) values (?, 'ISSUE', ?, 12, 0)
          """,
          sourceInstance,
          secondIssueId);
      jdbcTemplate.update(
          """
          insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id, change_version,
            project_id, iid, publication_status
          ) values (?, ?, 'ISSUE', ?, 11, 9, 32129, 'PENDING')
          """,
          parentRunId,
          sourceInstance,
          issueId);
      jdbcTemplate.update(
          """
          insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id, change_version,
            project_id, iid, publication_status
          ) values (?, ?, 'ISSUE', ?, 12, 9, 32130, 'PENDING')
          """,
          secondParentRunId,
          sourceInstance,
          secondIssueId);

      int assigned =
          factBuildTaskService.assignPendingSourceTargetBatches(
              config, factRunId, 100);
      QueuedFactBuildTask task =
          factBuildTaskService.claimNextQueuedTaskForFactRun(
              factRunId, "target-worker", 30);

      assertThat(assigned).isOne();
      assertThat(task).isNotNull();
      assertThat(task.full()).isFalse();
      assertThat(task.factType()).isEqualTo("ISSUE");
      assertThat(task.leaseOwner()).isEqualTo("target-worker");
      assertThat(factBuildTaskService.loadAssignedRootIds(task))
          .containsExactly(issueId, secondIssueId);
    } finally {
      jdbcTemplate.update("delete from fact_build_tasks where run_id = ?", String.valueOf(factRunId));
      jdbcTemplate.update(
          "delete from source_fact_publication_states where config_id = ? and source_instance = ?",
          config.getId(), sourceInstance);
      jdbcTemplate.update(
          "delete from sync_runs where id in (?, ?, ?)",
          factRunId, parentRunId, secondParentRunId);
      jdbcTemplate.update(
          "delete from fact_change_heads where source_instance = ? and fact_type = 'ISSUE' and root_id in (?, ?)",
          sourceInstance,
          issueId,
          secondIssueId);
      jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
    }
  }

  @Test
  void renewTaskLeaseShouldExtendOnlyOwnerHeldRunningTask() {
    GitlabSyncConfig config = config("corp-renew");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, 904L);
    QueuedFactBuildTask task = factBuildTaskService.claimNextQueuedTask("renew-worker", 30);

    assertThat(factBuildTaskService.renewTaskLease(task, 30)).isTrue();
    assertThat(
            factBuildTaskService.renewTaskLease(
                new QueuedFactBuildTask(
                    task.id(),
                    task.factRunId(),
                    task.configId(),
                    task.sourceInstance(),
                    task.factType(),
                    task.scope(),
                    task.full(),
                    task.retryCount(),
                    task.maxRetryCount(),
                    "other-owner",
                    task.leaseUntil()),
                30))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select lock_owner from fact_build_tasks where id = ?", String.class, task.id()))
        .isEqualTo("renew-worker");
  }

  @Test
  void shouldRecoverExpiredQueuedTaskLease() {
    GitlabSyncConfig config = config("corp-timeout");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, 902L);
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

  @Test
  void failOwnedTaskShouldEnterRetryWaitingBeforeMaxRetryAndFailedAfter() {
    GitlabSyncConfig config = config("corp-failure");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, 903L);
    // 入队会产生 issue/MR/integration-test 三个任务；本测试只验证单任务的失败-重试链，
    // 先清掉后两个避免按 created_at 认领到不同任务。
    jdbcTemplate.update(
        "delete from fact_build_tasks where id in ("
            + "select id from fact_build_tasks where run_id = '903' order by created_at desc, id desc limit 2)");

    QueuedFactBuildTask firstAttempt = factBuildTaskService.claimNextQueuedTask("worker-a", 30);

    FactBuildTaskService.FailureDisposition retryDisposition =
        factBuildTaskService.failOwnedTask(firstAttempt, "第一次瞬时失败");

    assertThat(retryDisposition.retryWaiting()).isTrue();
    assertThat(retryDisposition.failed()).isFalse();
    assertThat(retryDisposition.runAfter()).isNotNull();

    // 退避把 run_after 推到未来；把时间拨回以便立即可再认领。
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp - interval '1 minute' where run_id = '903'");
    QueuedFactBuildTask secondAttempt = factBuildTaskService.claimNextQueuedTask("worker-b", 30);
    assertThat(secondAttempt.id()).isEqualTo(firstAttempt.id());
    assertThat(secondAttempt.retryCount()).isEqualTo(1);

    factBuildTaskService.failOwnedTask(secondAttempt, "第二次失败");
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp - interval '1 minute' where run_id = '903'");
    QueuedFactBuildTask thirdAttempt = factBuildTaskService.claimNextQueuedTask("worker-c", 30);
    FactBuildTaskService.FailureDisposition finalDisposition =
        factBuildTaskService.failOwnedTask(thirdAttempt, "第三次失败");

    // max_retry_count 默认 3：第 3 次失败时 retry_count+1=3 不小于 max_retry_count，进入终态。
    // 终态分支保留原 run_after 值（不再参与调度），契约只保证 failed 判定。
    assertThat(finalDisposition.failed()).isTrue();
    var summary = factBuildTaskService.summarizeFactRun(903L);
    assertThat(summary.totalTasks()).isEqualTo(1);
    assertThat(summary.failedTasks()).isEqualTo(1);
    assertThat(summary.hasActiveTasks()).isFalse();
    assertThat(summary.affectedRows()).isZero();
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
  }

  @Test
  void summarizeFactRunShouldReturnEmptySummaryForUnknownRun() {
    FactBuildTaskService.RunTaskSummary summary = factBuildTaskService.summarizeFactRun(9_999_999L);

    assertThat(summary.totalTasks()).isZero();
    assertThat(summary.hasActiveTasks()).isFalse();
    assertThat(summary.affectedRows()).isZero();
  }

  @Test
  void busyLockWithSyncRunBindingShouldThrowInsteadOfReturningSkippedResponse() {
    jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
      try (PreparedStatement lock = connection.prepareStatement("select pg_advisory_lock(?)")) {
        lock.setLong(1, FACT_BUILD_LOCK_KEY);
        lock.execute();
      }
      try {
        assertThatThrownBy(() -> factBuildTaskService.runGuarded(
                "issue", false, 4242L, () -> new FactBuildResponse("issue", false, 1, "should not run")))
            .isInstanceOf(com.data.collection.platform.common.exception.BizException.class)
            .hasMessage(FactBuildTaskService.BUSY_MESSAGE);
        var latest = factBuildTaskService.latest("issue");
        assertThat(latest).isNotNull();
        assertThat(latest.status()).isEqualTo("SKIPPED");
        return null;
      } finally {
        try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
          unlock.setLong(1, FACT_BUILD_LOCK_KEY);
          unlock.execute();
        }
      }
    });
  }

  @Test
  void busyDetectionShouldOnlyMatchExactBusyMessage() {
    assertThat(FactBuildTaskService.wasSkippedBecauseBusy(
        new FactBuildResponse("issue", true, 0, FactBuildTaskService.BUSY_MESSAGE))).isTrue();
    assertThat(FactBuildTaskService.wasSkippedBecauseBusy(
        new FactBuildResponse("issue", true, 5, "议题事实已全量构建"))).isFalse();
    assertThat(FactBuildTaskService.wasSkippedBecauseBusy(null)).isFalse();
  }

  @Test
  void scopeNormalizationShouldFoldUnknownScopesToAllAndKeepSourcePrefix() {
    // 未知基础范围回退 all；来源前缀先按统一来源实例键契约归一化，再与基础范围组合。
    factBuildTaskService.runGuarded("not-a-scope", true, () -> new FactBuildResponse("all", true, 0, "ok"));
    var unknown = factBuildTaskService.latest("all");
    assertThat(unknown).isNotNull();
    assertThat(unknown.scope()).isEqualTo("all");

    factBuildTaskService.runGuarded(
        "corp-x:merge_request", false, () -> new FactBuildResponse("corp-x:merge-request", false, 0, "ok"));
    var scoped = factBuildTaskService.latest("corp_x:merge-request");
    assertThat(scoped).isNotNull();
    assertThat(scoped.scope()).isEqualTo("corp_x:merge-request");

    factBuildTaskService.runGuarded("corp-x:mystery", false, () -> new FactBuildResponse("corp-x:all", false, 0, "ok"));
    var scopedFallback = factBuildTaskService.latest("corp_x:all");
    assertThat(scopedFallback).isNotNull();
    assertThat(scopedFallback.scope()).isEqualTo("corp_x:all");
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
