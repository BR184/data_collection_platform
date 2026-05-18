package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class SyncRunExecutorServiceTest {
  @Test
  void shouldTrackActiveRunsAroundAsyncExecution() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setMaxSyncThreads(2);
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, executor, heartbeatExecutor);
    SyncRun run = run(11L);

    try {
      service.submit(run);

      assertThat(service.activeRuns()).isEqualTo(1);
      assertThat(service.availableSlots()).isEqualTo(1);

      executor.runNext();

      verify(workerService).executeRun(run);
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
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, executor, heartbeatExecutor);

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
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(
            properties,
            workerService,
            leaseService,
            command -> {
              throw new RejectedExecutionException("closed");
            },
            heartbeatExecutor);

    try {
      assertThatThrownBy(() -> service.submit(run(13L))).isInstanceOf(RejectedExecutionException.class);
      assertThat(service.activeRuns()).isZero();
      assertThat(service.hasCapacity()).isTrue();
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  private SyncRun run(Long id) {
    SyncRun run = new SyncRun();
    run.setId(id);
    run.setRunId("sr_" + id);
    return run;
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
