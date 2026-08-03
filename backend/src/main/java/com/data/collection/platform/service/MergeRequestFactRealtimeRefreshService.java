package com.data.collection.platform.service;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import org.springframework.stereotype.Service;

@Service
public class MergeRequestFactRealtimeRefreshService {
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
              realtimeIncrementalRefreshService.requestIncrementalRefresh(
                  com.data.collection.platform.entity.WorkspaceRefreshRequest.global(workspaceKey));
          return result;
        });
  }
}
