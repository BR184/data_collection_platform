package com.data.collection.platform.service.read;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 历史回收与读生产共用专用串行调度，不占同步派发线程或业务读取请求。 */
@Component
public class ReadMaintenanceScheduler {
  private final ReadMaintenanceService maintenance;

  public ReadMaintenanceScheduler(ReadMaintenanceService maintenance) {
    this.maintenance = maintenance;
  }

  /** 各维护操作使用独立短事务，避免先锁页面再锁版本导致与正常续约反向竞争。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void maintainReadVersions() {
    maintenance.expireViews();
    maintenance.collectOneDomain();
  }

  /** 与历史回收独立周期，任一路径失败都不跳过另一路径；使用专用线程与单批事务。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void cleanupAbortedFacts() {
    maintenance.collectAbortedFactCandidate();
  }
}
