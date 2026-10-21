package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.BackgroundSchedulingConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

/** 实际注册周期任务，验证读生产被阻塞时默认调度仍能派发，而非仅断言线程池配置。 */
class ReadSchedulingIsolationTest {
  @Test
  void test_blocked_read_job_does_not_block_default_dispatch() {
    Jobs jobs = new Jobs();
    new ApplicationContextRunner().withUserConfiguration(BackgroundSchedulingConfiguration.class)
        .withBean(Jobs.class, () -> jobs).run(context -> {
          try {
            assertThat(context).hasNotFailed();
            assertThat(jobs.readEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.defaultDispatched.await(5, TimeUnit.SECONDS)).isTrue();
          } finally {
            jobs.releaseRead.countDown();
          }
        });
  }

  static class Jobs {
    final CountDownLatch readEntered = new CountDownLatch(1);
    final CountDownLatch releaseRead = new CountDownLatch(1);
    final CountDownLatch defaultDispatched = new CountDownLatch(1);

    /** 模拟尚未返回的大表查询，直到测试结束显式释放。 */
    @Scheduled(scheduler = "readPublicationScheduler", fixedDelay = 60000)
    public void readJob() throws InterruptedException {
      readEntered.countDown();
      releaseRead.await();
    }

    /** 在大表任务已占用专用线程后，默认派发线程仍可执行任务。 */
    @Scheduled(fixedDelay = 1)
    public void dispatch() {
      if (readEntered.getCount() == 0) { defaultDispatched.countDown(); }
    }
  }
}
