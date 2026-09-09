package com.data.collection.platform.service.backup;

/** 远程备份存储（SSH/SFTP）操作失败的统一异常；message 面向页面展示。 */
public class BackupRemoteException extends RuntimeException {
  public BackupRemoteException(String message) {
    super(message);
  }

  public BackupRemoteException(String message, Throwable cause) {
    super(message, cause);
  }
}
