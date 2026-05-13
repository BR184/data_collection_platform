package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJob;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.mapper.GitlabMirrorRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabMirrorSyncServiceTest {
  private GitlabConfigService configService;
  private GitlabWhitelistService whitelistService;
  private GitlabExternalDbService externalDbService;
  private GitlabMirrorSchemaService mirrorSchemaService;
  private GitlabMirrorTableStorageService mirrorTableStorageService;
  private GitlabMirrorRecordMapper mirrorRecordMapper;
  private GitlabSyncLogService logService;
  private GitlabSyncTaskService taskService;
  private GitlabWebhookPreciseSyncPlanner webhookPreciseSyncPlanner;
  private GitlabMirrorProperties properties;
  private JsonUtils jsonUtils;
  private FactBuildTaskService factBuildTaskService;
  private GitlabTableSyncPlanningService tableSyncPlanningService;
  private GitlabTableSyncWorkerService tableSyncWorkerService;
  private GitlabMirrorSyncService syncService;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    externalDbService = mock(GitlabExternalDbService.class);
    mirrorSchemaService = mock(GitlabMirrorSchemaService.class);
    mirrorTableStorageService = mock(GitlabMirrorTableStorageService.class);
    mirrorRecordMapper = mock(GitlabMirrorRecordMapper.class);
    logService = mock(GitlabSyncLogService.class);
    taskService = mock(GitlabSyncTaskService.class);
    webhookPreciseSyncPlanner = mock(GitlabWebhookPreciseSyncPlanner.class);
    properties = new GitlabMirrorProperties();
    jsonUtils = mock(JsonUtils.class);
    factBuildTaskService = mock(FactBuildTaskService.class);
    tableSyncPlanningService = mock(GitlabTableSyncPlanningService.class);
    tableSyncWorkerService = mock(GitlabTableSyncWorkerService.class);
    syncService =
        new GitlabMirrorSyncService(
            configService,
            whitelistService,
            externalDbService,
            mirrorSchemaService,
            mirrorTableStorageService,
            mirrorRecordMapper,
            logService,
            taskService,
            webhookPreciseSyncPlanner,
            properties,
            jsonUtils,
            factBuildTaskService,
            tableSyncPlanningService,
            tableSyncWorkerService,
            null);
  }

  @Test
  void fullSyncShouldQueueTableVerificationPlanAndDrainWorker() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));

    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualVerificationPlan(config, tables))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(100L, 1, 1, 0));
    when(tableSyncPlanningService.findJobStatus(100L)).thenReturn(SyncStatus.SUCCESS);

    SyncTaskSubmissionResult result = syncService.startFullSync();

    assertThat(result.task().getId()).isEqualTo(100L);
    assertThat(result.task().getTaskType()).isEqualTo(SyncType.FULL);
    assertThat(result.task().getStatus()).isEqualTo(SyncStatus.SUCCESS);
    verify(tableSyncWorkerService).drainReadyTasksForJob(100L);
    verify(taskService, never()).submitTaskResult(any(), any(), any(), anyString(), any());
  }

  @Test
  void manualIncrementalSyncShouldQueueTablePlanAndDrainWorker() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));

    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(
            config,
            tables,
            List.of("issues"),
            "Manual recovery incremental sync"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(41L, 1, 1, 0));
    when(tableSyncPlanningService.findJobStatus(41L)).thenReturn(SyncStatus.SUCCESS);

    SyncTaskSubmissionResult result =
        syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync");

    assertThat(result.task().getId()).isEqualTo(41L);
    assertThat(result.task().getTaskType()).isEqualTo(SyncType.INCREMENTAL);
    assertThat(result.task().getTriggerType()).isEqualTo(SyncTriggerType.MANUAL);
    assertThat(result.action()).isEqualTo(SyncSubmissionAction.CREATED);
    verify(tableSyncWorkerService).drainReadyTasksForJob(41L);
    verify(taskService, never()).submitTaskResult(any(), any(), any(), anyString(), any());
  }

  @Test
  void webhookTriggeredIncrementalShouldAlsoUseTablePlanInsteadOfLegacyTaskQueue() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));

    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(
            config,
            tables,
            List.of("issues"),
            "Triggered by webhook"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(58L, 1, 1, 0));
    when(tableSyncPlanningService.findJobStatus(58L)).thenReturn(SyncStatus.SUCCESS);

    SyncTaskSubmissionResult result =
        syncService.startIncrementalSync(SyncTriggerType.WEBHOOK, "Triggered by webhook");

    assertThat(result.task().getId()).isEqualTo(58L);
    assertThat(result.task().getTriggerType()).isEqualTo(SyncTriggerType.WEBHOOK);
    verify(tableSyncWorkerService).drainReadyTasksForJob(58L);
    verify(taskService, never()).submitTaskResult(any(), any(), any(), anyString(), any());
  }

  @Test
  void incrementalSyncShouldReuseActiveTableJobInsteadOfCreatingZeroSecondPlan() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    GitlabSyncJob activeJob = new GitlabSyncJob();
    activeJob.setId(99L);
    activeJob.setConfigId(1L);
    activeJob.setJobType(GitlabSyncJobType.DAILY_VERIFY);
    activeJob.setStatus(SyncStatus.RUNNING);
    activeJob.setTriggerType(SyncTriggerType.MANUAL);

    when(configService.getConfig()).thenReturn(config);
    when(tableSyncPlanningService.findActiveJob(1L)).thenReturn(activeJob);

    SyncTaskSubmissionResult result =
        syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync");

    assertThat(result.action()).isEqualTo(SyncSubmissionAction.REUSED_ACTIVE);
    assertThat(result.task().getId()).isEqualTo(99L);
    assertThat(result.task().getStatus()).isEqualTo(SyncStatus.RUNNING);
    assertThat(result.task().getTaskType()).isEqualTo(SyncType.FULL);
    verify(tableSyncPlanningService, never()).createManualRefreshPlan(any(), any(), any(), any());
    verify(tableSyncWorkerService, never()).drainReadyTasksForJob(any());
  }

  @Test
  void manualIncrementalTablePlanShouldWriteVisibleSyncLog() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));

    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(
            config,
            tables,
            List.of("issues"),
            "Manual recovery incremental sync"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(41L, 1, 1, 0));
    when(tableSyncWorkerService.drainReadyTasksForJob(41L)).thenReturn(1);
    when(tableSyncPlanningService.findJobStatus(41L)).thenReturn(SyncStatus.SUCCESS);
    when(logService.start(1L, SyncType.INCREMENTAL, List.of("issues"), "Manual recovery incremental sync"))
        .thenReturn(9L);

    syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync");

    verify(logService).start(1L, SyncType.INCREMENTAL, List.of("issues"), "Manual recovery incremental sync");
    verify(logService).finish(eq(9L), eq(SyncStatus.SUCCESS), contains("表级同步"), eq(1), eq(0));
  }

  @Test
  void manualFullSyncShouldLeaveLogRunningWhenJobContinuesAfterInitialDrain() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));

    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualVerificationPlan(config, tables))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(100L, 1, 1, 0));
    when(tableSyncWorkerService.drainReadyTasksForJob(100L)).thenReturn(200);
    when(tableSyncPlanningService.findJobStatus(100L)).thenReturn(SyncStatus.RUNNING);
    when(logService.start(1L, SyncType.FULL, List.of("issues"), "Manual full sync"))
        .thenReturn(19L);

    SyncTaskSubmissionResult result = syncService.startFullSync();

    assertThat(result.task().getStatus()).isEqualTo(SyncStatus.RUNNING);
    verify(logService).start(1L, SyncType.FULL, List.of("issues"), "Manual full sync");
    verify(logService, never()).finish(eq(19L), any(), anyString(), any(Integer.class), any(Integer.class));
  }

  @Test
  void recoverTimedOutTasksShouldDelegateToTaskServiceAndRecoverMirrorStatus() {
    syncService.recoverTimedOutTasks();

    verify(taskService).recoverTimedOutTasks();
    verify(mirrorSchemaService).recoverStaleSyncingStatuses();
  }

  @Test
  void refreshTablesOnDemandShouldQueueTableLevelManualRefreshPlan() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));
    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.listOptionsStrict(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(config, tables, List.of("issues"), "board"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(31L, 1, 1, 0));

    int planned = syncService.refreshTablesOnDemand(List.of("issues"), "board");

    assertThat(planned).isEqualTo(1);
    verify(tableSyncPlanningService).createManualRefreshPlan(config, tables, List.of("issues"), "board");
    verify(tableSyncWorkerService).drainReadyTasksForJob(31L);
    verify(externalDbService, never()).incrementalScan(any(), any(), any());
  }

  @Test
  void refreshTablesOnDemandDetailedShouldReturnPersistentJobContext() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true),
        new TableWhitelistOption("label_links", "Label links", "id", null, true));
    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.listOptionsStrict(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(
            config,
            tables,
            List.of("issues", "label_links"),
            "board"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(31L, 2, 1, 1));
    when(tableSyncPlanningService.findJobStatus(31L)).thenReturn(SyncStatus.SUCCESS);

    GitlabMirrorSyncService.OnDemandRefreshResult result =
        syncService.refreshTablesOnDemandDetailed(List.of("Issues", "label_links"), "board");

    assertThat(result.jobId()).isEqualTo(31L);
    assertThat(result.sourceTables()).containsExactly("issues", "label_links");
    assertThat(result.plannedTasks()).isEqualTo(1);
    assertThat(result.unsupportedTables()).containsExactly("label_links");
    assertThat(result.status()).isEqualTo(SyncStatus.SUCCESS);
    verify(tableSyncWorkerService).drainReadyTasksForJob(31L);
  }

  @Test
  void refreshTablesOnDemandShouldFailWhenRequestedTablesAreNotRefreshable() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("label_links", "Label links", "id", null, true));
    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.listOptionsStrict(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(config, tables, List.of("label_links"), "board"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(32L, 1, 0, 1));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.refreshTablesOnDemand(List.of("label_links"), "board"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持主动刷新");
  }

  @Test
  void refreshTablesOnDemandShouldFailWhenRequestedTablesAreOutsideWhitelist() {
    GitlabSyncConfig config = baseConfig();
    config.setEnabled(true);
    List<TableWhitelistOption> tables = List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true));
    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.listOptionsStrict(config)).thenReturn(tables);
    when(tableSyncPlanningService.createManualRefreshPlan(config, tables, List.of("unknown_table"), "board"))
        .thenReturn(new GitlabTableSyncPlanningService.CompensationPlanResult(33L, 0, 0, 0));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> syncService.refreshTablesOnDemand(List.of("unknown_table"), "board"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不支持主动刷新");
  }

  @Test
  void getProgressShouldReturnNullBecauseLegacyInMemoryProgressIsRemoved() {
    assertThat(syncService.getProgress(123L)).isNull();
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    config.setLastFullSyncAt(LocalDateTime.now().minusMinutes(10));
    return config;
  }
}
