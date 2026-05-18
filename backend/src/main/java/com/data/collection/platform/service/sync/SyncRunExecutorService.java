package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunExecutorService {
  private final GitlabMirrorProperties properties;
  private final SyncRunWorkerService workerService;
  private final SyncRunLeaseService leaseService;
  private final Executor executor;
  private final ExecutorService ownedExecutor;
  private final ScheduledExecutorService heartbeatExecutor;
  private final AtomicInteger activeRuns = new AtomicInteger();

  @Autowired
  public SyncRunExecutorService(
      GitlabMirrorProperties properties,
      SyncRunWorkerService workerService,
      SyncRunLeaseService leaseService) {
    this(
        properties,
        workerService,
        leaseService,
        Executors.newCachedThreadPool(new SyncRunThreadFactory("sync-run-worker")),
        Executors.newSingleThreadScheduledExecutor(new SyncRunThreadFactory("sync-run-heartbeat")),
        null);
  }

  SyncRunExecutorService(
      GitlabMirrorProperties properties,
      SyncRunWorkerService workerService,
      SyncRunLeaseService leaseService,
      Executor executor,
      ScheduledExecutorService heartbeatExecutor) {
    this(properties, workerService, leaseService, executor, heartbeatExecutor, null);
  }

  private SyncRunExecutorService(
      GitlabMirrorProperties properties,
      SyncRunWorkerService workerService,
      SyncRunLeaseService leaseService,
      Executor executor,
      ScheduledExecutorService heartbeatExecutor,
      ExecutorService ownedExecutor) {
    this.properties = properties;
    this.workerService = workerService;
    this.leaseService = leaseService;
    this.executor = executor;
    this.heartbeatExecutor = heartbeatExecutor;
    this.ownedExecutor = ownedExecutor == null && executor instanceof ExecutorService service ? service : ownedExecutor;
  }

  public int availableSlots() {
    return Math.max(0, maxConcurrentRuns() - activeRuns.get());
  }

  public int activeRuns() {
    return activeRuns.get();
  }

  public boolean hasCapacity() {
    return availableSlots() > 0;
  }

  public void submit(SyncRun run) {
    if (run == null || run.getId() == null) {
      return;
    }
    activeRuns.incrementAndGet();
    try {
      executor.execute(() -> execute(run));
    } catch (RejectedExecutionException e) {
      activeRuns.decrementAndGet();
      throw e;
    }
  }

  private void execute(SyncRun run) {
    ScheduledFuture<?> heartbeat = startHeartbeat(run);
    try {
      workerService.executeRun(run);
    } catch (RuntimeException e) {
      log.error("Async sync run execution failed, runId={}", run.getRunId(), e);
      throw e;
    } finally {
      heartbeat.cancel(false);
      activeRuns.decrementAndGet();
    }
  }

  private ScheduledFuture<?> startHeartbeat(SyncRun run) {
    int leaseSeconds = Math.max(1, properties.getHeartbeatTimeoutSeconds());
    int heartbeatSeconds = Math.max(1, leaseSeconds / 3);
    return heartbeatExecutor.scheduleWithFixedDelay(
        () -> leaseService.heartbeat(run.getId(), leaseSeconds),
        0,
        heartbeatSeconds,
        TimeUnit.SECONDS);
  }

  private int maxConcurrentRuns() {
    return Math.max(1, properties.getMaxSyncThreads());
  }

  @PreDestroy
  public void shutdown() {
    if (ownedExecutor == null) {
      shutdownExecutor(heartbeatExecutor);
      return;
    }
    shutdownExecutor(ownedExecutor);
    shutdownExecutor(heartbeatExecutor);
  }

  private void shutdownExecutor(ExecutorService executorService) {
    if (executorService == null) {
      return;
    }
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executorService.shutdownNow();
    }
  }

  private static final class SyncRunThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    private SyncRunThreadFactory(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
