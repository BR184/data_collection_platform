package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactBuildResponse;
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
class MergeRequestFactRealtimeRefreshServiceTest {

  @Mock private GitlabMirrorSyncService gitlabMirrorSyncService;
  @Mock private RealtimeWorkspaceService realtimeWorkspaceService;
  @Mock private FactBuildService factBuildService;

  private MergeRequestFactRealtimeRefreshService service;

  @BeforeEach
  void setUp() {
    service =
        new MergeRequestFactRealtimeRefreshService(
            gitlabMirrorSyncService,
            realtimeWorkspaceService,
            factBuildService);
  }

  @Test
  void shouldDelegateStatusLookupToRealtimeWorkspaceService() {
    RealtimeWorkspaceStatusResponse status =
        new RealtimeWorkspaceStatusResponse(
            "code-review-multi-board", true, "READY", "ok", false, null, null, null);
    when(realtimeWorkspaceService.getStatus("code-review-multi-board")).thenReturn(status);

    RealtimeWorkspaceStatusResponse response = service.getStatus("code-review-multi-board");

    assertThat(response).isSameAs(status);
  }

  @Test
  void shouldRefreshMergeRequestMirrorTablesAndRebuildMergeRequestFacts() {
    when(realtimeWorkspaceService.requestRefreshWithResult(
            eq("code-review-multi-board"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new RealtimeWorkspaceStatusResponse(
                "code-review-multi-board", true, "REFRESHING", "started", true, null, null, null));
    when(gitlabMirrorSyncService.refreshTablesOnDemandDetailed(anyList(), eq("code-review-multi-board")))
        .thenReturn(
            new GitlabMirrorSyncService.OnDemandRefreshResult(
                51L,
                List.of("merge_requests", "merge_request_metrics"),
                2,
                List.of("namespaces"),
                SyncStatus.SUCCESS,
                "mirror ok"));
    when(factBuildService.rebuildMergeRequestFacts(false))
        .thenReturn(new FactBuildResponse("merge-request", false, 11, "merge request facts ok"));

    RealtimeWorkspaceStatusResponse response = service.requestRefresh("code-review-multi-board");

    assertThat(response.refreshing()).isTrue();
    ArgumentCaptor<Supplier<RealtimeWorkspaceRefreshResult>> refreshAction =
        ArgumentCaptor.forClass(Supplier.class);
    verify(realtimeWorkspaceService).requestRefreshWithResult(eq("code-review-multi-board"), refreshAction.capture());
    RealtimeWorkspaceRefreshResult result = refreshAction.getValue().get();
    ArgumentCaptor<List<String>> sourceTables = ArgumentCaptor.forClass(List.class);
    verify(gitlabMirrorSyncService)
        .refreshTablesOnDemandDetailed(sourceTables.capture(), eq("code-review-multi-board"));
    assertThat(sourceTables.getValue())
        .containsExactly(
            "merge_requests",
            "merge_request_metrics",
            "merge_request_reviewers",
            "merge_request_assignees",
            "label_links",
            "labels",
            "projects",
            "namespaces",
            "users");
    verify(factBuildService).rebuildMergeRequestFacts(false);
    assertThat(result.jobId()).isEqualTo(51L);
    assertThat(result.sourceTables()).containsExactly("merge_requests", "merge_request_metrics");
    assertThat(result.plannedTasks()).isEqualTo(2);
    assertThat(result.unsupportedTables()).containsExactly("namespaces");
    assertThat(result.factRefreshPlanned()).isTrue();
    assertThat(result.mirrorStatus()).isEqualTo("SUCCESS");
    assertThat(result.factStatus()).isEqualTo("SUCCESS");
    assertThat(result.message()).isEqualTo("merge request facts ok");
  }
}
