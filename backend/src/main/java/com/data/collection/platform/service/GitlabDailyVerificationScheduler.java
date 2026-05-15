package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GitlabDailyVerificationScheduler {
  private final GitlabMirrorProperties properties;

  public GitlabDailyVerificationScheduler(GitlabMirrorProperties properties) {
    this.properties = properties;
  }

  @Scheduled(cron = "${platform.gitlab-mirror.daily-verify-cron:0 0 2 * * *}")
  public void run() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    log.debug("Daily verification scheduling is disabled until the unified run orchestrator is wired.");
  }
}
