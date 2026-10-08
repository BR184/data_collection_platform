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
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_dedup_" + UUID.randomUUID().toString().replace("-", ""),
            "token-dedup");

    int created = factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);
    int duplicate = factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);

    assertThat(created).isEqualTo(3);
    assertThat(duplicate).isZero();

    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-dedup", "test-worker", 30);

    assertThat(task).isNotNull();
    assertThat(task.factRunId()).isEqualTo(factRunId);
    assertThat(task.factRunLeaseToken()).isEqualTo("token-dedup");
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
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_binding_" + UUID.randomUUID().toString().replace("-", ""),
            "token-binding");

    int created = factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);

    assertThat(created).isEqualTo(3);
    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-binding", "test-worker", 30);
    assertThat(task).isNotNull();
    assertThat(task.factRunId()).isEqualTo(factRunId);
    assertThat(task.configId()).isEqualTo(config.getId());
    assertThat(task.factType()).isEqualTo("ISSUE");
    String runId =
        jdbcTemplate.queryForObject(
            "select run_id from fact_build_tasks where id = ?",
            String.class,
            task.id());
    assertThat(runId).isEqualTo(String.valueOf(factRunId));
  }

  @Test
  void test_pending_heads_are_assigned_as_bounded_fact_task() {
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
    Long factRunId = insertFactRun(config, sourceInstance, "test_fact_" + suffix, "token-assign");
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

      int assigned =
          factBuildTaskService.assignPendingSourceTargetBatches(
              config, factRunId, 100);
      QueuedFactBuildTask task =
          factBuildTaskService.claimNextQueuedTaskForFactRun(
              factRunId, "token-assign", "target-worker", 30);

      // 来源默认共享同一事实控制面，因此本断言只锁定"本事实运行确实认领了这两个根"，
      // 不对同源其他待发布根的归属数量做假设。
      assertThat(assigned).isPositive();
      assertThat(task).isNotNull();
      assertThat(task.full()).isFalse();
      assertThat(task.factType()).isEqualTo("ISSUE");
      assertThat(task.leaseOwner()).isEqualTo("target-worker");
      assertThat(assignedRootsOfFactRun(factRunId, sourceInstance))
          .contains(issueId, secondIssueId);
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
  void test_pending_head_is_claimed_once_and_taken_over_after_claim_holder_fails() {
    GitlabSyncConfig config = config("fact-target-takeover");
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String suffix = UUID.randomUUID().toString().replace("-", "");
    Long firstFactRunId =
        insertFactRun(config, sourceInstance, "test_first_" + suffix, "token-first");
    Long secondFactRunId =
        insertFactRun(config, sourceInstance, "test_second_" + suffix, "token-second");
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            config_id, source_instance, fact_type, latest_mirror_run_id,
            ready_mirror_run_id, readiness_status)
        values (?, ?, 'ISSUE', ?, ?, 'READY')
        """,
        config.getId(),
        sourceInstance,
        firstFactRunId,
        firstFactRunId);
    long issueId = 1_600_000_000L + Math.floorMod(firstFactRunId + secondFactRunId, 100_000_000L);

    try {
      jdbcTemplate.update(
          """
          insert into fact_change_heads(
            source_instance, fact_type, root_id, latest_change_version, published_version
          ) values (?, 'ISSUE', ?, 21, 0)
          """,
          sourceInstance,
          issueId);

      // 唯一待发布根只派发一个批次任务：领取记录写入即生效，重复派发会造出空跑任务。
      int firstAssigned =
          factBuildTaskService.assignPendingSourceTargetBatches(config, firstFactRunId, 100);
      assertThat(firstAssigned).isOne();
      assertThat(assignedRootsOfFactRun(firstFactRunId, sourceInstance)).containsExactly(issueId);
      QueuedFactBuildTask firstTask =
          factBuildTaskService.claimNextQueuedTaskForFactRun(
              firstFactRunId, "token-first", "first-worker", 30);
      assertThat(firstTask).isNotNull();

      // 在途任务持有领取记录：同一根不会被再次派发给别的运行。
      int duplicatedAssigned =
          factBuildTaskService.assignPendingSourceTargetBatches(config, secondFactRunId, 100);
      assertThat(duplicatedAssigned).isZero();
      assertThat(assignedRootsOfFactRun(secondFactRunId, sourceInstance)).doesNotContain(issueId);

      // 领取者所在运行失败、任务租约超时且重试耗尽后，领取记录随任务终态释放。
      jdbcTemplate.update("update sync_runs set status = 'FAILED' where id = ?", firstFactRunId);
      jdbcTemplate.update(
          """
          update fact_build_tasks
             set retry_count = max_retry_count,
                 lease_until = current_timestamp - interval '1 minute'
           where id = ?
          """,
          firstTask.id());
      assertThat(factBuildTaskService.recoverTimedOutQueuedTasks()).isPositive();
      assertThat(assignedRootsOfFactRun(firstFactRunId, sourceInstance)).isEmpty();

      // 未发布根不归任何运行私有：失败运行收敛后，后续运行可再次认领同一根。
      int takeoverAssigned =
          factBuildTaskService.assignPendingSourceTargetBatches(config, secondFactRunId, 100);
      assertThat(takeoverAssigned).isOne();
      assertThat(assignedRootsOfFactRun(secondFactRunId, sourceInstance)).containsExactly(issueId);
    } finally {
      jdbcTemplate.update(
          "delete from fact_build_tasks where run_id in (?, ?)",
          String.valueOf(firstFactRunId),
          String.valueOf(secondFactRunId));
      jdbcTemplate.update(
          "delete from source_fact_publication_states where config_id = ? and source_instance = ?",
          config.getId(), sourceInstance);
      jdbcTemplate.update(
          "delete from sync_runs where id in (?, ?)", firstFactRunId, secondFactRunId);
      jdbcTemplate.update(
          "delete from fact_change_heads where source_instance = ? and fact_type = 'ISSUE' and root_id = ?",
          sourceInstance,
          issueId);
      jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
    }
  }

  private Long insertFactRun(
      GitlabSyncConfig config, String sourceInstance, String runId, String leaseToken) {
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
          run_id, config_id, source_instance, run_type, trigger_type, status, priority,
          exclusive_scope, lease_owner, lease_until
        ) values (?, ?, ?, 'FACT_REFRESH', 'SCHEDULE', 'RUNNING', 10, ?, ?,
                  current_timestamp + interval '10 minutes')
        returning id
        """,
        Long.class,
        runId,
        config.getId(),
        sourceInstance,
        "test:fact-run:" + runId,
        leaseToken);
  }

  private java.util.List<Long> assignedRootsOfFactRun(
      Long factRunId, String sourceInstance) {
    return jdbcTemplate.queryForList(
        """
        select roots.root_id
          from fact_build_task_roots roots
          join fact_build_tasks task on task.id = roots.task_id
         where task.run_id = ?
           and roots.source_instance = ?
           and roots.fact_type = 'ISSUE'
        """,
        Long.class,
        String.valueOf(factRunId),
        sourceInstance);
  }

  @Test
  void renewTaskLeaseShouldExtendOnlyOwnerHeldRunningTask() {
    GitlabSyncConfig config = config("corp-renew");
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_renew_" + UUID.randomUUID().toString().replace("-", ""),
            "token-renew");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);
    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-renew", "renew-worker", 30);

    assertThat(factBuildTaskService.renewTaskLease(task, 30)).isTrue();
    assertThat(
            factBuildTaskService.renewTaskLease(
                new QueuedFactBuildTask(
                    task.id(),
                    task.factRunId(),
                    task.factRunLeaseToken(),
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
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_recover_" + UUID.randomUUID().toString().replace("-", ""),
            "token-recover");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);
    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-recover", "test-worker", 30);
    jdbcTemplate.update(
        """
        update fact_build_tasks
           set lease_until = current_timestamp - interval '1 minute'
         where id = ?
        """,
        task.id());
    // 租约超时回收只针对不再活动、且已失去执行权的父运行：把运行置为终态并释放租约。
    jdbcTemplate.update(
        "update sync_runs set status = 'FAILED', lease_owner = null, lease_until = null where id = ?",
        factRunId);

    int recovered = factBuildTaskService.recoverTimedOutQueuedTasks();

    // 父运行被重新领取（新执行令牌）后，超时任务才由该运行的执行器重新认领。
    jdbcTemplate.update(
        """
        update sync_runs
           set status = 'RUNNING',
               lease_owner = 'token-recover-2',
               lease_until = current_timestamp + interval '10 minutes'
         where id = ?
        """,
        factRunId);
    QueuedFactBuildTask reclaimed =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-recover-2", "next-worker", 30);

    assertThat(recovered).isEqualTo(1);
    assertThat(reclaimed.id()).isEqualTo(task.id());
    assertThat(reclaimed.factRunId()).isEqualTo(factRunId);
    assertThat(reclaimed.retryCount()).isEqualTo(1);
    assertThat(reclaimed.full()).isTrue();
  }

  @Test
  void failOwnedTaskShouldEnterRetryWaitingBeforeMaxRetryAndFailedAfter() {
    GitlabSyncConfig config = config("corp-failure");
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_failure_" + UUID.randomUUID().toString().replace("-", ""),
            "token-failure");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);
    String runIdText = String.valueOf(factRunId);
    // 入队会产生 issue/MR/integration-test 三个任务；本测试只验证单任务的失败-重试链，
    // 先清掉后两个避免按 created_at 认领到不同任务。
    jdbcTemplate.update(
        "delete from fact_build_tasks where id in ("
            + "select id from fact_build_tasks where run_id = ? order by created_at desc, id desc limit 2)",
        runIdText);

    QueuedFactBuildTask firstAttempt =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-failure", "worker-a", 30);

    FactBuildTaskService.FailureDisposition retryDisposition =
        factBuildTaskService.failOwnedTask(firstAttempt, "第一次瞬时失败");

    assertThat(retryDisposition.retryWaiting()).isTrue();
    assertThat(retryDisposition.failed()).isFalse();
    assertThat(retryDisposition.runAfter()).isNotNull();

    // 退避把 run_after 推到未来；把时间拨回以便立即可再认领。
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp - interval '1 minute' where run_id = ?",
        runIdText);
    QueuedFactBuildTask secondAttempt =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-failure", "worker-b", 30);
    assertThat(secondAttempt.id()).isEqualTo(firstAttempt.id());
    assertThat(secondAttempt.retryCount()).isEqualTo(1);

    factBuildTaskService.failOwnedTask(secondAttempt, "第二次失败");
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp - interval '1 minute' where run_id = ?",
        runIdText);
    QueuedFactBuildTask thirdAttempt =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-failure", "worker-c", 30);
    FactBuildTaskService.FailureDisposition finalDisposition =
        factBuildTaskService.failOwnedTask(thirdAttempt, "第三次失败");

    // max_retry_count 默认 3：第 3 次失败时 retry_count+1=3 不小于 max_retry_count，进入终态。
    // 终态分支保留原 run_after 值（不再参与调度），契约只保证 failed 判定。
    assertThat(finalDisposition.failed()).isTrue();
    var summary = factBuildTaskService.summarizeFactRun(factRunId);
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

  @Test
  void test_orphaned_tasks_are_parked_for_manual_decision_and_not_claimed() {
    GitlabSyncConfig config = config("corp-orphan");
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    Long factRunId =
        insertFactRun(
            config,
            sourceInstance,
            "test_park_" + UUID.randomUUID().toString().replace("-", ""),
            "token-park");
    jdbcTemplate.update(
        "update sync_runs set status = 'FAILED', lease_owner = null, lease_until = null where id = ?",
        factRunId);
    assertThat(factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId)).isEqualTo(3);
    String runIdText = String.valueOf(factRunId);
    Long taskId =
        jdbcTemplate.queryForObject(
            "select id from fact_build_tasks where run_id = ? order by id limit 1",
            Long.class,
            runIdText);
    jdbcTemplate.update(
        """
        insert into fact_build_task_roots(task_id, source_instance, fact_type, root_id)
        values (?, ?, 'ISSUE', 4242)
        """,
        taskId,
        sourceInstance);

    int parked = factBuildTaskService.parkOrphanedTasksForManualDecision();

    assertThat(parked).isEqualTo(3);
    Integer parkedRows =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from fact_build_tasks
             where run_id = ?
               and status = ?
               and manual_disposition = 'REQUIRES_DECISION'
               and lock_owner is null
               and lease_until is null
            """,
            Integer.class,
            runIdText,
            FactBuildTaskService.STATUS_PAUSED);
    assertThat(parkedRows).isEqualTo(3);
    // 停放后即使父运行重新被领取（重新获得授权），任务也不再回到自动派发。
    jdbcTemplate.update(
        """
        update sync_runs
           set status = 'RUNNING',
               lease_owner = 'token-park-2',
               lease_until = current_timestamp + interval '10 minutes'
         where id = ?
        """,
        factRunId);
    assertThat(
            factBuildTaskService.claimNextQueuedTaskForFactRun(
                factRunId, "token-park-2", "worker-park", 30))
        .isNull();
    // 停放保留持有根：维护人员选择"继续"时仍需按原意图恢复同一批根。
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_build_task_roots where task_id = ?", Integer.class, taskId))
        .isEqualTo(1);

    FactBuildTaskService.RunTaskSummary summary = factBuildTaskService.summarizeFactRun(factRunId);
    assertThat(summary.totalTasks()).isEqualTo(3);
    assertThat(summary.successTasks()).isZero();
    assertThat(summary.manualAttentionTasks()).isEqualTo(3);
    assertThat(summary.hasActiveTasks()).isFalse();
    assertThat(summary.awaitsManualDecision()).isTrue();

    jdbcTemplate.update("delete from fact_build_task_roots where task_id = ?", taskId);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
  }

  @Test
  void test_tasks_owned_by_active_fact_run_are_not_parked() {
    GitlabSyncConfig config = config("corp-active-parent");
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_active_fact_" + UUID.randomUUID().toString().replace("-", ""),
            "token-active");
    assertThat(factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId)).isEqualTo(3);

    assertThat(factBuildTaskService.parkOrphanedTasksForManualDecision()).isZero();

    QueuedFactBuildTask claimed =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-active", "worker-active", 30);
    assertThat(claimed).isNotNull();
    assertThat(claimed.factRunId()).isEqualTo(factRunId);

    jdbcTemplate.update("delete from sync_runs where id = ?", factRunId);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", config.getId());
  }

  @Test
  void test_summary_separates_dependency_wait_from_ready_queue() {
    GitlabSyncConfig config = config("corp-wait-summary");
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_wait_" + UUID.randomUUID().toString().replace("-", ""),
            "token-wait");
    assertThat(factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId)).isEqualTo(3);
    String runIdText = String.valueOf(factRunId);
    var taskIds =
        jdbcTemplate.queryForList(
            "select id from fact_build_tasks where run_id = ? order by id", Long.class, runIdText);
    jdbcTemplate.update(
        """
        update fact_build_tasks
           set wait_reason = 'DEPENDENCY_SETTLING',
               run_after = current_timestamp + interval '4 minutes'
         where id = ?
        """,
        taskIds.get(0));
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp where id = ?", taskIds.get(1));

    FactBuildTaskService.RunTaskSummary summary = factBuildTaskService.summarizeFactRun(factRunId);

    assertThat(summary.totalTasks()).isEqualTo(3);
    assertThat(summary.dependencyWaitingTasks()).isEqualTo(1);
    assertThat(summary.queuedTasks()).isEqualTo(2);
    assertThat(summary.hasActiveTasks()).isTrue();
    assertThat(summary.awaitsManualDecision()).isFalse();
    assertThat(summary.nextRunAfter()).isAfter(LocalDateTime.now().plusMinutes(3));

    QueuedFactBuildTask claimed =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-wait", "worker-wait", 30);
    assertThat(claimed).isNotNull();
    assertThat(claimed.id()).isNotEqualTo(taskIds.get(0));
  }

  @Test
  void test_dependency_wait_defers_owned_task_without_consuming_retry_budget() {
    GitlabSyncConfig config = config("corp-defer");
    Long factRunId =
        insertFactRun(
            config,
            GitlabSourceInstanceSupport.sourceInstanceOf(config),
            "test_defer_" + UUID.randomUUID().toString().replace("-", ""),
            "token-defer");
    factBuildTaskService.enqueueFullFactRefreshTasks(config, factRunId);
    jdbcTemplate.update(
        "delete from fact_build_tasks where id in ("
            + "select id from fact_build_tasks where run_id = ? order by created_at desc, id desc limit 2)",
        String.valueOf(factRunId));
    QueuedFactBuildTask task =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-defer", "worker-defer", 30);

    LocalDateTime runAfter = factBuildTaskService.deferOwnedTask(task, 5);

    assertThat(runAfter).isAfter(LocalDateTime.now().plusSeconds(3));
    var row =
        jdbcTemplate.queryForMap(
            "select status, wait_reason, retry_count, lock_owner, lease_until, message "
                + "from fact_build_tasks where id = ?",
            task.id());
    assertThat(row.get("status")).isEqualTo("QUEUED");
    assertThat(row.get("wait_reason")).isEqualTo("DEPENDENCY_SETTLING");
    assertThat(((Number) row.get("retry_count")).intValue()).isZero();
    assertThat(row.get("lock_owner")).isNull();
    assertThat(row.get("lease_until")).isNull();
    assertThat((String) row.get("message")).contains("依赖");

    FactBuildTaskService.RunTaskSummary summary = factBuildTaskService.summarizeFactRun(factRunId);
    assertThat(summary.dependencyWaitingTasks()).isEqualTo(1);
    assertThat(summary.queuedTasks()).isZero();
    assertThat(summary.hasActiveTasks()).isTrue();
    assertThat(summary.awaitsManualDecision()).isFalse();
    assertThat(summary.nextRunAfter()).isEqualTo(runAfter);
    // 未到 run_after 不会被重新认领。
    assertThat(
            factBuildTaskService.claimNextQueuedTaskForFactRun(
                factRunId, "token-defer", "worker-defer", 30))
        .isNull();

    // 认领之后再取消父运行：等待必须被拒绝（执行权已失效），由巡检收敛为人工待处理。
    jdbcTemplate.update(
        "update fact_build_tasks set run_after = current_timestamp where id = ?", task.id());
    QueuedFactBuildTask secondAttempt =
        factBuildTaskService.claimNextQueuedTaskForFactRun(
            factRunId, "token-defer", "worker-defer-2", 30);
    assertThat(secondAttempt).isNotNull();
    jdbcTemplate.update("update sync_runs set cancel_requested = true where id = ?", factRunId);
    assertThatThrownBy(() -> factBuildTaskService.deferOwnedTask(secondAttempt, 5))
        .isInstanceOf(FactTaskLeaseLostException.class);
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
