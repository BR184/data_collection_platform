package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.QueuedFactBuildTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactRefreshTaskWorkerServiceTest {
  private FactBuildTaskService taskService;
  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private IntegrationTestFactBuildService integrationTestFactBuildService;
  private FactTargetPublicationService targetPublicationService;
  private GitlabMirrorProperties properties;
  private FactRefreshTaskWorkerService workerService;

  @BeforeEach
  void setUp() {
    taskService = mock(FactBuildTaskService.class);
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    targetPublicationService = mock(FactTargetPublicationService.class);
    properties = new GitlabMirrorProperties();
    properties.setSchedulerEnabled(true);
    properties.setHeartbeatTimeoutSeconds(9);
    workerService =
        new FactRefreshTaskWorkerService(
            taskService,
            configService,
            factBuildService,
            integrationTestFactBuildService,
            properties,
            targetPublicationService);
  }

  @Test
  void test_claimed_targeted_issue_task_rebuilds_only_assigned_roots() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task = targetedTask(10L, "ISSUE");
    FactBuildResponse expected =
        new FactBuildResponse("corp-main:issue", false, 2, "issues built");
    when(taskService.claimNextQueuedTask(anyString(), eq(9))).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    invokeTargetedPublication(task, List.of(101L, 102L));
    when(factBuildService.rebuildIssueFactsByRootIds("corp-main", List.of(101L, 102L)))
        .thenReturn(expected);

    workerService.runOnce();

    verify(taskService).recoverTimedOutQueuedTasks();
    verify(factBuildService)
        .rebuildIssueFactsByRootIds("corp-main", List.of(101L, 102L));
    verify(targetPublicationService).publish(eq(task), any());
  }

  @Test
  void test_claimed_full_integration_task_runs_full_publication() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task = fullTask(11L, "INTEGRATION_TEST");
    FactBuildResponse expected =
        new FactBuildResponse("corp-main:integration-test", true, 8, "integration built");
    when(configService.getConfigById(1L)).thenReturn(config);
    invokeFullPublication(task);
    when(integrationTestFactBuildService.rebuildFactsForConfig(config, true))
        .thenReturn(expected);

    FactBuildResponse response = workerService.execute(task);

    assertThat(response).isSameAs(expected);
    verify(integrationTestFactBuildService).rebuildFactsForConfig(config, true);
    verify(targetPublicationService).publishFull(eq(task), any());
  }

  @Test
  void test_unsupported_fact_type_fails_owned_task_without_full_rebuild() {
    QueuedFactBuildTask task = targetedTask(12L, "UNKNOWN");
    when(configService.getConfigById(1L)).thenReturn(config());
    invokeTargetedPublication(task, List.of(101L));

    FactBuildResponse response = workerService.execute(task);

    assertThat(response).isNull();
    verify(taskService).failOwnedTask(task, "Unsupported fact refresh type: UNKNOWN");
    verifyNoInteractions(factBuildService, integrationTestFactBuildService);
  }

  @Test
  void test_disabled_scheduler_does_not_recover_or_claim_tasks() {
    properties.setSchedulerEnabled(false);

    workerService.runOnce();

    verify(taskService, never()).recoverTimedOutQueuedTasks();
    verify(taskService, never()).claimNextQueuedTask(anyString(), anyInt());
  }

  @SuppressWarnings("unchecked")
  private void invokeTargetedPublication(QueuedFactBuildTask task, List<Long> rootIds) {
    when(targetPublicationService.publish(eq(task), any()))
        .thenAnswer(
            invocation -> {
              Function<List<Long>, FactBuildResponse> action = invocation.getArgument(1);
              return action.apply(rootIds);
            });
  }

  @SuppressWarnings("unchecked")
  private void invokeFullPublication(QueuedFactBuildTask task) {
    when(targetPublicationService.publishFull(eq(task), any()))
        .thenAnswer(
            invocation -> {
              Supplier<FactBuildResponse> action = invocation.getArgument(1);
              return action.get();
            });
  }

  private QueuedFactBuildTask targetedTask(long id, String factType) {
    return task(id, factType, false);
  }

  private QueuedFactBuildTask fullTask(long id, String factType) {
    return task(id, factType, true);
  }

  private QueuedFactBuildTask task(long id, String factType, boolean full) {
    return new QueuedFactBuildTask(
        id,
        20L,
        1L,
        "corp-main",
        factType,
        "corp-main:" + factType.toLowerCase(),
        full,
        0,
        3,
        "worker-a",
        LocalDateTime.now().plusSeconds(9));
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("corp-main");
    return config;
  }
}
