package com.data.collection.platform.service;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IssueFactRealtimeRefreshService {
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "resource_label_events", "labels", "notes");

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;

  public IssueFactRealtimeRefreshService(
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService) {
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey) {
    return realtimeWorkspaceService.getStatus(workspaceKey);
  }

  public RealtimeWorkspaceStatusResponse getStatus(String workspaceKey, Map<String, String> filters) {
    return realtimeWorkspaceService.getStatus(workspaceKey, filters);
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
