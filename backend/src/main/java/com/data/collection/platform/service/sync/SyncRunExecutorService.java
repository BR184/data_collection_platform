package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
  private final Executor executor;
  private final ExecutorService ownedExecutor;
  private final AtomicInteger activeRuns = new AtomicInteger();

  @Autowired
  public SyncRunExecutorService(GitlabMirrorProperties properties, SyncRunWorkerService workerService) {
    this(properties, workerService, Executors.newCachedThreadPool(new SyncRunThreadFactory()), null);
  }

  SyncRunExecutorService(GitlabMirrorProperties properties, SyncRunWorkerService workerService, Executor executor) {
    this(properties, workerService, executor, null);
  }

  private SyncRunExecutorService(
      GitlabMirrorProperties properties,
      SyncRunWorkerService workerService,
      Executor executor,
      ExecutorService ownedExecutor) {
    this.properties = properties;
    this.workerService = workerService;
    this.executor = executor;
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
    try {
      workerService.executeRun(run);
    } catch (RuntimeException e) {
      log.error("Async sync run execution failed, runId={}", run.getRunId(), e);
      throw e;
    } finally {
      activeRuns.decrementAndGet();
    }
  }

  private int maxConcurrentRuns() {
    return Math.max(1, properties.getMaxSyncThreads());
  }

  @PreDestroy
  public void shutdown() {
    if (ownedExecutor == null) {
      return;
    }
    ownedExecutor.shutdown();
    try {
      if (!ownedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        ownedExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      ownedExecutor.shutdownNow();
    }
  }

  private static final class SyncRunThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "sync-run-worker-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
