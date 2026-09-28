package com.data.collection.platform.service.backup;

/** 备份执行已进入停止状态，调用方必须停止后续提交与清理动作。 */
public class BackupExecutionStoppedException extends RuntimeException {
  /** Creates a cancellation failure with the reason shown in run diagnostics. */
  public BackupExecutionStoppedException(String message) {
    super(message);
  }
}
