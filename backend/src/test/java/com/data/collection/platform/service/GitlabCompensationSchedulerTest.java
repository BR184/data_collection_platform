package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabCompensationSchedulerTest {
  private GitlabConfigService configService;
  private GitlabMirrorSyncService syncService;
  private GitlabSyncTaskService taskService;
  private GitlabWhitelistService whitelistService;
  private GitlabTableSyncPlanningService tableSyncPlanningService;
  private GitlabCompensationScheduler scheduler;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setSchedulerEnabled(true);
    properties.setSchedulerDelayMs(1000);

    configService = mock(GitlabConfigService.class);
    syncService = mock(GitlabMirrorSyncService.class);
    taskService = mock(GitlabSyncTaskService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    scheduler = new GitlabCompensationScheduler(
        properties,
        configService,
        syncService,
        taskService,
        whitelistService,
        tableSyncPlanningService);
  }

  @Test
  void shouldTriggerCompensationWhenLatestActivityExceededInterval() {
    GitlabSyncConfig config = baseConfig();
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(syncService.hasActiveTask(1L)).thenReturn(false);
    when(taskService.isInCooldown(1L)).thenReturn(false);
    when(taskService.resolveLatestActivityAt(1L)).thenReturn(LocalDateTime.now().minusMinutes(20));
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createCompensationScanPlan(config, tables))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(11L, 1, 1, 0));

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(whitelistService).resolveOptions(config);
    verify(tableSyncPlanningService).createCompensationScanPlan(config, tables);
  }

  @Test
  void shouldSkipCompensationWhenActiveTaskExists() {
    GitlabSyncConfig config = baseConfig();
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(syncService.hasActiveTask(1L)).thenReturn(true);

    scheduler.run();

    verify(syncService).recoverTimedOutTasks();
    verify(tableSyncPlanningService, never()).createCompensationScanPlan(any(), any());
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setCompensationIntervalMinutes(10);
    return config;
  }
}
