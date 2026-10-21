package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.service.read.CandidateCacheArtifactStore.CandidateCacheArtifact;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 在线程内传递当前投影任务身份，使快照存储边界能够验证最终写入资格。 */
@Service
public class FactProjectionExecutionContext {
  private final ThreadLocal<QueuedFactProjectionTask> currentTask = new ThreadLocal<>();
  private final ThreadLocal<List<CandidateCacheArtifact>> writtenArtifacts = new ThreadLocal<>();

  /**
   * 在当前同步调用栈中安装投影任务身份。
   *
   * @param task 已领取且包含不可复用租约令牌的任务
   * @return 关闭后清除身份与写入清单的作用域
   */
  public Scope open(QueuedFactProjectionTask task) {
    if (task == null || task.leaseToken() == null || task.leaseToken().isBlank()) {
      throw new IllegalArgumentException("投影执行上下文必须携带有效租约令牌");
    }
    if (currentTask.get() != null) {
      throw new IllegalStateException("当前线程已经处于投影执行上下文");
    }
    currentTask.set(task);
    writtenArtifacts.set(new ArrayList<>());
    return () -> {
      currentTask.remove();
      writtenArtifacts.remove();
    };
  }

  /** 返回当前同步调用栈中的投影任务；普通页面请求返回 {@code null}。 */
  public QueuedFactProjectionTask currentTask() {
    return currentTask.get();
  }

  /**
   * 登记本任务刚写入的候选缓存行内容快照。
   *
   * @param artifact 写入事务内由同一取证查询读回的快照
   */
  public void recordWrittenArtifact(CandidateCacheArtifact artifact) {
    List<CandidateCacheArtifact> written = writtenArtifacts.get();
    if (written == null) {
      throw new IllegalStateException("候选预热产物登记缺少投影执行上下文");
    }
    if (artifact == null) {
      throw new IllegalArgumentException("候选预热产物快照不能为空");
    }
    written.add(artifact);
  }

  /**
   * 本任务在当前执行内登记的候选缓存行快照，按写入顺序。
   *
   * @return 不可变副本；不在投影执行上下文内时抛出
   */
  public List<CandidateCacheArtifact> writtenArtifacts() {
    List<CandidateCacheArtifact> written = writtenArtifacts.get();
    if (written == null) {
      throw new IllegalStateException("当前线程不在投影执行上下文中");
    }
    return List.copyOf(written);
  }

  /** 通过 try-with-resources 明确限定身份有效的代码段。 */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
