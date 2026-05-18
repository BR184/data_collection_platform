package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.GitlabConfigService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SyncRunFactRefreshListener {
  private static final String MIRROR_COMPLETION_REFRESH_REASON = "Mirror run completed; refresh fact layer";

  private final GitlabConfigService configService;
  private final SyncRunSubmissionService submissionService;

  public SyncRunFactRefreshListener(
      GitlabConfigService configService,
      SyncRunSubmissionService submissionService) {
    this.configService = configService;
    this.submissionService = submissionService;
  }

  @EventListener
  public void onSyncRunCompleted(SyncRunCompletionEvent event) {
    if (event == null || !event.mirrorRun() || !event.successful() || event.appliedRowCount() <= 0L) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(event.configId());
    submissionService.submitFactRefresh(
        config,
        event.runId(),
        event.fullSync(),
        MIRROR_COMPLETION_REFRESH_REASON);
  }
}
