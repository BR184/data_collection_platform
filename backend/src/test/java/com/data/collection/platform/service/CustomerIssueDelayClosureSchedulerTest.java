package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 调度触发点必须只做移交：本机调度线程池只有 1 个线程且承载全应用 {@code @Scheduled} 触发，
 * 在这里执行编排（更不用说等待）会让同一池上的运行派发器拿不到线程，形成"等待自身派发"的自锁。
 */
class CustomerIssueDelayClosureSchedulerTest {
  private CustomerIssueDelayClosureOrchestrator orchestrator;
  private RecordingExecutor executor;
  private CustomerIssueDelayClosureScheduler scheduler;

  @BeforeEach
  void setUp() {
    orchestrator = mock(CustomerIssueDelayClosureOrchestrator.class);
    executor = new RecordingExecutor();
    scheduler = new CustomerIssueDelayClosureScheduler(orchestrator, executor);
  }

  @Test
  void shouldHandOffCycleWithoutRunningItOnTheTriggeringThread() {
    scheduler.triggerCustomerIssueDelayClosure();

    verify(orchestrator, never()).runCycleForAllSources();
    assertThat(executor.tasks()).hasSize(1);

    executor.drain();

    verify(orchestrator).runCycleForAllSources();
  }

  @Test
  void shouldSubmitOneCyclePerTrigger() {
    scheduler.triggerCustomerIssueDelayClosure();
    scheduler.triggerCustomerIssueDelayClosure();

    assertThat(executor.tasks()).hasSize(2);
  }

  private static final class RecordingExecutor implements Executor {
    private final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    List<Runnable> tasks() {
      return tasks;
    }

    void drain() {
      List<Runnable> pending = new ArrayList<>(tasks);
      tasks.clear();
      pending.forEach(Runnable::run);
    }
  }
}
