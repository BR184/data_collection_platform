package com.data.collection.platform.service.backup;

/** 外部进程执行失败（非零退出、启动失败、超时被强杀）的统一异常。 */
public class BackupProcessException extends RuntimeException {
  public BackupProcessException(String message) {
    super(message);
  }

  public BackupProcessException(String message, Throwable cause) {
    super(message, cause);
  }
}
