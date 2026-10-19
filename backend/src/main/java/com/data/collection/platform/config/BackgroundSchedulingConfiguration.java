package com.data.collection.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 平台后台调度总入口。
 *
 * <p>正常运行时默认启用全部 {@code @Scheduled} 任务；读版本生产使用独立单线程，
 * 不占用同步派发等短触发任务的默认线程。关闭该开关不创建调度线程；
 * 仅迁移进程另用独立上下文，不能用关闭调度代替业务组件隔离。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "platform.background-jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BackgroundSchedulingConfiguration {
  /** 默认调度线程；显式保留标准名称，防止新增专用调度器接管未限定的任务。 */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("platform-scheduling-");
    return scheduler;
  }

  /** 串行执行固定数量的读生产/维护周期；每项 fixedDelay 在完成后才再次调度，不累计提交工作。 */
  @Bean
  public ThreadPoolTaskScheduler readPublicationScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("read-publication-");
    return scheduler;
  }
}
