package com.data.collection.platform.service.backup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/** 一次备份尝试的不可变身份、取消信号、子进程与远程会话所有权。 */
public final class BackupExecutionContext {
  private static final Duration PROCESS_GRACE = Duration.ofSeconds(2);
  private static final Duration PROCESS_FORCE_WAIT = Duration.ofSeconds(8);

  private final long runId;
  private final String executionToken;
  private final BackupStateRepository stateRepository;
  private final Clock clock;
  private final AtomicBoolean stopRequested = new AtomicBoolean();
  private final AtomicReference<Process> activeProcess = new AtomicReference<>();
  private final AtomicReference<BackupRemoteStorage> activeStorage = new AtomicReference<>();
  private final Object quiescenceMonitor = new Object();
  private int activeExternalOperations;
  private boolean workerActive;
  private volatile String stopReason;
  private volatile ProcessIdentity processIdentity;

  public BackupExecutionContext(
      long runId, String executionToken, BackupStateRepository stateRepository, Clock clock) {
    if (runId <= 0L || executionToken == null || executionToken.isBlank()) {
      throw new IllegalArgumentException("备份执行上下文必须绑定运行 ID 与唯一 token");
    }
    this.runId = runId;
    this.executionToken = executionToken;
    this.stateRepository = stateRepository;
    this.clock = clock;
  }

  /** 返回此执行身份绑定的运行历史 ID。 */
  public long runId() {
    return runId;
  }

  /** 返回此执行身份的唯一 fencing token。 */
  public String executionToken() {
    return executionToken;
  }

  /** 在工作线程开始副作用前标记活动，供失租恢复器等待退出。 */
  public void beginWorker() {
    synchronized (quiescenceMonitor) {
      if (workerActive) {
        throw new IllegalStateException("备份执行工作线程已启动");
      }
      workerActive = true;
    }
  }

  /** 工作线程完成所有清理动作后解除活动标记。 */
  public void endWorker() {
    synchronized (quiescenceMonitor) {
      workerActive = false;
      quiescenceMonitor.notifyAll();
    }
  }

  /** 检查数据库运行权，并在租约无法确认时将执行转入停止状态。 */
  public void requireActive() {
    if (stopRequested.get()) {
      throw stoppedException();
    }
    final boolean active;
    try {
      active = stateRepository.isOwnedActive(runId, executionToken, clock.instant());
    } catch (RuntimeException unavailable) {
      requestStop("无法确认备份运行权：" + message(unavailable.getMessage()));
      throw new BackupExecutionStoppedException(stopReason);
    }
    if (!active) {
      requestStop("备份执行身份已失租或被撤销");
      throw new BackupLeaseLostException(runId);
    }
    if (stopRequested.get()) {
      throw stoppedException();
    }
  }

  /** 将启动的操作系统进程登记为本次执行的子进程；身份不匹配时立即终止。 */
  public void attachProcess(Process process) {
    if (!activeProcess.compareAndSet(null, process)) {
      terminate(process);
      throw new IllegalStateException("一次备份执行不能并发运行多个外部进程");
    }
    long processId = process.pid();
    Instant startedAt = process.toHandle().info().startInstant().orElse(null);
    if (startedAt == null) {
      terminate(process);
      if (!process.isAlive()) {
        activeProcess.compareAndSet(process, null);
      }
      throw new BackupExecutionStoppedException("无法读取备份子进程启动时刻，拒绝登记不可验证的进程身份");
    }
    processIdentity = new ProcessIdentity(processId, startedAt);
    boolean recorded;
    try {
      recorded =
          stateRepository.recordProcessIdentity(
              runId, executionToken, processId, startedAt, clock.instant());
    } catch (RuntimeException unavailable) {
      requestStop("子进程身份无法登记到运行状态");
      terminate(process);
      throw new BackupExecutionStoppedException(stopReason);
    }
    if (!recorded || stopRequested.get()) {
      requestStop("子进程启动时备份运行权已失效");
      terminate(process);
      throw new BackupLeaseLostException(runId);
    }
  }

  /** 在子进程已退出后清除其 PID 与启动时刻。 */
  public void detachProcess(Process process) {
    if (process.isAlive()) {
      return;
    }
    java.util.List<ProcessHandle> descendants = process.toHandle().descendants().toList();
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if (descendants.stream().anyMatch(ProcessHandle::isAlive)) {
      return;
    }
    if (activeProcess.compareAndSet(process, null)) {
      ProcessIdentity identity = processIdentity;
      processIdentity = null;
      if (identity != null) {
        try {
          stateRepository.clearProcessIdentity(
              runId, executionToken, identity.processId(), clock.instant());
        } catch (RuntimeException unavailable) {
          // 恢复器会按已持久化 PID 与启动时刻复核；数据库暂时不可用不能阻断进程停止。
        }
      }
      signalQuiescenceChange();
    }
  }

  /** 注册活动远程会话，失租时关闭会话以中断正在进行的 SFTP 操作。 */
  public void attachStorage(BackupRemoteStorage storage) {
    if (!activeStorage.compareAndSet(null, storage)) {
      storage.cancel();
      throw new IllegalStateException("一次备份执行不能并发使用多个远程会话");
    }
    if (stopRequested.get()) {
      boolean canceled = false;
      try {
        storage.cancel();
        canceled = true;
      } finally {
        if (canceled) {
          activeStorage.compareAndSet(storage, null);
          signalQuiescenceChange();
        }
      }
      throw stoppedException();
    }
  }

  /** 远程会话关闭后解除其执行归属。 */
  public void detachStorage(BackupRemoteStorage storage) {
    activeStorage.compareAndSet(storage, null);
    signalQuiescenceChange();
  }

  /** 远程 I/O 前后配对调用，供恢复器确认取消后没有仍在执行的外部操作。 */
  public void beginExternalOperation() {
    requireActive();
    synchronized (quiescenceMonitor) {
      if (stopRequested.get()) {
        throw stoppedException();
      }
      activeExternalOperations++;
    }
  }

  /** 与 {@link #beginExternalOperation()} 配对，表明远程副作用已经退出。 */
  public void endExternalOperation() {
    synchronized (quiescenceMonitor) {
      if (activeExternalOperations <= 0) {
        throw new IllegalStateException("备份远程操作计数不平衡");
      }
      activeExternalOperations--;
      quiescenceMonitor.notifyAll();
    }
  }

  /** 等待子进程与远程操作退出，不要求调用方 worker 本身已结束。 */
  public boolean awaitExternalEffectsStopped(Duration timeout) {
    long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
    synchronized (quiescenceMonitor) {
      while (activeExternalOperations > 0 || activeProcess.get() != null || activeStorage.get() != null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return false;
        }
        try {
          waitNanos(quiescenceMonitor, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  /** 撤销后续提交资格，并请求终止当前子进程与远程会话。 */
  public void requestStop(String reason) {
    stopReason = reason == null || reason.isBlank() ? "备份执行已停止" : reason;
    stopRequested.set(true);
    Process process = activeProcess.get();
    if (process != null) {
      try {
        terminate(process);
      } catch (RuntimeException ignored) {
        // 远程会话仍须取消；恢复器会保留本次暂存并复核持久化进程身份。
      }
    }
    BackupRemoteStorage storage = activeStorage.get();
    if (storage != null) {
      try {
        storage.cancel();
        activeStorage.compareAndSet(storage, null);
      } catch (RuntimeException ignored) {
        // 无法确认断连时 awaitStopped 将保留恢复状态和暂存文件。
      }
    }
    signalQuiescenceChange();
  }

  /** 有界等待工作线程、子进程与远程 I/O 全部停止。 */
  public boolean awaitStopped(Duration timeout) {
    long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
    synchronized (quiescenceMonitor) {
      while (workerActive
          || activeExternalOperations > 0
          || activeProcess.get() != null
          || activeStorage.get() != null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return false;
        }
        try {
          waitNanos(quiescenceMonitor, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return true;
    }
  }

  /** 返回是否已请求取消本次执行。 */
  public boolean stopRequested() {
    return stopRequested.get();
  }

  /** 返回最近一次停止原因，供进程与运行历史错误信息使用。 */
  public String stopReason() {
    return stopReason;
  }

  /** 返回当前登记的子进程身份；进程退出后为空。 */
  public ProcessIdentity processIdentity() {
    return processIdentity;
  }

  /** 用 PID 与 OS 启动时刻复核进程，避免仅凭可能复用的 PID 终止无关进程。 */
  public static boolean stopRecordedProcess(BackupStateRepository.ExpiredExecution expired) {
    if (expired.processId() == null || expired.processStartedAt() == null) {
      return false;
    }
    return ProcessHandle.of(expired.processId())
        .map(handle -> stopRecordedHandle(handle, expired.processStartedAt()))
        .orElse(true);
  }

  private static boolean stopRecordedHandle(ProcessHandle handle, Instant expectedStart) {
    Instant actualStart = handle.info().startInstant().orElse(null);
    if (actualStart == null || !actualStart.equals(expectedStart)) {
      return actualStart != null;
    }
    java.util.List<ProcessHandle> descendants = handle.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    if (handle.isAlive()) {
      handle.destroy();
    }
    try {
      if (handle.isAlive()
          && handle.onExit().get(PROCESS_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).isAlive()) {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
        handle.onExit().get(PROCESS_FORCE_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      return !handle.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    } catch (java.util.concurrent.TimeoutException timeout) {
      descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      handle.destroyForcibly();
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (java.util.concurrent.ExecutionException failed) {
      return !handle.isAlive();
    }
  }

  private boolean terminate(Process process) {
    java.util.List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    if (process.isAlive()) {
      process.destroy();
    }
    try {
      if (process.isAlive()
          && !process.waitFor(PROCESS_GRACE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(PROCESS_FORCE_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    boolean stopped = !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    if (stopped) {
      detachProcess(process);
    }
    return stopped;
  }

  private BackupExecutionStoppedException stoppedException() {
    return new BackupExecutionStoppedException(
        stopReason == null ? "备份执行已进入停止状态" : stopReason);
  }

  private void signalQuiescenceChange() {
    synchronized (quiescenceMonitor) {
      quiescenceMonitor.notifyAll();
    }
  }

  private static String message(String value) {
    return value == null || value.isBlank() ? "数据库未返回确认" : value;
  }

  /** 由操作系统 PID 和进程启动时刻组成的防 PID 复用身份。 */
  public record ProcessIdentity(long processId, Instant startedAt) {}

  private static void waitNanos(Object monitor, long nanos) throws InterruptedException {
    long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
    int nanosRemainder = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
    monitor.wait(millis, nanosRemainder);
  }
}
