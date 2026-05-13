package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncJobType;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabCompensationScheduler {
  private final GitlabMirrorProperties properties;
  private final GitlabConfigService configService;
  private final GitlabMirrorSyncService syncService;
  private final GitlabSyncTaskService taskService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabTableSyncPlanningService tableSyncPlanningService;

  public GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabSyncTaskService taskService,
      GitlabWhitelistService whitelistService,
      GitlabTableSyncPlanningService tableSyncPlanningService) {
    this.properties = properties;
    this.configService = configService;
    this.syncService = syncService;
    this.taskService = taskService;
    this.whitelistService = whitelistService;
    this.tableSyncPlanningService = tableSyncPlanningService;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.scheduler-delay-ms:60000}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    syncService.recoverTimedOutTasks();
    for (GitlabSyncConfig config : configService.listConfigs()) {
      runForConfig(config);
    }
  }

  private void runForConfig(GitlabSyncConfig config) {
    if (config.getId() == null || !config.isEnabled() || !config.isAutoSyncEnabled()) {
      return;
    }
    if (syncService.hasActiveTask(config.getId()) || taskService.isInCooldown(config.getId())) {
      log.debug("Compensation skipped because task is active or cooldown is in effect, configId={}", config.getId());
      return;
    }
    if (tableSyncPlanningService.hasActiveJob(config.getId(), GitlabSyncJobType.COMPENSATION_SCAN)) {
      log.debug("Compensation skipped because table-level compensation job is already pending or running, configId={}", config.getId());
      return;
    }
    LocalDateTime latestActivityAt = tableSyncPlanningService.resolveLatestActivityAt(config.getId());
    if (latestActivityAt == null) {
      latestActivityAt = taskService.resolveLatestActivityAt(config.getId());
    }
    long minutes = latestActivityAt == null ? Long.MAX_VALUE : Duration.between(latestActivityAt, LocalDateTime.now()).toMinutes();
    if (latestActivityAt == null || minutes >= config.getCompensationIntervalMinutes()) {
      log.info(
          "Compensation trigger accepted, configId={}, intervalMinutes={}, idleMinutes={}",
          config.getId(),
          config.getCompensationIntervalMinutes(),
          minutes);
      List<TableWhitelistOption> tables = whitelistService.resolveOptions(config);
      GitlabTableSyncPlanningService.CompensationPlanResult result =
          tableSyncPlanningService.createCompensationScanPlan(config, tables);
      log.info(
          "Compensation table plan queued, configId={}, jobId={}, discoveredTables={}, plannedTasks={}, verifyOnlyTables={}",
          config.getId(),
          result.jobId(),
          result.discoveredTables(),
          result.plannedTasks(),
          result.verifyOnlyTables());
    }
  }
}
