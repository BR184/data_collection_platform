package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactProjectionTaskService;
import com.data.collection.platform.service.FactProjectionTaskWorkerService;
import com.data.collection.platform.service.FactRefreshTaskWorkerService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabSourceSchemaGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SyncFactRefreshRunExecutorTest {
  private GitlabConfigService configService;
  private FactBuildTaskService factBuildTaskService;
  private FactRefreshTaskWorkerService factRefreshTaskWorkerService;
  private FactProjectionTaskService projectionTaskService;
  private FactProjectionTaskWorkerService projectionTaskWorkerService;
  private GitlabSourceSchemaGuard sourceSchemaGuard;
  private SyncFactRefreshRunExecutor executor;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    factRefreshTaskWorkerService = mock(FactRefreshTaskWorkerService.class);
    projectionTaskService = mock(FactProjectionTaskService.class);
    projectionTaskWorkerService = mock(FactProjectionTaskWorkerService.class);
    sourceSchemaGuard = mock(GitlabSourceSchemaGuard.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setHeartbeatTimeoutSeconds(30);
    properties.setFactTargetBatchSize(200);
    executor =
        new SyncFactRefreshRunExecutor(
            configService,
            factBuildTaskService,
            factRefreshTaskWorkerService,
            projectionTaskService,
            projectionTaskWorkerService,
            sourceSchemaGuard,
            properties,
            new JsonUtils(new ObjectMapper()));
  }

  @Test
  void test_full_run_drains_fact_and_projection_tasks_then_succeeds() {
    SyncRun run = run(14L, null);
    run.setPayloadJson("{\"fullBuild\":true}");
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task = fullTask(101L, 14L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(factBuildTaskService.enqueueFullFactRefreshTasks(config, 14L)).thenReturn(1);
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(14L, runLease(14L), "fact-run-14", 30))
        .thenReturn(task)
        .thenReturn(null);
    when(factRefreshTaskWorkerService.execute(task))
        .thenReturn(new FactBuildResponse("alpha:issue", true, 8, "issue facts built"));
    when(projectionTaskService.claimNext(14L, "projection-run-14", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(14L)).thenReturn(factSummary(1, 1, 0, 0, 0, 0, null, 8));
    when(projectionTaskService.summarize(14L)).thenReturn(projectionSummary(0, 0, 0, 0, 0, 0, null));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    verify(sourceSchemaGuard).verifyAllFactSources("alpha");
    verify(sourceSchemaGuard).verifyMergeRequestCommitFactSource("alpha");
    verify(factBuildTaskService).enqueueFullFactRefreshTasks(config, 14L);
    verify(factRefreshTaskWorkerService).execute(task);
    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(result.plannedTasks()).isOne();
    assertThat(result.completedTasks()).isOne();
    assertThat(result.affectedRows()).isEqualTo(8L);
  }

  @Test
  void test_full_custom_run_without_commit_tables_keeps_commit_enhancement_optional() {
    SyncRun run = run(19L, null);
    run.setPayloadJson("{\"fullBuild\":true}");
    GitlabSyncConfig config = config();
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(
        List.of(
            "merge_requests",
            "merge_request_metrics",
            "projects",
            "namespaces",
            "users",
            "merge_request_reviewers",
            "merge_request_assignees",
            "notes",
            "label_links",
            "labels",
            "resource_label_events"));
    when(configService.getConfigById(1L)).thenReturn(config);
    stubEmptyDrainsAndSummaries(19L);

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    verify(sourceSchemaGuard).verifyAllFactSources("alpha");
    verify(sourceSchemaGuard, never()).verifyMergeRequestCommitFactSource("alpha");
    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
  }

  @Test
  void test_targeted_run_accepts_source_level_consumer_without_parent_lineage() {
    SyncRun run = run(15L, null);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.assignPendingSourceTargetBatches(config(), 15L, 200)).thenReturn(0);
    stubEmptyDrainsAndSummaries(15L);

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
  }

  @Test
  void test_retry_waiting_task_keeps_same_fact_run_retryable() {
    SyncRun run = run(16L, null);
    LocalDateTime retryAt = LocalDateTime.of(2026, 7, 31, 16, 0);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.assignPendingSourceTargetBatches(config(), 16L, 200)).thenReturn(0);
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(16L, runLease(16L), "fact-run-16", 30))
        .thenReturn(null);
    when(projectionTaskService.claimNext(16L, "projection-run-16", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(16L))
        .thenReturn(factSummary(1, 0, 0, 0, 0, 1, retryAt, 0));
    when(projectionTaskService.summarize(16L))
        .thenReturn(projectionSummary(0, 0, 0, 0, 0, 0, null));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.RETRYING);
    assertThat(result.runAfter()).isEqualTo(retryAt);
    assertThat(result.errorMessage()).contains("等待重试");
  }

  @Test
  void test_failed_projection_task_fails_fact_run_without_false_success() {
    SyncRun run = run(17L, null);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.assignPendingSourceTargetBatches(config(), 17L, 200)).thenReturn(0);
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(17L, runLease(17L), "fact-run-17", 30))
        .thenReturn(null);
    when(projectionTaskService.claimNext(17L, "projection-run-17", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(17L)).thenReturn(factSummary(0, 0, 0, 0, 0, 0, null, 0));
    when(projectionTaskService.summarize(17L))
        .thenReturn(projectionSummary(1, 0, 1, 0, 0, 0, null));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.FAILED);
    assertThat(result.errorMessage()).contains("重试上限");
  }

  @Test
  void test_manual_attention_tasks_keep_run_terminal_without_spinning() {
    SyncRun run = run(18L, null);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.assignPendingSourceTargetBatches(config(), 18L, 200)).thenReturn(0);
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(18L, runLease(18L), "fact-run-18", 30))
        .thenReturn(null);
    when(projectionTaskService.claimNext(18L, "projection-run-18", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(18L))
        .thenReturn(
            new FactBuildTaskService.RunTaskSummary(3, 0, 0, 0, 0, 0, 0, 3, 0, null, 0L));
    when(projectionTaskService.summarize(18L))
        .thenReturn(new FactProjectionTaskService.RunTaskSummary(0, 0, 0, 0, 0, 0, 0, 0, null));
    // 人工停放的根仍处于未发布状态；运行不得因此每 5 秒永久重派。
    when(factBuildTaskService.hasUnpublishedTargets(1L, "alpha")).thenReturn(true);

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.PARTIAL_SUCCESS);
    assertThat(result.runAfter()).isNull();
    assertThat(result.errorMessage()).contains("人工待处理");
    assertThat(result.plannedTasks()).isEqualTo(3);
    assertThat(result.completedTasks()).isZero();
  }

  @Test
  void test_dependency_waiting_task_defers_run_to_reported_run_after() {
    SyncRun run = run(20L, null);
    LocalDateTime waitUntil = LocalDateTime.of(2026, 7, 31, 18, 0);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.assignPendingSourceTargetBatches(config(), 20L, 200)).thenReturn(0);
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(20L, runLease(20L), "fact-run-20", 30))
        .thenReturn(null);
    when(projectionTaskService.claimNext(20L, "projection-run-20", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(20L))
        .thenReturn(
            new FactBuildTaskService.RunTaskSummary(
                1, 0, 0, 0, 0, 0, 1, 0, 0, waitUntil, 0L));
    when(projectionTaskService.summarize(20L))
        .thenReturn(new FactProjectionTaskService.RunTaskSummary(0, 0, 0, 0, 0, 0, 0, 0, null));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.PAUSED);
    assertThat(result.runAfter()).isEqualTo(waitUntil);
    assertThat(result.errorMessage()).contains("依赖");
  }

  @Test
  void test_resume_run_executes_only_the_handed_over_intent() {
    SyncRun run = run(21L, null);
    run.setPayloadJson(
        "{\"resumedTask\":{\"kind\":\"FACT_BUILD\",\"taskId\":77,\"originalRunId\":\"14\",\"mode\":\"FULL\"}}");
    QueuedFactBuildTask task = fullTask(311L, 21L);
    when(configService.getConfigById(1L)).thenReturn(config());
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(21L, runLease(21L), "fact-run-21", 30))
        .thenReturn(task)
        .thenReturn(null);
    when(factRefreshTaskWorkerService.execute(task))
        .thenReturn(new FactBuildResponse("alpha:issue", true, 4, "resumed issue facts"));
    when(projectionTaskService.claimNext(21L, "projection-run-21", 30)).thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(21L)).thenReturn(factSummary(1, 1, 0, 0, 0, 0, null, 4));
    when(projectionTaskService.summarize(21L))
        .thenReturn(projectionSummary(0, 0, 0, 0, 0, 0, null));

    SyncFactRefreshRunExecutor.Result result = executor.execute(run);

    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    verify(factBuildTaskService, never()).enqueueFullFactRefreshTasks(any(), anyLong());
    verify(factBuildTaskService, never())
        .assignPendingSourceTargetBatches(any(), anyLong(), anyInt());
    verify(sourceSchemaGuard).verifyAllFactSources("alpha");
    verify(factRefreshTaskWorkerService).execute(task);
  }

  private void stubEmptyDrainsAndSummaries(long runId) {
    when(factBuildTaskService.claimNextQueuedTaskForFactRun(
            runId, runLease(runId), "fact-run-" + runId, 30))
        .thenReturn(null);
    when(projectionTaskService.claimNext(runId, "projection-run-" + runId, 30))
        .thenReturn(null);
    when(factBuildTaskService.summarizeFactRun(runId))
        .thenReturn(factSummary(0, 0, 0, 0, 0, 0, null, 0));
    when(projectionTaskService.summarize(runId))
        .thenReturn(projectionSummary(0, 0, 0, 0, 0, 0, null));
    when(factBuildTaskService.hasUnpublishedTargets(1L, "alpha")).thenReturn(false);
  }

  private FactBuildTaskService.RunTaskSummary factSummary(
      int total,
      int success,
      int failed,
      int queued,
      int running,
      int retryWaiting,
      LocalDateTime nextRunAfter,
      long affectedRows) {
    return new FactBuildTaskService.RunTaskSummary(
        total, success, failed, queued, running, retryWaiting, 0, 0, 0, nextRunAfter, affectedRows);
  }

  private FactProjectionTaskService.RunTaskSummary projectionSummary(
      int total,
      int success,
      int failed,
      int queued,
      int running,
      int retryWaiting,
      LocalDateTime nextRunAfter) {
    return new FactProjectionTaskService.RunTaskSummary(
        total, success, failed, queued, running, retryWaiting, 0, 0, nextRunAfter);
  }

  private QueuedFactBuildTask fullTask(long taskId, long runId) {
    return new QueuedFactBuildTask(
        taskId,
        runId,
        runLease(runId),
        1L,
        "alpha",
        "ISSUE",
        "alpha:issue",
        true,
        0,
        3,
        "fact-run-" + runId,
        LocalDateTime.now().plusSeconds(30));
  }

  /** 父事实运行的执行令牌；批次提交边界据此校验父子两层执行权。 */
  private String runLease(long runId) {
    return "run-lease-" + runId;
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    return config;
  }

  private SyncRun run(Long id, Long parentRunId) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setConfigId(1L);
    run.setSourceInstance("alpha");
    run.setRunType(SyncRunType.FACT_REFRESH);
    run.setParentRunId(parentRunId);
    run.setLeaseOwner(runLease(id));
    run.setPayloadJson("{}");
    return run;
  }
}
