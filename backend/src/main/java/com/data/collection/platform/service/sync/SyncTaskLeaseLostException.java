package com.data.collection.platform.service.sync;

/** 表任务已失去租约所有权，当前 worker 必须停止后续副作用。 */
public class SyncTaskLeaseLostException extends RuntimeException {
  public SyncTaskLeaseLostException(Long taskId) {
    super("表任务租约已转移，停止旧 worker 写入：" + taskId);
  }
}
