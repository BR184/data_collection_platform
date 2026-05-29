package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SyncRunExecutorServiceTest {
  @Test
  void shouldTrackActiveRunsAroundAsyncExecution() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(2);
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, workerLeaseService, executor, heartbeatExecutor);
    SyncRun run = run(11L);

    try {
      service.submit(run);

      assertThat(service.activeRuns()).isEqualTo(1);
      assertThat(service.availableSlots()).isEqualTo(1);

      executor.runNext();

      verify(workerService).executeRun(run);
      verify(workerLeaseService, atLeastOnce()).heartbeatRunExecutor(2, 1, 0, 180);
      verify(workerLeaseService, atLeastOnce()).heartbeatRunExecutor(2, 0, 0, 180);
      assertThat(service.activeRuns()).isZero();
      assertThat(service.availableSlots()).isEqualTo(2);
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  @Test
  void shouldExposeCapacityFromConfiguredMaxSyncThreads() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(1);
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, workerLeaseService, executor, heartbeatExecutor);

    try {
      service.submit(run(12L));

      assertThat(service.hasCapacity()).isFalse();
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  @Test
  void shouldReleaseSlotWhenExecutorRejectsRun() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(1);
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(
            properties,
            workerService,
            leaseService,
            workerLeaseService,
            command -> {
              throw new RejectedExecutionException("closed");
            },
            heartbeatExecutor);

    try {
      assertThatThrownBy(() -> service.submit(run(13L))).isInstanceOf(RejectedExecutionException.class);
      verify(workerLeaseService, atLeastOnce()).heartbeatRunExecutor(1, 1, 0, 180);
      verify(workerLeaseService, atLeastOnce()).heartbeatRunExecutor(1, 0, 0, 180);
      assertThat(service.activeRuns()).isZero();
      assertThat(service.hasCapacity()).isTrue();
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  @Test
  void defaultExecutorShouldRejectWhenBoundedQueueIsFull() throws Exception {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(1);
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    org.mockito.Mockito.doAnswer(invocation -> {
      Thread.sleep(TimeUnit.SECONDS.toMillis(5));
      return null;
    }).when(workerService).executeRun(org.mockito.ArgumentMatchers.any());
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, workerLeaseService);

    try {
      service.submit(run(100L));
      waitUntilActive(service, 1);
      for (long id = 101L; id <= 104L; id++) {
        service.submit(run(id));
      }
      assertThatThrownBy(() -> service.submit(run(105L))).isInstanceOf(RejectedExecutionException.class);
    } finally {
      service.shutdown();
    }
  }

  private SyncRun run(Long id) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_" + id);
    return run;
  }

  private void waitUntilActive(SyncRunExecutorService service, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline && service.activeRuns() != expected) {
      Thread.sleep(10);
    }
    assertThat(service.activeRuns()).isEqualTo(expected);
  }

  private static final class CapturingExecutor implements Executor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    void runNext() {
      Runnable task = tasks.poll();
      if (task != null) {
        task.run();
      }
    }
  }
}
