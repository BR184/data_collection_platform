package com.data.collection.platform.service;

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

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;

  public MergeRequestFactRealtimeRefreshService(
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService) {
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    return realtimeWorkspaceService.getStatus(workspaceKey);
  }

  public RealtimeWorkspaceStatusResponse requestRefresh(String workspaceKey) {
    return realtimeWorkspaceService.requestRefreshWithResult(
        workspaceKey,
        () -> {
          RealtimeWorkspaceRefreshResult result =
              realtimeIncrementalRefreshService.requestIncrementalRefresh(workspaceKey, REALTIME_REFRESH_TABLES);
          return result;
        });
  }
}
