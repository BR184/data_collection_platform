package com.data.collection.platform.service.sync;

/** 当前执行器已失去同步运行所有权，后续运行级写入必须停止。 */
public class SyncRunLeaseLostException extends RuntimeException {
  public SyncRunLeaseLostException(Long runId) {
    super("Sync run lease ownership changed: runId=" + runId);
  }
}
