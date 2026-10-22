package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.config.PlatformAsyncConfiguration;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 客户问题延期标签写回的调度触发点。
 *
 * <p>调度方法只把编排提交到应用异步执行器后立即返回，本方法不得包含任何阻塞或长任务：
 * 调度线程池只有 1 个线程且承载全应用全部 {@code @Scheduled} 触发（运行派发、事实与写回 worker、
 * 系统钩子、补偿对账、BI 镜像、备份等），一旦在这里等待，等待对象又是同一线程池派发的运行时就会自锁。
 */
@Service
@Slf4j
public class CustomerIssueDelayClosureScheduler {
  private final CustomerIssueDelayClosureOrchestrator orchestrator;
  private final GitlabMirrorProperties properties;
  private final Executor platformAsyncExecutor;

  public CustomerIssueDelayClosureScheduler(
      CustomerIssueDelayClosureOrchestrator orchestrator,
      GitlabMirrorProperties properties,
      @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR) Executor platformAsyncExecutor) {
    this.orchestrator = orchestrator;
    this.properties = properties;
    this.platformAsyncExecutor = platformAsyncExecutor;
  }

  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.customer-issue-delay-check-delay-ms:3600000}")
  public void triggerCustomerIssueDelayClosure() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    log.info("Customer issue delay closure cycle submitted to the platform async executor");
    platformAsyncExecutor.execute(orchestrator::runCycleForAllSources);
  }

  /**
   * 短周期恢复检查：进程重启或终态事件丢失后，从持久进度继续等待和分页。
   *
   * <p>该触发不登记新请求，也不会重复提交正在运行的前置刷新。
   */
  @Scheduled(
      fixedDelayString = "${platform.gitlab-mirror.customer-issue-delay-progress-check-delay-ms:60000}")
  public void recoverCustomerIssueDelayClosureProgress() {
    if (!properties.isSchedulerEnabled()) {
      return;
    }
    platformAsyncExecutor.execute(orchestrator::checkProgressForAllSources);
  }
}
