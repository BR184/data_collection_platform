package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.service.GitlabConfigService;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SyncRunFactRefreshListener {
  private static final String MIRROR_COMPLETION_REFRESH_REASON = "镜像同步已完成，刷新事实层";
  private static final List<String> FACT_REFRESH_REQUIRED_TABLES =
      List.of("issues", "projects", "users", "labels", "label_links", "notes", "merge_requests");

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
    if (!hasFactRefreshSourceTables(config)) {
      return;
    }
    submissionService.submitFactRefresh(
        config,
        event.runId(),
        event.fullSync(),
        MIRROR_COMPLETION_REFRESH_REASON);
  }

  private boolean hasFactRefreshSourceTables(GitlabSyncConfig config) {
    if (config == null || config.getWhitelistMode() != WhitelistMode.CUSTOM) {
      return true;
    }
    List<String> whitelist = config.getWhitelistTables();
    if (whitelist == null || whitelist.isEmpty()) {
      return false;
    }
    List<String> normalized = whitelist.stream()
        .filter(table -> table != null && !table.isBlank())
        .map(table -> table.trim().toLowerCase())
        .toList();
    return normalized.containsAll(FACT_REFRESH_REQUIRED_TABLES);
  }
}
