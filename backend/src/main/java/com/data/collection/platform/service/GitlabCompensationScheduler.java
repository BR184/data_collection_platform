package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.time.Duration;
import java.time.LocalDateTime;
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

  public GitlabCompensationScheduler(
      GitlabMirrorProperties properties,
      GitlabConfigService configService,
      GitlabMirrorSyncService syncService,
      GitlabSyncTaskService taskService) {
    this.properties = properties;
    this.configService = configService;
    this.syncService = syncService;
    this.taskService = taskService;
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
    LocalDateTime latestActivityAt = taskService.resolveLatestActivityAt(config.getId());
    if (latestActivityAt == null) {
      return;
    }
    long minutes = Duration.between(latestActivityAt, LocalDateTime.now()).toMinutes();
    if (minutes >= config.getCompensationIntervalMinutes()) {
      log.info(
          "Compensation trigger accepted, configId={}, intervalMinutes={}, idleMinutes={}",
          config.getId(),
          config.getCompensationIntervalMinutes(),
          minutes);
      syncService.startCompensationSync(config);
    }
  }
}
