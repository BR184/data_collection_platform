package com.data.collection.platform.service.sync;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 在表任务执行期间持续维护任务级租约。 */
@Service
@Slf4j
public class SyncRunTableTaskHeartbeatService {
  private final SyncRunTableTaskLeaseService leaseService;
  private final int leaseSeconds;
  private final ScheduledExecutorService scheduler;

  @Autowired
  public SyncRunTableTaskHeartbeatService(
      SyncRunTableTaskLeaseService leaseService, GitlabMirrorProperties properties) {
    this(
        leaseService,
        Math.max(properties.getTableTaskLeaseSeconds(), properties.getExternalQueryTimeoutSeconds() + 30),
        Executors.newSingleThreadScheduledExecutor(new HeartbeatThreadFactory()));
  }

  SyncRunTableTaskHeartbeatService(
      SyncRunTableTaskLeaseService leaseService,
      int leaseSeconds,
      ScheduledExecutorService scheduler) {
    this.leaseService = leaseService;
    this.leaseSeconds = Math.max(3, leaseSeconds);
    this.scheduler = scheduler;
  }

  /**
   * 开始维护已领取任务的租约。
   *
   * @param task 已处于 RUNNING 的表任务
   * @return 执行结束时必须关闭的租约守卫
   */
  public LeaseGuard monitor(SyncRunTableTask task) {
    if (task == null || task.getId() == null || task.getLeaseOwner() == null || task.getLeaseOwner().isBlank()) {
      throw new IllegalArgumentException("表任务缺少有效租约所有者");
    }
    AtomicBoolean owned = new AtomicBoolean(true);
    int heartbeatSeconds = Math.max(1, leaseSeconds / 3);
    ScheduledFuture<?> heartbeat =
        scheduler.scheduleWithFixedDelay(
            () -> {
              try {
                if (!leaseService.renewLease(task.getId(), task.getLeaseOwner(), leaseSeconds)) {
                  owned.set(false);
                }
              } catch (RuntimeException error) {
                log.warn("表任务租约续期失败，taskId={}", task.getId(), error);
              }
            },
            heartbeatSeconds,
            heartbeatSeconds,
            TimeUnit.SECONDS);
    return new LeaseGuard(task.getId(), task.getLeaseOwner(), owned, heartbeat);
  }

  /** 返回任务租约的有效时长，单位为秒。 */
  public int leaseSeconds() {
    return leaseSeconds;
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }

  /** 当前 worker 的任务租约守卫。 */
  public static final class LeaseGuard implements AutoCloseable {
    private final Long taskId;
    private final String owner;
    private final AtomicBoolean owned;
    private final ScheduledFuture<?> heartbeat;

    private LeaseGuard(
        Long taskId, String owner, AtomicBoolean owned, ScheduledFuture<?> heartbeat) {
      this.taskId = taskId;
      this.owner = owner;
      this.owned = owned;
      this.heartbeat = heartbeat;
    }

    public String owner() {
      return owner;
    }

    /** 在每个副作用边界拒绝失去租约的旧 worker。 */
    public void requireOwnership() {
      if (!owned.get()) {
        throw new SyncTaskLeaseLostException(taskId);
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
      Thread thread = new Thread(runnable, "sync-table-task-heartbeat");
      thread.setDaemon(true);
      return thread;
    }
  }
}
