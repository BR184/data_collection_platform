package com.data.collection.platform.service;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RealtimeIncrementalRefreshService {
  private final GitlabMirrorSyncService gitlabMirrorSyncService;

  public RealtimeIncrementalRefreshService(GitlabMirrorSyncService gitlabMirrorSyncService) {
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
  }

  public RealtimeWorkspaceRefreshResult requestIncrementalRefresh(String reason, List<String> coveredSourceTables) {
    SyncRunSubmissionResult submission =
        gitlabMirrorSyncService.startIncrementalSync(SyncTriggerType.MANUAL, reason);
    String mirrorStatus = normalizeMirrorStatus(submission.status());
    return new RealtimeWorkspaceRefreshResult(
        submission.runId(),
        coveredSourceTables,
        0,
        List.of(),
        true,
        mirrorStatus,
        "QUEUED",
        submission.message() == null || submission.message().isBlank()
            ? "已提交增量同步，事实层将在镜像同步完成后自动刷新"
            : submission.message());
  }

  private String normalizeMirrorStatus(SyncStatus status) {
    if (status == null) {
      return SyncStatus.QUEUED.name();
    }
    return status.name();
  }
}
