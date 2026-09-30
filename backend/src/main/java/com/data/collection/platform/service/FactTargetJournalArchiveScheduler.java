package com.data.collection.platform.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 低优先级归档巡检：定期收敛事实发布会话日志的历史行。
 *
 * <p>受 {@code platform.background-jobs.enabled} 总开关与单线程调度池约束，节拍远低于同步与事实刷新
 * worker，避免与重量级运行争用连接。
 */
@Component
public class FactTargetJournalArchiveScheduler {
  private final FactTargetJournalArchiveService archiveService;

  public FactTargetJournalArchiveScheduler(FactTargetJournalArchiveService archiveService) {
    this.archiveService = archiveService;
  }

  @Scheduled(
      fixedDelayString = "${platform.gitlab-mirror.fact-target-journal-archive-delay-ms:1800000}",
      initialDelayString = "${platform.gitlab-mirror.fact-target-journal-archive-initial-delay-ms:300000}")
  public void archiveExpiredBatches() {
    archiveService.archiveExpiredBatches();
  }
}
