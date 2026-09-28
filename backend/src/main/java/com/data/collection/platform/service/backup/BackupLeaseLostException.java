package com.data.collection.platform.service.backup;

/** 当前备份执行身份已失租或已被恢复器撤销。 */
public class BackupLeaseLostException extends RuntimeException {
  /** Creates a failure scoped to the run whose execution token is no longer valid. */
  public BackupLeaseLostException(long runId) {
    super("备份运行权已失效或撤销：" + runId);
  }
}
