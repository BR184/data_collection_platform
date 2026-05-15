package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
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
import com.data.collection.platform.service.sync.SyncRunStatusService;
import com.data.collection.platform.service.sync.SyncThreadBudgetResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSyncControllerTest {
  private GitlabConfigService configService;
  private SyncRunCancellationService cancellationService;
  private SyncRunStatusService statusService;
  private GitlabSyncController controller;

  @BeforeEach
  void setUp() {
    configService = mock(GitlabConfigService.class);
    cancellationService = mock(SyncRunCancellationService.class);
    statusService = mock(SyncRunStatusService.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    controller =
        new GitlabSyncController(
            configService,
            mock(GitlabMirrorSyncService.class),
            mock(GitlabWhitelistService.class),
            properties,
            mock(GitlabSystemHookService.class),
            mock(GitlabSystemHookRegistrationService.class),
            mock(GitlabMirrorPurgeService.class),
            mock(GitlabSourceHealthService.class),
            mock(GitlabExternalDbService.class),
            new SyncThreadBudgetResolver(properties),
            cancellationService,
            statusService);
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
  void shouldRouteTableSyncDiagnosticsToUnifiedStatusService() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    when(configService.getConfigById(1L)).thenReturn(config);
    when(statusService.tableDiagnostics(config))
        .thenReturn(Map.of("status", "RUNNING", "tableCount", 3));

    var response = controller.tableSyncDiagnostics(1L);

    verify(statusService).tableDiagnostics(config);
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
}
