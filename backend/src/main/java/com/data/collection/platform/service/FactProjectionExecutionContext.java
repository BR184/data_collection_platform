package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactProjectionTask;
import org.springframework.stereotype.Service;

/** 在线程内传递当前投影任务身份，使快照存储边界能够验证最终写入资格。 */
@Service
public class FactProjectionExecutionContext {
  private final ThreadLocal<QueuedFactProjectionTask> currentTask = new ThreadLocal<>();

  /**
   * 在当前同步调用栈中安装投影任务身份。
   *
   * @param task 已领取且包含不可复用租约令牌的任务
   * @return 关闭后清除身份的作用域
   */
  public Scope open(QueuedFactProjectionTask task) {
    if (task == null || task.leaseToken() == null || task.leaseToken().isBlank()) {
      throw new IllegalArgumentException("投影执行上下文必须携带有效租约令牌");
    }
    if (currentTask.get() != null) {
      throw new IllegalStateException("当前线程已经处于投影执行上下文");
    }
    currentTask.set(task);
    return () -> currentTask.remove();
  }

  /** 返回当前同步调用栈中的投影任务；普通页面请求返回 {@code null}。 */
  public QueuedFactProjectionTask currentTask() {
    return currentTask.get();
  }

  /** 通过 try-with-resources 明确限定身份有效的代码段。 */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
