package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabFactRefreshRequirements;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SyncRunFactRefreshListener {
  private static final String MIRROR_COMPLETION_REFRESH_REASON = "镜像同步已完成，刷新事实层";

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
    if (event == null || !event.mirrorRun() || !event.factRefreshEligible()) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(event.configId());
    if (!GitlabFactRefreshRequirements.supportsAnyFactRefresh(config)) {
      return;
    }
    submissionService.submitFactRefresh(
        config,
        event.runId(),
        event.fullSync() && event.successful(),
        MIRROR_COMPLETION_REFRESH_REASON);
  }
}
