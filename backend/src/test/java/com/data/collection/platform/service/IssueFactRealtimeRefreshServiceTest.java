package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.SyncStatus;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssueFactRealtimeRefreshServiceTest {

  @Mock private GitlabMirrorSyncService gitlabMirrorSyncService;
  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;

  private IssueFactRealtimeRefreshService service;

  @BeforeEach
  void setUp() {
    service =
        new IssueFactRealtimeRefreshService(
            realtimeWorkspaceService,
            realtimeIncrementalRefreshService);
  }

  @Test
  void shouldDelegateStatusLookupToRealtimeWorkspaceService() {
    RealtimeWorkspaceStatusResponse status =
        new RealtimeWorkspaceStatusResponse("system-test-issues", true, "READY", "ok", false, null, null, null);
    when(realtimeWorkspaceService.getStatus("system-test-issues")).thenReturn(status);

    RealtimeWorkspaceStatusResponse response = service.getStatus("system-test-issues");

    assertThat(response).isSameAs(status);
  }

  @Test
  void shouldSubmitIncrementalRefreshForIssueMirrorTables() {
    when(realtimeWorkspaceService.requestRefreshWithResult(eq("system-test-issues"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new RealtimeWorkspaceStatusResponse(
                "system-test-issues", true, "REFRESHING", "started", true, null, null, null));
    when(realtimeIncrementalRefreshService.requestIncrementalRefresh(eq("system-test-issues"), anyList()))
        .thenReturn(
            new RealtimeWorkspaceRefreshResult(
                41L,
                List.of("issues", "projects"),
                2,
                List.of("notes"),
                true,
                SyncStatus.QUEUED.name(),
                "QUEUED",
                "mirror ok"));

    RealtimeWorkspaceStatusResponse response = service.requestRefresh("system-test-issues");

    assertThat(response.refreshing()).isTrue();
    ArgumentCaptor<Supplier<RealtimeWorkspaceRefreshResult>> refreshAction =
        ArgumentCaptor.forClass(Supplier.class);
    verify(realtimeWorkspaceService).requestRefreshWithResult(eq("system-test-issues"), refreshAction.capture());
    RealtimeWorkspaceRefreshResult result = refreshAction.getValue().get();
    ArgumentCaptor<List<String>> sourceTables = ArgumentCaptor.forClass(List.class);
    verify(realtimeIncrementalRefreshService)
        .requestIncrementalRefresh(eq("system-test-issues"), sourceTables.capture());
    assertThat(sourceTables.getValue())
        .containsExactly(
            "issues", "projects", "users", "label_links", "resource_label_events", "labels", "notes");
    assertThat(result.jobId()).isEqualTo(41L);
    assertThat(result.sourceTables()).containsExactly("issues", "projects");
    assertThat(result.plannedTasks()).isEqualTo(2);
    assertThat(result.unsupportedTables()).containsExactly("notes");
    assertThat(result.factRefreshPlanned()).isTrue();
    assertThat(result.mirrorStatus()).isEqualTo("QUEUED");
    assertThat(result.factStatus()).isEqualTo("QUEUED");
    assertThat(result.message()).isEqualTo("mirror ok");
  }
}
