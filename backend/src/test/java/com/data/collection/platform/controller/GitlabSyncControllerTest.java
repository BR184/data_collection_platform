package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSourceTableDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.GitlabWebhookRegistrationStatus;
import com.data.collection.platform.entity.MirrorPurgeResult;
import com.data.collection.platform.entity.MirrorPurgeScope;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncProgress;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTaskSubmissionResult;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabExternalDbService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSyncLogService;
import com.data.collection.platform.service.GitlabSyncTaskService;
import com.data.collection.platform.service.GitlabWebhookRegistrationService;
import com.data.collection.platform.service.GitlabWebhookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GitlabSyncControllerTest {

  @Mock
  private GitlabConfigService configService;

  @Mock
  private GitlabMirrorSyncService syncService;

  @Mock
  private GitlabSyncLogService logService;

  @Mock
  private GitlabWhitelistService whitelistService;

  @Mock
  private GitlabWebhookService webhookService;

  @Mock
  private GitlabSyncTaskService taskService;

  @Mock
  private GitlabWebhookRegistrationService webhookRegistrationService;

  @Mock
  private GitlabMirrorPurgeService purgeService;

  @Mock
  private GitlabSourceHealthService sourceHealthService;

  @Mock
  private GitlabExternalDbService externalDbService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setWebhookBaseUrl("http://localhost:18080/api/gitlab-sync/webhook");
    GitlabSyncController controller = new GitlabSyncController(
        configService,
        syncService,
        logService,
        whitelistService,
        properties,
        webhookService,
        taskService,
        webhookRegistrationService,
        purgeService,
        sourceHealthService,
        externalDbService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void statusShouldReturnTaskDrivenProgressPayloadWhenRunning() throws Exception {
    GitlabSyncConfig config = baseConfig();
    config.setDbPassword("database-secret");
    config.setWebhookSecret("webhook-secret");
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(10L);
    task.setStatus(SyncStatus.RUNNING);
    task.setTaskType(SyncType.FULL);
    SyncProgress progress = new SyncProgress();
    progress.setPhase("FULL_SYNC");
    progress.setTotalTables(20);
    progress.setCompletedTables(5);
    progress.setSyncedRecords(120);
    progress.setCurrentTable("issues");

    when(configService.getConfig()).thenReturn(config);
    when(taskService.findDisplayTask(1L)).thenReturn(task);
    when(taskService.extractMessage(task)).thenReturn("Manual full sync");
    when(syncService.getProgress(10L)).thenReturn(progress);
    when(logService.listRecent(eq(1L), anyInt())).thenReturn(List.of());

    mockMvc.perform(get("/api/gitlab-sync/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.currentStatus").value("RUNNING"))
        .andExpect(jsonPath("$.data.currentTask.id").value(10))
        .andExpect(jsonPath("$.data.progress.phase").value("FULL_SYNC"))
        .andExpect(jsonPath("$.data.progress.totalTables").value(20))
        .andExpect(jsonPath("$.data.progress.completedTables").value(5))
        .andExpect(jsonPath("$.data.config.dbPassword").value(""))
        .andExpect(jsonPath("$.data.config.webhookSecret").value(""))
        .andExpect(jsonPath("$.data.webhookRegistration").doesNotExist());
  }

  @Test
  void saveConfigShouldKeepSourceEnabledSeparateFromAutoSyncAndSanitizeResponseSecrets() throws Exception {
    GitlabSyncConfig saved = baseConfig();
    saved.setEnabled(true);
    saved.setSourceEnabled(true);
    saved.setAutoSyncEnabled(false);
    saved.setDbPassword("database-secret");
    saved.setWebhookSecret("webhook-secret");
    saved.setWebhookEnabled(true);
    when(configService.saveConfig(argThat(config ->
        config.isEnabled()
            && Boolean.TRUE.equals(config.getSourceEnabled())
            && !config.isAutoSyncEnabled()
            && Boolean.TRUE.equals(config.getWebhookEnabled())
            && "new-database-secret".equals(config.getDbPassword())
            && "new-webhook-secret".equals(config.getWebhookSecret()))))
        .thenReturn(saved);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/gitlab-sync/config")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "GitLab default source",
                  "enabled": true,
                  "sourceEnabled": true,
                  "autoSyncEnabled": false,
                  "webhookEnabled": true,
                  "sourceMode": "DIRECT",
                  "whitelistMode": "RECOMMENDED",
                  "whitelistTables": [],
                  "dbHost": "localhost",
                  "dbPort": 5432,
                  "dbName": "gitlabhq_production",
                  "dbUsername": "gitlab",
                  "dbPassword": "new-database-secret",
                  "dockerContainerName": "gitlab-data-web-1",
                  "webhookSecret": "new-webhook-secret",
                  "webhookProjectId": 1,
                  "compensationIntervalMinutes": 10
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.autoSyncEnabled").value(false))
        .andExpect(jsonPath("$.data.enabled").value(true))
        .andExpect(jsonPath("$.data.sourceEnabled").value(true))
        .andExpect(jsonPath("$.data.webhookEnabled").value(true))
        .andExpect(jsonPath("$.data.dbPassword").value(""))
        .andExpect(jsonPath("$.data.webhookSecret").value(""));

    verify(configService, never()).saveConfig(argThat(config -> config.isAutoSyncEnabled() || !config.isEnabled()));
  }

  @Test
  void webhookRegistrationStatusShouldReturnAsyncStatusPayload() throws Exception {
    GitlabSyncConfig config = baseConfig();
    when(configService.getConfig()).thenReturn(config);
    when(webhookRegistrationService.getStatus(eq(config), eq("http://localhost:18080/api/gitlab-sync/webhook")))
        .thenReturn(new GitlabWebhookRegistrationStatus(
            true,
            true,
            false,
            1L,
            "http://localhost:18080/api/gitlab-sync/webhook",
            "尚未注册 GitLab Webhook",
            List.of()));

    mockMvc.perform(get("/api/gitlab-sync/webhook-registration-status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.registered").value(false))
        .andExpect(jsonPath("$.data.projectId").value(1));
  }

  @Test
  void sourceHealthShouldReturnPerSourceDiagnostics() throws Exception {
    when(sourceHealthService.listHealth())
        .thenReturn(List.of(new GitlabSourceHealthResponse(
            2L,
            "DGM source",
            "dgm",
            true,
            SyncStatus.IDLE,
            "",
            null,
            SyncStatus.SUCCESS,
            "同步完成",
            null,
            5,
            5,
            false,
            "",
            null,
            false,
            false,
            false,
            120,
            0,
            0,
            List.of())));

    mockMvc.perform(get("/api/gitlab-sync/source-health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].sourceInstance").value("dgm"))
        .andExpect(jsonPath("$.data[0].mergeRequestFactCount").value(120));
  }

  @Test
  void diagnosticsShouldReturnDirectConnectionAndWebhookReadiness() throws Exception {
    GitlabSyncConfig config = baseConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setSourceInstance("inner-gitlab");
    config.setWebhookEnabled(true);
    config.setWebhookSecret("direct-secret");
    when(configService.getConfig()).thenReturn(config);
    when(configService.listConfigs()).thenReturn(List.of(config));
    when(whitelistService.listOptions(config))
        .thenReturn(List.of(
            new TableWhitelistOption("issues", "issues", "id", "updated_at", true),
            new TableWhitelistOption("merge_requests", "merge_requests", "id", "updated_at", true)));
    when(externalDbService.inspectSourceMetadata(eq(config), Mockito.anyList()))
        .thenReturn(new GitlabSourceMetadataDiagnosticsResponse(
            true,
            "GitLab source metadata discovered",
            2,
            2,
            0,
            0,
            List.of(
                new GitlabSourceTableDiagnosticsResponse("issues", "id", "updated_at", "INCREMENTAL", "abc123", true),
                new GitlabSourceTableDiagnosticsResponse("merge_requests", "id", "updated_at", "INCREMENTAL", "def456", true))));
    when(webhookRegistrationService.getStatus(eq(config), eq("http://localhost:18080/api/gitlab-sync/webhook")))
        .thenReturn(new GitlabWebhookRegistrationStatus(
            false,
            true,
            false,
            1L,
            "http://localhost:18080/api/gitlab-sync/webhook",
            "直连模式不支持自动注册，但 webhook 接收入口可用",
            List.of()));

    mockMvc.perform(post("/api/gitlab-sync/diagnostics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.configId").value(1))
        .andExpect(jsonPath("$.data.sourceInstance").value("inner_gitlab"))
        .andExpect(jsonPath("$.data.sourceMode").value("DIRECT"))
        .andExpect(jsonPath("$.data.connectionOk").value(true))
        .andExpect(jsonPath("$.data.whitelistOk").value(true))
        .andExpect(jsonPath("$.data.whitelistOptionCount").value(2))
        .andExpect(jsonPath("$.data.metadataOk").value(true))
        .andExpect(jsonPath("$.data.sourceTableCount").value(2))
        .andExpect(jsonPath("$.data.missingPrimaryKeyTableCount").value(0))
        .andExpect(jsonPath("$.data.missingUpdatedAtTableCount").value(0))
        .andExpect(jsonPath("$.data.sourceTables[0].rowStrategy").value("INCREMENTAL"))
        .andExpect(jsonPath("$.data.webhookEnabled").value(true))
        .andExpect(jsonPath("$.data.webhookSecretConfigured").value(true))
        .andExpect(jsonPath("$.data.webhookSecretUnique").value(true))
        .andExpect(jsonPath("$.data.webhookConfigMessage").value("直连模式支持 Webhook 接收，但需要在 GitLab 中手动注册"))
        .andExpect(jsonPath("$.data.webhookReceiverUrl").value("http://localhost:18080/api/gitlab-sync/webhook"))
        .andExpect(jsonPath("$.data.webhookAutoRegistrationSupported").value(false))
        .andExpect(jsonPath("$.data.webhookAutoRegistered").value(false));

    verify(syncService).testConnection();
  }

  @Test
  void diagnosticsShouldReturnConnectionFailureWithoutThrowing() throws Exception {
    GitlabSyncConfig config = baseConfig();
    when(configService.getConfig()).thenReturn(config);
    Mockito.doThrow(new BizException("connection refused")).when(syncService).testConnection();
    when(whitelistService.listOptions(config)).thenReturn(List.of());
    when(externalDbService.inspectSourceMetadata(eq(config), Mockito.anyList()))
        .thenReturn(new GitlabSourceMetadataDiagnosticsResponse(
            true,
            "GitLab source metadata discovered",
            0,
            0,
            0,
            0,
            List.of()));
    when(webhookRegistrationService.getStatus(eq(config), eq("http://localhost:18080/api/gitlab-sync/webhook")))
        .thenReturn(new GitlabWebhookRegistrationStatus(
            true,
            true,
            false,
            1L,
            "http://localhost:18080/api/gitlab-sync/webhook",
            "not registered",
            List.of()));

    mockMvc.perform(post("/api/gitlab-sync/diagnostics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.connectionOk").value(false))
        .andExpect(jsonPath("$.data.connectionMessage").value("connection refused"))
        .andExpect(jsonPath("$.data.whitelistOk").value(true));
  }

  @Test
  void diagnosticsShouldReturnWebhookStatusFailureWithoutThrowing() throws Exception {
    GitlabSyncConfig config = baseConfig();
    when(configService.getConfig()).thenReturn(config);
    when(whitelistService.listOptions(config)).thenReturn(List.of());
    when(externalDbService.inspectSourceMetadata(eq(config), Mockito.anyList()))
        .thenReturn(new GitlabSourceMetadataDiagnosticsResponse(
            true,
            "GitLab source metadata discovered",
            0,
            0,
            0,
            0,
            List.of()));
    Mockito.doThrow(new BizException("docker command failed"))
        .when(webhookRegistrationService)
        .getStatus(eq(config), eq("http://localhost:18080/api/gitlab-sync/webhook"));

    mockMvc.perform(post("/api/gitlab-sync/diagnostics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.connectionOk").value(true))
        .andExpect(jsonPath("$.data.webhookAutoRegistrationSupported").value(false))
        .andExpect(jsonPath("$.data.webhookAutoRegistered").value(false))
        .andExpect(jsonPath("$.data.webhookMessage").value("docker command failed"));
  }

  @Test
  void purgeShouldDeleteMirrorDataOnly() throws Exception {
    GitlabSyncConfig config = baseConfig();
    config.setSourceInstance("cc");
    when(configService.getConfig()).thenReturn(config);
    when(purgeService.purge(MirrorPurgeScope.MIRROR_DATA_ONLY, 1L))
        .thenReturn(new MirrorPurgeResult(
            MirrorPurgeScope.MIRROR_DATA_ONLY,
            12,
            List.of("ods_gitlab_issues"),
            2,
            List.of("sys_table_registry", "gitlab_mirror_records"),
            true));

    mockMvc.perform(post("/api/gitlab-sync/purge")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "scope": "MIRROR_DATA_ONLY"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("已真实删除 cc 镜像数据，GitLab 源端和本地非镜像数据均不受影响"))
        .andExpect(jsonPath("$.data.scope").value("MIRROR_DATA_ONLY"))
        .andExpect(jsonPath("$.data.droppedMirrorTables").value(12))
        .andExpect(jsonPath("$.data.truncatedTables").value(2));
  }

  @Test
  void purgeShouldReturnWhitelistScopedMessage() throws Exception {
    GitlabSyncConfig config = baseConfig();
    config.setSourceInstance("dgm");
    when(configService.getConfig()).thenReturn(config);
    when(purgeService.purge(MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST, 1L))
        .thenReturn(new MirrorPurgeResult(
            MirrorPurgeScope.MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST,
            3,
            List.of("ods_gitlab_notes"),
            2,
            List.of("sys_table_registry", "gitlab_mirror_records"),
            false));

    mockMvc.perform(post("/api/gitlab-sync/purge")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "scope": "MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("已真实删除 dgm 当前白名单之外的镜像数据，GitLab 源端和本地非镜像数据均不受影响"))
        .andExpect(jsonPath("$.data.syncTimestampsReset").value(false));
  }

  @Test
  void cancelShouldReturnAcceptedWhenActiveTaskExists() throws Exception {
    GitlabSyncConfig config = baseConfig();
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(11L);
    task.setStatus(SyncStatus.CANCELLING);

    when(configService.getConfig()).thenReturn(config);
    when(syncService.requestCancel(1L)).thenReturn(task);

    mockMvc.perform(post("/api/gitlab-sync/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true))
        .andExpect(jsonPath("$.data.taskId").value(11))
        .andExpect(jsonPath("$.data.status").value("CANCELLING"));
  }

  @Test
  void incrementalSyncShouldReturnTaskPayload() throws Exception {
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(15L);
    task.setStatus(SyncStatus.PENDING);
    task.setTaskType(SyncType.INCREMENTAL);
    when(syncService.startIncrementalSync(SyncTriggerType.MANUAL, "Manual recovery incremental sync"))
        .thenReturn(new SyncTaskSubmissionResult(task, SyncSubmissionAction.CREATED));

    mockMvc.perform(post("/api/gitlab-sync/incremental-sync"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accepted").value(true))
        .andExpect(jsonPath("$.data.taskId").value(15))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.action").value("CREATED"));
  }

  @Test
  void fullSyncShouldReturnQueuedPayloadWhenExecutionIsDeferred() throws Exception {
    GitlabSyncTask task = new GitlabSyncTask();
    task.setId(18L);
    task.setStatus(SyncStatus.QUEUED);
    task.setTaskType(SyncType.FULL);
    when(syncService.startFullSync()).thenReturn(new SyncTaskSubmissionResult(task, SyncSubmissionAction.QUEUED));

    mockMvc.perform(post("/api/gitlab-sync/full-sync"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.accepted").value(true))
        .andExpect(jsonPath("$.data.taskId").value(18))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.action").value("QUEUED"));
  }

  @Test
  void webhookShouldDelegatePayloadToWebhookService() throws Exception {
    String payload = """
        {
          "object_kind": "issue",
          "project_id": 10,
          "object_attributes": {
            "id": 101,
            "title": "Simulated issue from webhook"
          }
        }
        """;

    mockMvc.perform(post("/api/gitlab-sync/webhook")
            .header("X-Gitlab-Event", "Issue Hook")
            .header("X-Gitlab-Token", "secret-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true));

    verify(webhookService).accept(
        eq("Issue Hook"),
        eq(Map.of(
            "object_kind", "issue",
            "project_id", 10,
            "object_attributes", Map.of("id", 101, "title", "Simulated issue from webhook"))),
        eq("secret-token"));
  }

  @Test
  void registerWebhookShouldReturnRegistrationStatus() throws Exception {
    GitlabSyncConfig config = baseConfig();
    when(configService.getConfig()).thenReturn(config);
    when(webhookRegistrationService.ensureRegistered(eq(config), eq("http://localhost:18080/api/gitlab-sync/webhook")))
        .thenReturn(new GitlabWebhookRegistrationStatus(
            true,
            true,
            true,
            1L,
            "http://localhost:18080/api/gitlab-sync/webhook",
            "GitLab Webhook 已注册",
            List.of(new GitlabWebhookRegistrationStatus.RegisteredGitlabWebhook(
                1L,
                "http://localhost:18080/api/gitlab-sync/webhook",
                true,
                true,
                true,
                true,
                true,
                true,
                false))));

    mockMvc.perform(post("/api/gitlab-sync/register-webhook"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.registered").value(true))
        .andExpect(jsonPath("$.data.projectId").value(1));
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setName("GitLab default source");
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setWebhookEnabled(false);
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    return config;
  }
}
