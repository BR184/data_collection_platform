package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MergeRequestFactRealtimeRefreshService {
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of(
          "merge_requests",
          "merge_request_metrics",
          "merge_request_reviewers",
          "merge_request_assignees",
          "label_links",
          "labels",
          "projects",
          "namespaces",
          "users");

  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final FactBuildService factBuildService;

  public MergeRequestFactRealtimeRefreshService(
      GitlabMirrorSyncService gitlabMirrorSyncService,
      RealtimeWorkspaceService realtimeWorkspaceService,
      FactBuildService factBuildService) {
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.factBuildService = factBuildService;
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    return realtimeWorkspaceService.getStatus(workspaceKey);
  }

  public RealtimeWorkspaceStatusResponse requestRefresh(String workspaceKey) {
    return realtimeWorkspaceService.requestRefreshWithResult(
        workspaceKey,
        () -> {
          GitlabMirrorSyncService.OnDemandRefreshResult mirrorResult =
              gitlabMirrorSyncService.refreshTablesOnDemandDetailed(
                  REALTIME_REFRESH_TABLES, workspaceKey);
          FactBuildResponse factResult = factBuildService.rebuildMergeRequestFacts(false);
          return new RealtimeWorkspaceRefreshResult(
              mirrorResult.jobId(),
              mirrorResult.sourceTables(),
              mirrorResult.plannedTasks(),
              mirrorResult.unsupportedTables(),
              true,
              mirrorResult.status().name(),
              "SUCCESS",
              factResult.message());
        });
  }
}
