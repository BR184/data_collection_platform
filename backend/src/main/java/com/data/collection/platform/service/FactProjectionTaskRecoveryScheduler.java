package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期收敛已失租的事实投影任务，包括父运行不再进入领取循环的情形。 */
@Component
public class FactProjectionTaskRecoveryScheduler {
  private final GitlabMirrorProperties properties;
  private final FactProjectionTaskService taskService;
  private final FactProjectionTaskWorkerService worker;
  private final FactTargetPublicationService publications;

  public FactProjectionTaskRecoveryScheduler(
      GitlabMirrorProperties properties, FactProjectionTaskService taskService,
      FactProjectionTaskWorkerService worker, FactTargetPublicationService publications) {
    this.properties = properties;
    this.taskService = taskService;
    this.worker = worker;
    this.publications = publications;
  }

  /**
   * 复用平台已有调度开关，每轮只执行有界 SQL：先回收失租任务，再把父运行已终态的遗留任务
   * 转为人工待处理；独立生产者每轮至多领取一个持久任务，使用相同候选预热工作器与唯一发布入口。
   * 不创建额外线程或无界队列，父 FACT_REFRESH 的任务仍由其运行执行器承担。
   */
  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void recoverExpiredTasks() {
    if (properties.isSchedulerEnabled()) {
      taskService.recoverExpiredTasks();
      taskService.parkOrphanedTasksForManualDecision();
      publications.recoverFailedStandaloneCandidates();
      taskService.enqueuePreparedCandidates(null);
      com.data.collection.platform.entity.QueuedFactProjectionTask task =
          taskService.claimNextStandalone(Math.max(1, properties.getHeartbeatTimeoutSeconds()));
      if (task != null) {
        worker.execute(task);
      }
      publications.publishPreparedStandaloneCandidates();
    }
  }
}
