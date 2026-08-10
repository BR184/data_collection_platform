package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRun;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** 在镜像运行预算内批量排空权威范围工作集。 */
@Service
public class SyncRunAuthoritativeScopeWorkerService {
  private final SyncRunAuthoritativeScopeRepository repository;
  private final SyncRunAuthoritativeScopeBatchExecutor batchExecutor;
  private final SyncRunAuthoritativeScopeHeartbeatService heartbeatService;
  private final SyncRunYieldService yieldService;
  private final int batchSize;

  public SyncRunAuthoritativeScopeWorkerService(
      SyncRunAuthoritativeScopeRepository repository,
      SyncRunAuthoritativeScopeBatchExecutor batchExecutor,
      SyncRunAuthoritativeScopeHeartbeatService heartbeatService,
      SyncRunYieldService yieldService,
      GitlabMirrorProperties properties) {
    this.repository = repository;
    this.batchExecutor = batchExecutor;
    this.heartbeatService = heartbeatService;
    this.yieldService = yieldService;
    this.batchSize = Math.max(1, Math.min(500, properties.getAuthoritativeScopeBatchSize()));
  }

  /** 排空当前可领取范围；后台运行只在已提交批次边界让行。 */
  public DrainResult drainRunScopes(SyncRun run, int workerCount) {
    if (run == null || run.getId() == null) {
      return new DrainResult(0, false);
    }
    int workers = Math.max(1, workerCount);
    AtomicBoolean yieldRequested = new AtomicBoolean();
    int processed =
        workers == 1
            ? drainSerial(run, owner(run.getId(), 1), yieldRequested)
            : drainParallel(run, workers, yieldRequested);
    if (yieldRequested.get() && yieldService.pauseIfRequested(run)) {
      return new DrainResult(processed, true);
    }
    return new DrainResult(processed, false);
  }

  /** 返回当前运行所有范围状态。 */
  public SyncRunAuthoritativeScopeRepository.ScopeSummary summarize(long runId) {
    return repository.summarize(runId);
  }

  /** 恢复所有已过期范围租约。 */
  public int recoverExpiredLeases() {
    return repository.recoverExpiredLeases();
  }

  /** 父运行结束时关闭尚未完成的范围。 */
  public int terminalizeRun(long runId, String message) {
    return repository.terminalizeRun(runId, message);
  }

  /** 把来源上次失败的权威范围纳入本次镜像运行。 */
  public int adoptFailedScopes(long runId, String sourceInstance) {
    return repository.adoptFailedScopes(
        runId, sourceInstance, repository.selectedSourceTables(runId));
  }

  private int drainSerial(
      SyncRun run, String owner, AtomicBoolean yieldRequested) {
    int processed = 0;
    while (!yieldRequested.get()) {
      List<SyncRunAuthoritativeScope> scopes =
          repository.claimNextBatch(
              run.getId(), owner, heartbeatService.leaseSeconds(), batchSize);
      if (scopes.isEmpty()) {
        break;
      }
      batchExecutor.execute(scopes, owner);
      processed += scopes.size();
      if (yieldService.shouldYield(run)) {
        yieldRequested.set(true);
      }
    }
    return processed;
  }

  private int drainParallel(
      SyncRun run, int workerCount, AtomicBoolean yieldRequested) {
    AtomicInteger processed = new AtomicInteger();
    ExecutorService executor =
        Executors.newFixedThreadPool(workerCount, new ScopeWorkerThreadFactory());
    List<Future<?>> futures = new ArrayList<>(workerCount);
    try {
      for (int index = 0; index < workerCount; index++) {
        String owner = owner(run.getId(), index + 1);
        futures.add(
            executor.submit(
                () -> processed.addAndGet(drainSerial(run, owner, yieldRequested))));
      }
      for (Future<?> future : futures) {
        future.get();
      }
      return processed.get();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("处理权威范围时被中断", error);
    } catch (ExecutionException error) {
      throw new IllegalStateException("处理权威范围失败", error.getCause());
    } finally {
      executor.shutdownNow();
    }
  }

  private String owner(long runId, int workerSlot) {
    return "scope-worker-" + runId + "-" + workerSlot + "-" + UUID.randomUUID();
  }

  /** 单次范围排空结果。 */
  public record DrainResult(int processedScopes, boolean yielded) {}

  private static final class ScopeWorkerThreadFactory implements ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread =
          new Thread(runnable, "sync-authoritative-scope-worker-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
