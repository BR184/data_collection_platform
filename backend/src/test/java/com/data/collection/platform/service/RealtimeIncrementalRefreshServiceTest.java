package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.WorkspaceRefreshRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeIncrementalRefreshServiceTest {

  @Mock private GitlabMirrorSyncService gitlabMirrorSyncService;

  @Test
  void shouldSubmitOnlyAvailableRealtimeTables() {
    RealtimeIncrementalRefreshService service =
        new RealtimeIncrementalRefreshService(gitlabMirrorSyncService);
    List<String> requiredTables =
        RealtimeWorkspaceDependencyCatalog.require("customer-issue-cc-product-records")
            .sourceTables();
    GitlabMirrorSyncService.OnDemandRefreshResult submission =
        new GitlabMirrorSyncService.OnDemandRefreshResult(
            88L,
            List.of("issues", "label_links"),
            2,
            List.of("resource_label_events"),
            SyncStatus.QUEUED,
            "queued");
    WorkspaceRefreshRequest request =
        WorkspaceRefreshRequest.global("customer-issue-cc-product-records");
    when(gitlabMirrorSyncService.refreshAvailableTablesOnDemandDetailed(
            requiredTables,
            "customer-issue-cc-product-records",
            request,
            "REALTIME_WORKSPACE_REFRESH"))
        .thenReturn(submission);

    RealtimeWorkspaceRefreshResult result =
        service.requestIncrementalRefresh(request);

    verify(gitlabMirrorSyncService)
        .refreshAvailableTablesOnDemandDetailed(
            requiredTables,
            "customer-issue-cc-product-records",
            request,
            "REALTIME_WORKSPACE_REFRESH");
    assertThat(result.jobId()).isEqualTo(88L);
    assertThat(result.sourceTables()).containsExactly("issues", "label_links");
    assertThat(result.unsupportedTables()).containsExactly("resource_label_events");
  }

  @Test
  void shouldRejectRealtimeRefreshWhenNoRequiredTableCanBeSubmitted() {
    RealtimeIncrementalRefreshService service =
        new RealtimeIncrementalRefreshService(gitlabMirrorSyncService);
    List<String> requiredTables =
        RealtimeWorkspaceDependencyCatalog.require("customer-issue-cc-product-records")
            .sourceTables();
    WorkspaceRefreshRequest request =
        WorkspaceRefreshRequest.global("customer-issue-cc-product-records");
    when(gitlabMirrorSyncService.refreshAvailableTablesOnDemandDetailed(
            requiredTables,
            "customer-issue-cc-product-records",
            request,
            "REALTIME_WORKSPACE_REFRESH"))
        .thenReturn(
            new GitlabMirrorSyncService.OnDemandRefreshResult(
                null,
                List.of(),
                0,
                List.of("issues"),
                SyncStatus.SUCCESS,
                "没有可提交的源表"));

    assertThatThrownBy(
            () -> service.requestIncrementalRefresh(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("没有可用于增量刷新的页面相关源表");
  }
}
