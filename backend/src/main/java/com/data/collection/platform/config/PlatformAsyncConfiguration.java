package com.data.collection.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 应用级异步执行器：承接 {@code @Async} 与后台编排，使长任务不占用调度线程池。
 *
 * <p>应用没有显式异步执行器时，Spring 的 {@code @Async} 会解析到上下文中唯一的
 * {@code TaskExecutor}——即 {@code @EnableScheduling} 建立的 {@code ThreadPoolTaskScheduler}
 * （默认 1 个线程）。长任务一旦落在它上面，就会挤占全部 {@code @Scheduled} 触发
 * （运行派发、事实与写回 worker、系统钩子、补偿对账、BI 镜像、备份、检索回填等）；
 * 而"等待某个由同一线程池派发的运行"的任务会与派发器互相等待，形成永久自锁。
 *
 * <p>本执行器因此同时承担两个职责：为显式 {@code @Async("platformAsyncExecutor")} 提供归属，
 * 并作为 {@code @Primary} 兜住未限定执行器的 {@code @Async}，使同类缺陷无法再靠"忘记指定执行器"复发。
 * 调度线程池从此只跑短触发性任务。
 */
@Configuration
public class PlatformAsyncConfiguration {

  /** 应用异步执行器的 bean 名；{@code @Async} 与显式提交都按此名引用。 */
  public static final String PLATFORM_ASYNC_EXECUTOR = "platformAsyncExecutor";

  /** 应用异步线程名前缀；用于线程归属断言，禁止改动。 */
  public static final String THREAD_NAME_PREFIX = "platform-async-";

  private static final int MIN_THREADS = 2;
  private static final int MAX_THREADS = 8;

  /**
   * 应用异步执行器。
   *
   * <p>线程数按可用处理器数自适应并收敛在 {@value #MIN_THREADS}～{@value #MAX_THREADS} 之间：
   * 下界保证调度触发与页面刷新不会互相排队，上界避免与镜像/事实构建的数据库连接预算竞争。
   * 队列保持无界以维持"异步提交不拒绝"的既有语义——拒绝会让页面手动刷新直接失败，
   * 而真正的长任务都带有各自的单飞与复用门禁，不会无界堆积。
   *
   * @return 应用异步执行器，随上下文关闭而优雅停机
   */
  @Bean(name = PLATFORM_ASYNC_EXECUTOR)
  @Primary
  public ThreadPoolTaskExecutor platformAsyncExecutor() {
    int threads =
        Math.max(MIN_THREADS, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()));
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
    executor.setWaitForTasksToCompleteOnShutdown(false);
    return executor;
  }
}
