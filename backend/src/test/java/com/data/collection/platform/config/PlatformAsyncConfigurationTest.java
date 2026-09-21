package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.RealtimeWorkspaceService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 应用异步执行器的归属与兜底。
 *
 * <p>本测试锁定缺陷的根因形态：上下文里唯一可用的 {@code TaskExecutor} 是调度器时，
 * 未限定执行器的 {@code @Async} 会落在 {@code scheduling-*} 线程上，使"异步"方法实际占用
 * 单线程调度池。生产接线（调度器 + 应用异步执行器并存）在这里被完整复现。
 */
class PlatformAsyncConfigurationTest {

  @Test
  void platformAsyncExecutorRunsTasksOnItsOwnThreads() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PlatformAsyncConfiguration.class);
      context.refresh();
      Executor executor =
          context.getBean(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR, Executor.class);

      String threadName =
          CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor).get();

      assertThat(threadName).startsWith(PlatformAsyncConfiguration.THREAD_NAME_PREFIX);
      assertThat(threadName).doesNotStartWith("scheduling-");
    }
  }

  @Test
  void unqualifiedAsyncDoesNotFallBackToTheSchedulingPool() throws Exception {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PlatformAsyncConfiguration.class, ProductionLikeWiring.class);
      context.refresh();

      Future<String> threadName = context.getBean(AsyncProbe.class).run();

      assertThat(threadName.get()).startsWith(PlatformAsyncConfiguration.THREAD_NAME_PREFIX);
    }
  }

  @Test
  void asyncAnnotationsArePinnedToThePlatformExecutor() throws Exception {
    assertPinnedAsync(
        RealtimeWorkspaceService.class.getMethod("executeRefreshAsync", String.class, Runnable.class));
    assertPinnedAsync(
        RealtimeWorkspaceService.class.getMethod(
            "executeRefreshWithResultAsync", String.class, Supplier.class));
  }

  private void assertPinnedAsync(java.lang.reflect.Method method) {
    Async async = method.getAnnotation(Async.class);
    assertThat(async).as("方法 %s 必须显式指定执行器", method.getName()).isNotNull();
    assertThat(async.value()).isEqualTo(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR);
  }

  /** 复现生产接线：调度线程池与应用异步执行器同时存在。 */
  @Configuration
  @EnableAsync
  static class ProductionLikeWiring {

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
      return new ThreadPoolTaskScheduler();
    }

    @Bean
    AsyncProbe asyncProbe() {
      return new AsyncProbe();
    }
  }

  static class AsyncProbe {

    @Async
    public Future<String> run() {
      return CompletableFuture.completedFuture(Thread.currentThread().getName());
    }
  }
}
