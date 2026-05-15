package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSystemHookRegistrationStatus;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabExternalDbService;
import com.data.collection.platform.service.GitlabMirrorPurgeService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.GitlabSourceHealthService;
import com.data.collection.platform.service.GitlabSystemHookRegistrationService;
import com.data.collection.platform.service.GitlabSystemHookService;
import com.data.collection.platform.service.GitlabWhitelistService;
import com.data.collection.platform.service.sync.SyncRunCancellationService;
import com.data.collection.platform.service.sync.SyncRunCancellationService.SyncRunCancellationResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import com.data.collection.platform.service.sync.SyncRunStatusService;
import com.data.collection.platform.service.sync.SyncRunTableDiagnosticsService;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSyncControllerTest {
  private GitlabConfigService configService;
  private GitlabMirrorSyncService syncService;
  private GitlabWhitelistService whitelistService;
  private GitlabExternalDbService externalDbService;
  private GitlabSystemHookRegistrationService systemHookRegistrationService;
  private SyncRunSubmissionService submissionService;
  private SyncRunCancellationService cancellationService;
  private SyncRunStatusService statusService;
  private SyncRunTableDiagnosticsService tableDiagnosticsService;
  private GitlabSyncController controller;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    syncService = mock(GitlabMirrorSyncService.class);
    whitelistService = mock(GitlabWhitelistService.class);
    externalDbService = mock(GitlabExternalDbService.class);
    systemHookRegistrationService = mock(GitlabSystemHookRegistrationService.class);
    submissionService = mock(SyncRunSubmissionService.class);
    cancellationService = mock(SyncRunCancellationService.class);
    statusService = mock(SyncRunStatusService.class);
    tableDiagnosticsService = mock(SyncRunTableDiagnosticsService.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    controller =
        new GitlabSyncController(
            configService,
            syncService,
            whitelistService,
            properties,
            mock(GitlabSystemHookService.class),
            systemHookRegistrationService,
            mock(GitlabMirrorPurgeService.class),
            mock(GitlabSourceHealthService.class),
            externalDbService,
            new SyncThreadBudgetResolver(properties),
            submissionService,
            cancellationService,
            statusService,
            tableDiagnosticsService);
  }

  @Test
  void shouldRouteStatusToUnifiedSyncRunStatusService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    MirrorStatusResponse unifiedStatus =
        new MirrorStatusResponse(
            config,
            Map.of("runId", "sr_1"),
            SyncStatus.RUNNING,
            "Sync run sr_1 is RUNNING",
            null,
            null,
            List.of(),
            "http://localhost/system-hook",
            null,
            8,
            4);
    when(statusService.getStatus(config)).thenReturn(unifiedStatus);

    var response = controller.status(1L);

    verify(statusService).getStatus(config);
    assertThat(response.getData().currentTask()).isEqualTo(Map.of("runId", "sr_1"));
    assertThat(response.getData().currentStatus()).isEqualTo(SyncStatus.RUNNING);
    assertThat(response.getData().currentMessage()).isEqualTo("Sync run sr_1 is RUNNING");
    assertThat(response.getData().systemHookUrl()).isEqualTo("http://localhost:18080/api/gitlab-sync/system-hook");
    assertThat(response.getData().resolvedSyncThreads()).isEqualTo(2);
  }

  @Test
  void shouldRouteFullSyncSubmissionToUnifiedSyncRunSubmissionService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(submissionService.submitFullSync(config, "Manual full sync"))
        .thenReturn(
            new SyncRunSubmissionResult(
                88L,
                SyncType.FULL,
                SyncStatus.QUEUED,
                SyncSubmissionAction.QUEUED,
                LocalDateTime.of(2026, 5, 15, 9, 0),
                "Queued"));

    var response = controller.fullSync(1L);

    verify(submissionService).submitFullSync(config, "Manual full sync");
    verify(syncService, never()).startFullSync(1L);
    assertThat(response.getData())
        .containsEntry("runId", 88L)
        .containsEntry("status", SyncStatus.QUEUED)
        .containsEntry("type", SyncType.FULL);
  }

  @Test
  void shouldRouteIncrementalSyncSubmissionToUnifiedSyncRunSubmissionService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(submissionService.submitIncrementalSync(config, null, "Manual incremental sync"))
        .thenReturn(
            new SyncRunSubmissionResult(
                89L,
                SyncType.INCREMENTAL,
                SyncStatus.QUEUED,
                SyncSubmissionAction.QUEUED,
                LocalDateTime.of(2026, 5, 15, 9, 5),
                "Queued incremental"));

    var response = controller.incrementalSync(1L);

    verify(submissionService).submitIncrementalSync(config, null, "Manual incremental sync");
    verify(syncService, never()).startIncrementalSync(1L, null, "Manual incremental sync");
    assertThat(response.getData())
        .containsEntry("runId", 89L)
        .containsEntry("status", SyncStatus.QUEUED)
        .containsEntry("type", SyncType.INCREMENTAL);
  }

  @Test
  void shouldRouteTableSyncDiagnosticsToUnifiedTableDiagnosticsService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(tableDiagnosticsService.tableDiagnostics(config))
        .thenReturn(Map.of("status", "RUNNING", "tableCount", 3));

    var response = controller.tableSyncDiagnostics(1L);

    verify(tableDiagnosticsService).tableDiagnostics(config);
    assertThat(response.getData())
        .containsEntry("status", "RUNNING")
        .containsEntry("tableCount", 3);
  }

  @Test
  void shouldRouteCancelToUnifiedSyncRunCancellationService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(cancellationService.requestCancel(1L, null, "Manual cancellation requested"))
        .thenReturn(new SyncRunCancellationResult(true, 77L, "sr_77", SyncRunStatus.CANCELLING, "Cancellation requested"));

    var response = controller.cancel(1L);

    assertThat(response.getMessage()).isEqualTo("Cancellation requested");
    assertThat(response.getData())
        .containsEntry("accepted", true)
        .containsEntry("runId", 77L)
        .containsEntry("externalRunId", "sr_77")
        .containsEntry("status", "CANCELLING")
        .containsEntry("message", "Cancellation requested");
  }

  @Test
  void shouldReportNoCancellableRunWithoutLegacyFallbackMessage() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(cancellationService.requestCancel(1L, null, "Manual cancellation requested"))
        .thenReturn(new SyncRunCancellationResult(false, null, null, null, "No cancellable sync run is available"));

    var response = controller.cancel(1L);
    Map<String, Object> data = response.getData();

    assertThat(response.getMessage()).isEqualTo("No cancellable sync run");
    assertThat(data).containsEntry("accepted", false);
    assertThat(data.get("message")).isEqualTo("No cancellable sync run is available");
  }

  @Test
  void shouldNotReportUnifiedRuntimeAsUnwiredInDiagnostics() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(whitelistService.listOptionsStrict(config)).thenReturn(List.of());
    when(externalDbService.inspectSourceMetadata(config, List.of()))
        .thenReturn(new GitlabSourceMetadataDiagnosticsResponse(true, "ok", 0, 0, 0, 0, List.of()));
    when(systemHookRegistrationService.getStatus(config, "http://localhost:18080/api/gitlab-sync/system-hook"))
        .thenReturn(
            new GitlabSystemHookRegistrationStatus(
                true,
                false,
                false,
                null,
                "http://localhost:18080/api/gitlab-sync/system-hook",
                "disabled",
                List.of()));
    GitlabSyncDiagnosticsResponse diagnostics = controller.diagnostics(1L).getData();

    assertThat(diagnostics.runtimeWarnings())
        .noneMatch(warning -> warning.contains("Legacy sync runtime") || warning.contains("not wired yet"));
  }
}
