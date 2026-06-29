package com.data.collection.platform.service;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.SyncStatus;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RealtimeIncrementalRefreshService {
  private final GitlabMirrorSyncService gitlabMirrorSyncService;

  public RealtimeIncrementalRefreshService(GitlabMirrorSyncService gitlabMirrorSyncService) {
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
  }

  public RealtimeWorkspaceRefreshResult requestIncrementalRefresh(String reason, List<String> coveredSourceTables) {
    GitlabMirrorSyncService.OnDemandRefreshResult submission =
        gitlabMirrorSyncService.refreshTablesOnDemandDetailed(
            coveredSourceTables,
            reason,
            reason,
            "REALTIME_WORKSPACE_REFRESH");
    String mirrorStatus = normalizeMirrorStatus(submission.status());
    return new RealtimeWorkspaceRefreshResult(
        submission.jobId(),
        submission.sourceTables(),
        submission.plannedTasks(),
        submission.unsupportedTables(),
        true,
        mirrorStatus,
        "QUEUED",
        buildMessage(submission));
  }

  private String normalizeMirrorStatus(SyncStatus status) {
    if (status == null) {
      return SyncStatus.QUEUED.name();
    }
    return status.name();
  }

  private String buildMessage(GitlabMirrorSyncService.OnDemandRefreshResult submission) {
    String message = submission.message();
    if (message != null && !message.isBlank()) {
      return message;
    }
    if (submission.sourceTables().isEmpty()) {
      return "没有需要刷新的源表。";
    }
    return "已提交页面相关源表增量刷新，事实层将在镜像同步完成后自动刷新。";
  }
}
