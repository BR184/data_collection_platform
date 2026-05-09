package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabDailyVerificationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;

  public GitlabDailyVerificationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabTableSyncPlanningService tableSyncPlanningService) {
    this.properties = properties;
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.tableSyncPlanningService = tableSyncPlanningService;
  }

  @Scheduled(cron = "${platform.gitlab-mirror.daily-verify-cron:0 0 2 * * *}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    for (GitlabSyncConfig config : configService.listConfigs()) {
      if (config.getId() == null || !config.isEnabled() || !Boolean.TRUE.equals(config.getSourceEnabled())) {
        continue;
      }
      List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
      GitlabTableSyncPlanningService.CompensationPlanResult result =
          tableSyncPlanningService.createDailyVerificationPlan(config, tables);
      log.info(
          "Daily verification table plan queued, configId={}, jobId={}, discoveredTables={}, plannedTasks={}, verifyOnlyTables={}",
          config.getId(),
          result.jobId(),
          result.discoveredTables(),
          result.plannedTasks(),
          result.verifyOnlyTables());
    }
  }
}
