package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期收敛已失租的事实投影任务，包括父运行不再进入领取循环的情形。 */
@Component
public class FactProjectionTaskRecoveryScheduler {
  private final GitlabMirrorProperties properties;
  private final FactProjectionTaskService taskService;

  public FactProjectionTaskRecoveryScheduler(
      GitlabMirrorProperties properties, FactProjectionTaskService taskService) {
    this.properties = properties;
    this.taskService = taskService;
  }

  /** 复用平台已有调度开关，每轮只执行一条原子批量回收语句。 */
  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void recoverExpiredTasks() {
    if (properties.isSchedulerEnabled()) {
      taskService.recoverExpiredTasks();
    }
  }
}
