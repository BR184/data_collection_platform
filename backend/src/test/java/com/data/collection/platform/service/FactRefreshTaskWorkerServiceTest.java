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
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FactRefreshTaskWorkerServiceTest {
  private FactBuildTaskService taskService;
  private GitlabConfigService configService;
  private FactBuildService factBuildService;
  private IntegrationTestFactBuildService integrationTestFactBuildService;
  private GitlabMirrorProperties properties;
  private FactRefreshTaskWorkerService workerService;

  @BeforeEach
  void setUp() {
    taskService = mock(FactBuildTaskService.class);
    configService = mock(GitlabConfigService.class);
    factBuildService = mock(FactBuildService.class);
    integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    properties = new GitlabMirrorProperties();
    properties.setHeartbeatTimeoutSeconds(9);
    workerService = new FactRefreshTaskWorkerService(
        taskService,
        configService,
        factBuildService,
        integrationTestFactBuildService,
        properties);
  }

  @Test
  void shouldClaimAndExecuteIssueFactRefreshTask() {
    GitlabSyncConfig config = config();
    QueuedFactBuildTask task = new QueuedFactBuildTask(
        10L,
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
        "corp-main",
        "UNKNOWN",
        "corp-main:unknown",
        false,
        0,
        3,
        LocalDateTime.now().plusSeconds(9));

    when(taskService.claimNextQueuedTask(anyString(), eq(9))).thenReturn(task);

    workerService.runOnce();

    verify(taskService).finishQueuedTask(eq(11L), eq("FAILED"), eq(0), eq("Fact refresh failed"), anyString());
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("corp-main");
    return config;
  }
}
