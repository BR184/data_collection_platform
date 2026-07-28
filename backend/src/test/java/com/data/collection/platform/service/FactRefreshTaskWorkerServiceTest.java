package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotRefreshService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactRefreshTaskWorkerServiceTest {
  private FactBuildTaskService taskService;
  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private IntegrationTestFactBuildService integrationTestFactBuildService;
  private FactRefreshImpactScopeService impactScopeService;
  private StatisticBoardSnapshotRefreshService snapshotRefreshService;
  private PageRecordSnapshotRefreshService pageRecordSnapshotRefreshService;
  private GitlabMirrorProperties properties;
  private FactRefreshTaskWorkerService workerService;

  @BeforeEach
  void setUp() {
    taskService = mock(FactBuildTaskService.class);
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    impactScopeService = mock(FactRefreshImpactScopeService.class);
    snapshotRefreshService = mock(StatisticBoardSnapshotRefreshService.class);
    pageRecordSnapshotRefreshService = mock(PageRecordSnapshotRefreshService.class);
    properties = new GitlabMirrorProperties();
    properties.setHeartbeatTimeoutSeconds(9);
    workerService = new FactRefreshTaskWorkerService(
        taskService,
        configService,
        factBuildService,
        integrationTestFactBuildService,
        impactScopeService,
        properties,
        snapshotRefreshService,
        pageRecordSnapshotRefreshService,
        new FactPublicationTransaction());
  }

  @Test
  void shouldClaimAndExecuteIssueFactRefreshTask() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task = new QueuedFactBuildTask(
        10L,
        1L,
        1L,
        "corp-main",
        "ISSUE",
        "corp-main:issue",
        false,
        0,
        3,
        LocalDateTime.now().plusSeconds(9));

    when(taskService.claimNextQueuedTask(anyString(), eq(9))).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(taskService.hasSuccessfulFullBuild("corp-main", "ISSUE")).thenReturn(true);
    when(impactScopeService.resolve(1L, "corp-main", "ISSUE"))
        .thenReturn(FactRefreshImpactScopeService.ImpactScope.fallback());
    when(factBuildService.rebuildIssueFactsForQueuedTask(config, false))
        .thenReturn(new FactBuildResponse("corp-main:issue", false, 4, "issues built"));

    workerService.runOnce();

    verify(taskService).recoverTimedOutQueuedTasks();
    verify(factBuildService).rebuildIssueFactsForQueuedTask(config, false);
    verify(taskService).finishQueuedTask(10L, "SUCCESS", 4, "issues built", null);
  }

  @Test
  void shouldMarkTaskFailedWhenFactRefreshThrows() {
    QueuedFactBuildTask task = new QueuedFactBuildTask(
        11L,
        1L,
        1L,
        "corp-main",
        "UNKNOWN",
        "corp-main:unknown",
        false,
        0,
        3,
        LocalDateTime.now().plusSeconds(9));

    when(taskService.claimNextQueuedTask(anyString(), eq(9))).thenReturn(task);

    workerService.runOnce();

    verify(taskService).finishQueuedTask(eq(11L), eq("FAILED"), eq(0), eq("事实数据刷新失败"), anyString());
  }

  @Test
  void shouldRefreshIntegrationFactsOnlyForAffectedIssues() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task =
        new QueuedFactBuildTask(
            12L,
            2L,
            1L,
            "default",
            "INTEGRATION_TEST",
            "integration-test",
            false,
            0,
            3,
            LocalDateTime.now().plusSeconds(9));
    List<FactRefreshImpactScopeService.Target> targets =
        List.of(new FactRefreshImpactScopeService.Target(9L, 101L));

    when(taskService.claimNextQueuedTask(anyString(), eq(9))).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(taskService.hasSuccessfulFullBuild("default", "INTEGRATION_TEST")).thenReturn(true);
    when(impactScopeService.resolve(2L, "default", "INTEGRATION_TEST"))
        .thenReturn(new FactRefreshImpactScopeService.ImpactScope(false, targets));
    when(integrationTestFactBuildService.rebuildFactsByTargets("default", targets))
        .thenReturn(new FactBuildResponse("integration-test", false, 1, "integration built"));

    workerService.runOnce();

    verify(integrationTestFactBuildService).rebuildFactsByTargets("default", targets);
    verify(taskService).finishQueuedTask(12L, "SUCCESS", 1, "integration built", null);
  }

  @Test
  void shouldReconcileMissingIssueFactsWhenMirrorRunHasNoAffectedIssueTargets() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task =
        new QueuedFactBuildTask(
            13L,
            3L,
            1L,
            "default",
            "ISSUE",
            "issue",
            false,
            0,
            3,
            LocalDateTime.now().plusSeconds(9));

    when(configService.getConfigById(1L)).thenReturn(config);
    when(taskService.hasSuccessfulFullBuild("default", "ISSUE")).thenReturn(true);
    when(impactScopeService.resolve(3L, "default", "ISSUE"))
        .thenReturn(FactRefreshImpactScopeService.ImpactScope.empty());
    when(factBuildService.reconcileMissingIssueFacts("default"))
        .thenReturn(new FactBuildResponse("issue", false, 1, "已补齐 1 条缺失议题事实"));

    workerService.execute(task);

    verify(factBuildService).reconcileMissingIssueFacts("default");
    verify(taskService).finishQueuedTask(13L, "SUCCESS", 1, "已补齐 1 条缺失议题事实", null);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("corp-main");
    return config;
  }
}
