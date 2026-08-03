package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabFactDependencyCatalog;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SyncRunFactRefreshListener {
  private static final String MIRROR_COMPLETION_REFRESH_REASON = "镜像同步已完成，刷新事实层";

  private final GitlabConfigService configService;
  private final SyncRunFactPublicationCoordinator factPublicationCoordinator;

  public SyncRunFactRefreshListener(
      GitlabConfigService configService,
      SyncRunFactPublicationCoordinator factPublicationCoordinator) {
    this.configService = configService;
    this.factPublicationCoordinator = factPublicationCoordinator;
  }

  @EventListener
  public void onSyncRunCompleted(SyncRunCompletionEvent event) {
    if (event == null || !event.mirrorRun()) {
      return;
    }
    GitlabSyncConfig config = configService.getConfigById(event.configId());
    if (!GitlabFactDependencyCatalog.supportsAnyFactRefresh(config)) {
      return;
    }
    factPublicationCoordinator.ensurePublication(
        event.runId(),
        event.requiresFullFactRefresh() && event.successful(),
        MIRROR_COMPLETION_REFRESH_REASON);
  }
}
