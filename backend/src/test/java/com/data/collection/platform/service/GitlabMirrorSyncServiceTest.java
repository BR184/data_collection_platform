package com.data.collection.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
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
import java.util.Map;
import org.mockito.ArgumentCaptor;
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
  private FactBuildService factBuildService;
  private IntegrationTestFactBuildService integrationTestFactBuildService;
  private GitlabMirrorSyncService selfProxy;
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
    factBuildService = mock(FactBuildService.class);
    integrationTestFactBuildService = mock(IntegrationTestFactBuildService.class);
    selfProxy = mock(GitlabMirrorSyncService.class);
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
            factBuildService,
            integrationTestFactBuildService,
            selfProxy);
  }

  @Test
  void fullSyncShouldDispatchAsyncExecutionWhenTaskIsPending() {
    GitlabSyncConfig config = baseConfig();
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(100L);
    task.setStatus(SyncStatus.PENDING);

    when(configService.getConfig()).thenReturn(config);
    when(taskService.submitTaskResult(config, SyncType.FULL, SyncTriggerType.MANUAL, "Manual full sync", Map.of()))
        .thenReturn(new SyncTaskSubmissionResult(task, SyncSubmissionAction.CREATED));

    syncService.startFullSync();

    verify(selfProxy).executeTaskAsync(100L);
  }

  @Test
  void incrementalSyncShouldNotDispatchWhenTaskIsQueued() {
    GitlabSyncConfig config = baseConfig();
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(101L);
    task.setStatus(SyncStatus.QUEUED);

    when(configService.getConfig()).thenReturn(config);
    when(taskService.submitTaskResult(config, SyncType.INCREMENTAL, SyncTriggerType.WEBHOOK, "Triggered by webhook", Map.of()))
        .thenReturn(new SyncTaskSubmissionResult(task, SyncSubmissionAction.QUEUED));

    syncService.startIncrementalSync(SyncTriggerType.WEBHOOK, "Triggered by webhook");

    verify(selfProxy, org.mockito.Mockito.never()).executeTaskAsync(anyLong());
  }

  @Test
  void recoverTimedOutTasksShouldDelegateToTaskService() {
    syncService.recoverTimedOutTasks();

    verify(taskService).recoverTimedOutTasks();
  }

  @Test
  void compensationSyncShouldUseWindowedScanForTablesWithTimeColumn() {
    GitlabSyncTask task = runningTask(SyncType.COMPENSATION);
    GitlabSyncConfig config = baseConfig();
    config.setCompensationIntervalMinutes(30);
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);

    when(taskService.claimPendingTask(eq(200L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_issues", true, null));
    when(taskService.extractMessage(task)).thenReturn("Scheduled compensation sync");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(externalDbService.compensationScan(any(), any(), any())).thenReturn(List.of());
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(200L);

    verify(externalDbService).compensationScan(any(), any(), any());
    verify(factBuildService).rebuildAllFactsForConfig(config, false);
    verify(integrationTestFactBuildService).rebuildFactsForConfig(config, false);
    verify(externalDbService, never()).fullTableScan(any(), any());
    verify(externalDbService, never()).incrementalScan(any(), any(), any());
  }

  @Test
  void compensationSyncShouldSkipTablesWithoutTimeColumn() {
    GitlabSyncTask task = runningTask(SyncType.COMPENSATION);
    GitlabSyncConfig config = baseConfig();
    config.setCompensationIntervalMinutes(30);
    TableWhitelistOption option = new TableWhitelistOption("events", "events", "id", null, false);

    when(taskService.claimPendingTask(eq(201L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_events", true, null));
    when(taskService.extractMessage(task)).thenReturn("Scheduled compensation sync");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(201L);

    verify(externalDbService, never()).compensationScan(any(), any(), any());
    verify(logService).finish(eq(1L), eq(SyncStatus.SUCCESS), contains("跳过了 1 张缺少时间列的表"), eq(1), eq(0));
  }

  @Test
  void incrementalSyncShouldUseLastFullSyncTimeWithLookbackWhenIncrementalBaselineIsMissing() {
    GitlabSyncTask task = runningTask(SyncType.INCREMENTAL);
    GitlabSyncConfig config = baseConfig();
    LocalDateTime lastFullSyncAt = LocalDateTime.now().minusMinutes(15);
    config.setLastFullSyncAt(lastFullSyncAt);
    TableWhitelistOption option = new TableWhitelistOption("merge_trains", "merge_trains", "id", "updated_at", true);

    when(taskService.claimPendingTask(eq(202L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_merge_trains", true, null));
    when(taskService.extractMessage(task)).thenReturn("Manual incremental sync");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(externalDbService.incrementalScan(any(), any(), any())).thenReturn(List.of());
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(202L);

    ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(externalDbService).incrementalScan(eq(config), eq(option), sinceCaptor.capture());
    org.assertj.core.api.Assertions.assertThat(sinceCaptor.getValue()).isEqualTo(lastFullSyncAt.minusMinutes(5));
  }

  @Test
  void incrementalSyncShouldSkipTablesWithoutTimeColumn() {
    GitlabSyncTask task = runningTask(SyncType.INCREMENTAL);
    GitlabSyncConfig config = baseConfig();
    config.setLastFullSyncAt(LocalDateTime.now().minusMinutes(20));
    TableWhitelistOption option = new TableWhitelistOption("events", "events", "id", null, false);

    when(taskService.claimPendingTask(eq(203L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_events", true, null));
    when(taskService.extractMessage(task)).thenReturn("Manual incremental sync");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(203L);

    verify(externalDbService, never()).incrementalScan(any(), any(), any());
    verify(externalDbService, never()).fullTableScan(any(), any());
    verify(logService).finish(eq(1L), eq(SyncStatus.SUCCESS), eq("同步已完成"), eq(1), eq(0));
  }

  @Test
  void webhookSyncShouldUsePreciseTargetsInsteadOfIncrementalScan() {
    GitlabSyncTask task = runningTask(SyncType.WEBHOOK);
    task.setPayloadJson("{\"message\":\"Triggered by webhook: issue\",\"webhookPayload\":{}}");
    GitlabSyncConfig config = baseConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    List<GitlabWebhookPreciseSyncTarget> targets = List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L));

    when(taskService.claimPendingTask(eq(204L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_issues", true, null));
    when(taskService.extractMessage(task)).thenReturn("Triggered by webhook: issue");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(jsonUtils.toMap(task.getPayloadJson())).thenReturn(Map.of("message", "Triggered by webhook: issue", "webhookPayload", Map.of("object_kind", "issue")));
    when(webhookPreciseSyncPlanner.planTargets(Map.of("object_kind", "issue"))).thenReturn(targets);
    when(externalDbService.preciseScan(any(), any(), eq("id"), eq(101L))).thenReturn(List.of());
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(204L);

    verify(externalDbService).preciseScan(eq(config), eq(option), eq("id"), eq(101L));
    verify(externalDbService, never()).incrementalScan(any(), any(), any());
    verify(externalDbService, never()).fullTableScan(any(), any());
  }

  @Test
  void webhookPreciseSyncShouldWorkWithDirectSourceMode() {
    GitlabSyncConfig config = baseConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost("gitlab.internal");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab_ro");
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    Map<String, Object> payload = Map.of("object_kind", "issue");
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L));

    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(webhookPreciseSyncPlanner.plan(payload))
        .thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", targets));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(
            new GitlabMirrorSchemaService.PreparedMirrorTable(
                sourceSchema(option), "ods_gitlab_issues", true, null));
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(externalDbService.preciseScan(config, option, "id", 101L)).thenReturn(List.of());

    syncService.executeRealtimeWebhookSync(config, payload, "101");

    verify(externalDbService).preciseScan(eq(config), eq(option), eq("id"), eq(101L));
    verify(factBuildService).rebuildAllFactsForConfig(config, false);
    verify(integrationTestFactBuildService).rebuildFactsForConfig(config, false);
    verify(externalDbService, never()).incrementalScan(any(), any(), any());
    verify(externalDbService, never()).fullTableScan(any(), any());
  }

  @Test
  void realtimeWebhookShouldFallbackToIncrementalWhenPreciseTargetsAreOutsideWhitelist() {
    GitlabSyncConfig config = baseConfig();
    Map<String, Object> payload = Map.of("object_kind", "release");
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(new GitlabWebhookPreciseSyncTarget("releases", "id", 707L));
    GitlabSyncTask queuedTask = new GitlabSyncTask();
    queuedTask.setId(300L);
    queuedTask.setStatus(SyncStatus.QUEUED);

    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config))
        .thenReturn(List.of(new TableWhitelistOption("issues", "issues", "id", "updated_at", true)));
    when(webhookPreciseSyncPlanner.plan(payload))
        .thenReturn(new GitlabWebhookPreciseSyncPlan("release:707", "707", targets));
    when(taskService.submitTaskResult(
            eq(config),
            eq(SyncType.INCREMENTAL),
            eq(SyncTriggerType.WEBHOOK),
            contains("fallback incremental sync"),
            eq(Map.of())))
        .thenReturn(new SyncTaskSubmissionResult(queuedTask, SyncSubmissionAction.QUEUED));

    syncService.executeRealtimeWebhookSync(config, payload, "707");

    verify(taskService).submitTaskResult(
        eq(config),
        eq(SyncType.INCREMENTAL),
        eq(SyncTriggerType.WEBHOOK),
        contains("fallback incremental sync"),
        eq(Map.of()));
    verify(externalDbService, never()).preciseScan(any(), any(), anyString(), any());
    verify(logService, never()).start(anyLong(), any(), any(), anyString());
    verify(factBuildService, never()).rebuildAllFactsForConfig(any(), any(Boolean.class));
  }

  @Test
  void realtimeWebhookShouldSyncWhitelistedTargetsAndSkipOthers() {
    GitlabSyncConfig config = baseConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    Map<String, Object> payload = Map.of("object_kind", "issue");
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(
            new GitlabWebhookPreciseSyncTarget("issues", "id", 101L),
            new GitlabWebhookPreciseSyncTarget("releases", "id", 707L));

    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(webhookPreciseSyncPlanner.plan(payload))
        .thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", targets));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(
            new GitlabMirrorSchemaService.PreparedMirrorTable(
                sourceSchema(option), "ods_gitlab_issues", true, null));
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(externalDbService.preciseScan(config, option, "id", 101L)).thenReturn(List.of());

    syncService.executeRealtimeWebhookSync(config, payload, "101");

    verify(externalDbService).preciseScan(eq(config), eq(option), eq("id"), eq(101L));
    verify(taskService, never()).submitTaskResult(any(), eq(SyncType.INCREMENTAL), any(), anyString(), any());
  }

  @Test
  void realtimeDeleteWebhookShouldMarkMirrorRowsDeletedInsteadOfQueryingSource() {
    GitlabSyncConfig config = baseConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    Map<String, Object> payload =
        Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "action", "delete"));
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L));
    SourceTableSchema schema = sourceSchema(option);

    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(webhookPreciseSyncPlanner.plan(payload))
        .thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", targets));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(schema, "ods_gitlab_issues", true, null));
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(mirrorTableStorageService.markRowsDeleted(schema, "id", 101L, null)).thenReturn(1);

    syncService.executeRealtimeWebhookSync(config, payload, "101");

    verify(mirrorTableStorageService).markRowsDeleted(eq(schema), eq("id"), eq(101L), eq(null));
    verify(externalDbService, never()).preciseScan(any(), any(), anyString(), any());
    verify(factBuildService).rebuildAllFactsForConfig(config, false);
  }

  @Test
  void nonDeleteWebhookShouldNotMarkRowsDeletedWhenPreciseQueryReturnsNoRows() {
    GitlabSyncConfig config = baseConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    Map<String, Object> payload =
        Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "action", "update"));
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L));
    SourceTableSchema schema = sourceSchema(option);

    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(webhookPreciseSyncPlanner.plan(payload))
        .thenReturn(new GitlabWebhookPreciseSyncPlan("issue:101", "101", targets));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(schema, "ods_gitlab_issues", true, null));
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(externalDbService.preciseScan(config, option, "id", 101L)).thenReturn(List.of());

    syncService.executeRealtimeWebhookSync(config, payload, "101");

    verify(externalDbService).preciseScan(eq(config), eq(option), eq("id"), eq(101L));
    verify(mirrorTableStorageService, never()).markRowsDeleted(any(), anyString(), any(), any());
  }

  @Test
  void unsupportedWebhookShouldFallbackToIncrementalWindowScan() {
    GitlabSyncTask task = runningTask(SyncType.WEBHOOK);
    task.setPayloadJson("{\"message\":\"Triggered by webhook: push\",\"webhookPayload\":{}}");
    GitlabSyncConfig config = baseConfig();
    config.setLastFullSyncAt(LocalDateTime.now().minusMinutes(10));
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);

    when(taskService.claimPendingTask(eq(205L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(sourceSchema(option), "ods_gitlab_issues", true, null));
    when(taskService.extractMessage(task)).thenReturn("Triggered by webhook: push");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(jsonUtils.toMap(task.getPayloadJson())).thenReturn(Map.of("message", "Triggered by webhook: push", "webhookPayload", Map.of("object_kind", "push")));
    when(webhookPreciseSyncPlanner.planTargets(Map.of("object_kind", "push"))).thenReturn(List.of());
    when(externalDbService.incrementalScan(any(), any(), any())).thenReturn(List.of());
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(205L);

    verify(externalDbService).incrementalScan(eq(config), eq(option), any());
    verify(externalDbService, never()).preciseScan(any(), any(), anyString(), any());
  }

  @Test
  void webhookTaskShouldFallbackToIncrementalWhenPreciseTargetsAreOutsideWhitelist() {
    GitlabSyncTask task = runningTask(SyncType.WEBHOOK);
    task.setPayloadJson("{\"message\":\"Triggered by webhook: release\",\"webhookPayload\":{}}");
    GitlabSyncConfig config = baseConfig();
    config.setLastFullSyncAt(LocalDateTime.now().minusMinutes(10));
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);

    when(taskService.claimPendingTask(eq(206L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(
            new GitlabMirrorSchemaService.PreparedMirrorTable(
                sourceSchema(option), "ods_gitlab_issues", true, null));
    when(taskService.extractMessage(task)).thenReturn("Triggered by webhook: release");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(jsonUtils.toMap(task.getPayloadJson()))
        .thenReturn(Map.of("message", "Triggered by webhook: release", "webhookPayload", Map.of("object_kind", "release")));
    when(webhookPreciseSyncPlanner.planTargets(Map.of("object_kind", "release")))
        .thenReturn(List.of(new GitlabWebhookPreciseSyncTarget("releases", "id", 707L)));
    when(externalDbService.incrementalScan(any(), any(), any())).thenReturn(List.of());
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(206L);

    verify(externalDbService).incrementalScan(eq(config), eq(option), any());
    verify(externalDbService, never()).preciseScan(any(), any(), anyString(), any());
  }

  @Test
  void webhookTaskDeleteEventShouldMarkMirrorRowsDeleted() {
    GitlabSyncTask task = runningTask(SyncType.WEBHOOK);
    task.setPayloadJson("{\"message\":\"Triggered by webhook: issue delete\",\"webhookPayload\":{}}");
    GitlabSyncConfig config = baseConfig();
    TableWhitelistOption option = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
    Map<String, Object> webhookPayload =
        Map.of("object_kind", "issue", "object_attributes", Map.of("id", 101L, "action", "delete"));
    List<GitlabWebhookPreciseSyncTarget> targets =
        List.of(new GitlabWebhookPreciseSyncTarget("issues", "id", 101L));
    SourceTableSchema schema = sourceSchema(option);

    when(taskService.claimPendingTask(eq(207L), anyString())).thenReturn(task);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.resolveOptions(config)).thenReturn(List.of(option));
    when(mirrorSchemaService.getPreparedMirrorTableForSync(config, option))
        .thenReturn(new GitlabMirrorSchemaService.PreparedMirrorTable(schema, "ods_gitlab_issues", true, null));
    when(taskService.extractMessage(task)).thenReturn("Triggered by webhook: issue delete");
    when(logService.start(anyLong(), any(), any(), anyString())).thenReturn(1L);
    when(jsonUtils.toMap(task.getPayloadJson()))
        .thenReturn(Map.of("message", "Triggered by webhook: issue delete", "webhookPayload", webhookPayload));
    when(webhookPreciseSyncPlanner.planTargets(webhookPayload)).thenReturn(targets);
    when(mirrorTableStorageService.markRowsDeleted(schema, "id", 101L, 1L)).thenReturn(1);
    when(taskService.promoteNextQueued(anyString())).thenReturn(null);

    syncService.executeTaskAsync(207L);

    verify(mirrorTableStorageService).markRowsDeleted(eq(schema), eq("id"), eq(101L), eq(1L));
    verify(externalDbService, never()).preciseScan(any(), any(), anyString(), any());
    verify(factBuildService).rebuildAllFactsForConfig(config, false);
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    return config;
  }

  private GitlabSyncTask runningTask(SyncType type) {
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(1L);
    task.setConfigId(1L);
    task.setScopeKey("gitlab:docker:" + type.name());
    task.setTaskType(type);
    task.setStatus(SyncStatus.RUNNING);
    task.setStartedAt(LocalDateTime.now());
    return task;
  }

  private SourceTableSchema sourceSchema(TableWhitelistOption option) {
    return new SourceTableSchema(
        "ods_gitlab_" + option.tableName(),
        List.of("id"),
        option.updatedAtColumn(),
        List.of(new SourceTableColumn("id", "bigint", false, 1)));
  }
}
