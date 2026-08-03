package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.WorkspaceRefreshRequest;
import org.springframework.stereotype.Service;

@Service
public class RealtimeIncrementalRefreshService {
  private final GitlabMirrorSyncService gitlabMirrorSyncService;

  public RealtimeIncrementalRefreshService(GitlabMirrorSyncService gitlabMirrorSyncService) {
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
  }

  public RealtimeWorkspaceRefreshResult requestIncrementalRefresh(WorkspaceRefreshRequest request) {
    RealtimeWorkspaceDependencyCatalog.WorkspaceDependency dependency =
        RealtimeWorkspaceDependencyCatalog.require(request.workspaceKey());
    GitlabMirrorSyncService.OnDemandRefreshResult submission =
        gitlabMirrorSyncService.refreshAvailableTablesOnDemandDetailed(
            dependency.sourceTables(),
            request.workspaceKey(),
            request,
            "REALTIME_WORKSPACE_REFRESH");
    if (submission.sourceTables().isEmpty()) {
      throw new BizException(
          "当前数据源没有可用于增量刷新的页面相关源表，请先完成相应表的全量同步。");
    }
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
    if (submission.sourceTables().isEmpty()) {
      return "没有需要刷新的源表。";
    }
    if (!submission.unsupportedTables().isEmpty()) {
      return "已提交可用的页面相关源表增量刷新，以下补充源表未配置或尚未完成基线："
          + String.join("、", submission.unsupportedTables());
    }
    String message = submission.message();
    if (message != null && !message.isBlank()) {
      return message;
    }
    return "已提交页面相关源表增量刷新，事实层将在镜像同步完成后自动刷新。";
  }
}
