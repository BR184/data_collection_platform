package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 黄金链路周期任务暂停机制的契约。
 *
 * <p>冻结链路必须在不中断已完成产物的前提下停止周期生产者，否则写用例修改定义后，
 * 定义刷新工作器会把测试输入登记成一次额外全量事实重建，改变运行序号并污染读快照。
 *
 * <p>暂停依赖 {@code ScheduledTask.getTask().getRunnable() instanceof ScheduledMethodRunnable}
 * 的匹配。「任务被包装导致匹配失效」只是待验证假设，本测试用真实调度注册结果证明其成立或不成立。
 */
class ScheduledProducerPauseTest {

  @Test
  void scheduledMethodRegistersWrappedRunnableThatStillResolvesToTheProducer() {
    try (AnnotationConfigApplicationContext context = newContext()) {
      ScheduledAnnotationBeanPostProcessor schedules =
          context.getBean(ScheduledAnnotationBeanPostProcessor.class);
      PauseableProducer producer = context.getBean(PauseableProducer.class);

      var registered =
          schedules.getScheduledTasks().stream()
              .map(task -> task.getTask().getRunnable())
              .toList();

      assertThat(registered)
          .as(
              "Spring 会把 @Scheduled 方法包进调度包装类，单层 instanceof 匹配会失效；实际类型=%s",
              registered.stream().map(r -> r.getClass().getName()).toList())
          .isNotEmpty();
      assertThat(GoldenBaselineSupport.pauseScheduledProducer(schedules, PauseableProducer.class))
          .as("穿透包装链后必须能按生产者类型命中并取消周期任务")
          .isEqualTo(registered.size());
    }
  }

  @Test
  void pauseByProducerTypeStopsFurtherExecutions() throws Exception {
    try (AnnotationConfigApplicationContext context = newContext()) {
      ScheduledAnnotationBeanPostProcessor schedules =
          context.getBean(ScheduledAnnotationBeanPostProcessor.class);
      PauseableProducer producer = context.getBean(PauseableProducer.class);

      awaitAtLeast(producer::executions, 1);
      int cancelled = GoldenBaselineSupport.pauseScheduledProducer(schedules, PauseableProducer.class);
      assertThat(cancelled).as("暂停必须命中至少一个已注册的周期任务").isPositive();

      Thread.sleep(150);
      int settled = producer.executions();
      Thread.sleep(300);

      assertThat(producer.executions())
          .as("暂停后生产者的方法不再被周期调用")
          .isEqualTo(settled);
    }
  }

  private static void awaitAtLeast(java.util.function.IntSupplier counter, int expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (counter.getAsInt() < expected && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(counter.getAsInt()).as("周期任务在等待窗口内应至少执行一次").isGreaterThanOrEqualTo(expected);
  }

  private static AnnotationConfigApplicationContext newContext() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("goldenPauseTest", Map.of("golden.fixture.delay-ms", "20")));
    context.register(FixtureConfig.class);
    context.refresh();
    return context;
  }

  @Configuration
  @EnableScheduling
  static class FixtureConfig {
    @Bean
    PauseableProducer pauseableProducer() {
      return new PauseableProducer();
    }
  }

  static class PauseableProducer {
    private final AtomicInteger executions = new AtomicInteger();

    @Scheduled(fixedDelayString = "${golden.fixture.delay-ms:3600000}")
    public void produce() {
      executions.incrementAndGet();
    }

    int executions() {
      return executions.get();
    }
  }
}
