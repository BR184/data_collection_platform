package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
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
    when(leaseService.heartbeat(11L, "owner-11", 180)).thenReturn(1);
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
    when(leaseService.heartbeat(12L, "owner-12", 180)).thenReturn(1);
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
    when(leaseService.heartbeat(13L, "owner-13", 180)).thenReturn(1);
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
      verify(leaseService).releaseOwnedRun(org.mockito.ArgumentMatchers.argThat(
          run -> run.getId().equals(13L) && "owner-13".equals(run.getLeaseOwner())));
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
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              workerStarted.countDown();
              try {
                releaseWorker.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return null;
            })
        .when(workerService)
        .executeRun(org.mockito.ArgumentMatchers.any());
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    when(leaseService.heartbeat(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(180)))
        .thenReturn(1);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    SyncRunExecutorService service =
        new SyncRunExecutorService(properties, workerService, leaseService, workerLeaseService);

    try {
      service.submit(run(100L));
      assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();
      for (long id = 101L; id <= 104L; id++) {
        service.submit(run(id));
      }
      assertThatThrownBy(() -> service.submit(run(105L))).isInstanceOf(RejectedExecutionException.class);
    } finally {
      releaseWorker.countDown();
      service.shutdown();
    }
  }

  @Test
  void shouldSkipExecutionWhenRunLeaseWasTransferredBeforeWorkerStart() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(
            properties,
            workerService,
            leaseService,
            workerLeaseService,
            executor,
            heartbeatExecutor);
    SyncRun run = run(14L);
    when(leaseService.heartbeat(14L, "owner-14", 180)).thenReturn(0);

    try {
      service.submit(run);
      executor.runNext();

      verify(workerService, never()).executeRun(run);
      assertThat(service.activeRuns()).isZero();
    } finally {
      heartbeatExecutor.shutdownNow();
    }
  }

  @Test
  void shouldReleaseCapacityWhenInitialLeaseHeartbeatFails() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    SyncRunWorkerService workerService = mock(SyncRunWorkerService.class);
    SyncRunLeaseService leaseService = mock(SyncRunLeaseService.class);
    SyncWorkerLeaseService workerLeaseService = mock(SyncWorkerLeaseService.class);
    CapturingExecutor executor = new CapturingExecutor();
    ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    SyncRunExecutorService service =
        new SyncRunExecutorService(
            properties,
            workerService,
            leaseService,
            workerLeaseService,
            executor,
            heartbeatExecutor);
    SyncRun run = run(15L);
    when(leaseService.heartbeat(15L, "owner-15", 180))
        .thenThrow(new IllegalStateException("database unavailable"));

    try {
      service.submit(run);
      executor.runNext();

      verify(workerService, never()).executeRun(run);
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
    run.setLeaseOwner("owner-" + id);
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
