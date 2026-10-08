package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactBuildTask;
import org.springframework.stereotype.Service;

/** 在线程内传递当前事实构建任务身份，使批次提交边界能够验证父运行与任务两层执行权。 */
@Service
public class FactTaskExecutionContext {
  private final ThreadLocal<QueuedFactBuildTask> currentTask = new ThreadLocal<>();

  /**
   * 在当前同步调用栈中安装事实任务身份。
   *
   * @param task 已领取且包含任务令牌与父运行令牌的任务
   * @return 关闭后清除身份的作用域
   */
  public Scope open(QueuedFactBuildTask task) {
    if (task == null || task.leaseOwner() == null || task.leaseOwner().isBlank()) {
      throw new IllegalArgumentException("事实任务执行上下文必须携带有效任务令牌");
    }
    if (currentTask.get() != null) {
      throw new IllegalStateException("当前线程已经处于事实任务执行上下文");
    }
    currentTask.set(task);
    return () -> currentTask.remove();
  }

  /** 返回当前同步调用栈中的事实任务；手工同步构建等无任务路径返回 {@code null}。 */
  public QueuedFactBuildTask currentTask() {
    return currentTask.get();
  }

  /** 通过 try-with-resources 明确限定身份有效的代码段。 */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
