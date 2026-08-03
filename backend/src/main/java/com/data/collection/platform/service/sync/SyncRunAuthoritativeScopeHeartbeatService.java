package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 在权威范围批次的来源查询期间持续维护整批租约。 */
@Service
@Slf4j
public class SyncRunAuthoritativeScopeHeartbeatService {
  private final SyncRunAuthoritativeScopeRepository repository;
  private final int leaseSeconds;
  private final ScheduledExecutorService scheduler;

  public SyncRunAuthoritativeScopeHeartbeatService(
      SyncRunAuthoritativeScopeRepository repository, GitlabMirrorProperties properties) {
    this.repository = repository;
    this.leaseSeconds =
        Math.max(
            properties.getTableTaskLeaseSeconds(),
            properties.getExternalQueryTimeoutSeconds() + 30);
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(new HeartbeatThreadFactory());
  }

  /** 开始维护一批已领取范围的租约；调用者必须关闭返回守卫。 */
  public LeaseGuard monitor(
      long runId, List<Long> scopeIds, String owner) {
    if (runId <= 0L
        || scopeIds == null
        || scopeIds.isEmpty()
        || owner == null
        || owner.isBlank()) {
      throw new IllegalArgumentException("权威范围批次缺少有效租约身份");
    }
    AtomicBoolean owned = new AtomicBoolean(true);
    int heartbeatSeconds = Math.max(1, leaseSeconds / 3);
    ScheduledFuture<?> heartbeat =
        scheduler.scheduleWithFixedDelay(
            () -> {
              try {
                if (!repository.renewBatchLease(scopeIds, owner, leaseSeconds)) {
                  owned.set(false);
                }
              } catch (RuntimeException error) {
                log.warn("权威范围批次租约续期失败，runId={}", runId, error);
              }
            },
            heartbeatSeconds,
            heartbeatSeconds,
            TimeUnit.SECONDS);
    return new LeaseGuard(runId, owned, heartbeat);
  }

  /** 返回领取范围批次时应使用的租约秒数。 */
  public int leaseSeconds() {
    return leaseSeconds;
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }

  /** 当前批次的租约守卫。 */
  public static final class LeaseGuard implements AutoCloseable {
    private final long runId;
    private final AtomicBoolean owned;
    private final ScheduledFuture<?> heartbeat;

    private LeaseGuard(
        long runId, AtomicBoolean owned, ScheduledFuture<?> heartbeat) {
      this.runId = runId;
      this.owned = owned;
      this.heartbeat = heartbeat;
    }

    /** 在平台副作用事务开始前拒绝已经失去租约的旧 worker。 */
    public void requireOwnership() {
      if (!owned.get()) {
        throw new SyncAuthoritativeScopeLeaseLostException(runId);
      }
    }

    @Override
    public void close() {
      heartbeat.cancel(false);
    }
  }

  private static final class HeartbeatThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "sync-authoritative-scope-heartbeat");
      thread.setDaemon(true);
      return thread;
    }
  }
}
