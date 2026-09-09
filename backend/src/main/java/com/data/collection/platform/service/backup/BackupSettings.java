package com.data.collection.platform.service.backup;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 备份配置的领域表示，对应 backup_settings 单行；行不存在时以 {@link #defaults()} 呈现
 * （与建表默认值一致：未启用调度、每日 03:00、保留 14 份、本地存储、版本 0）。
 * 版本 0 表示尚未落库，作为乐观锁边界：首次保存走 insert，其后走条件更新。
 */
public record BackupSettings(
    boolean enabled,
    LocalTime scheduleTime,
    int retentionCopies,
    String storageMode,
    String localSubdirectory,
    String remoteHost,
    int remotePort,
    String remoteUsername,
    String remotePasswordCipher,
    String remoteDirectory,
    String remoteHostKeyFingerprint,
    long version,
    String updatedBy,
    Instant updatedAt) {

  public static final String STORAGE_MODE_LOCAL = "LOCAL";
  public static final String STORAGE_MODE_REMOTE = "REMOTE";

  public static BackupSettings defaults() {
    return new BackupSettings(
        false,
        LocalTime.of(3, 0),
        14,
        STORAGE_MODE_LOCAL,
        null,
        null,
        22,
        null,
        null,
        null,
        null,
        0,
        null,
        null);
  }

  public boolean remoteMode() {
    return STORAGE_MODE_REMOTE.equals(storageMode);
  }

  public boolean hasStoredRemotePassword() {
    return remotePasswordCipher != null && !remotePasswordCipher.isBlank();
  }
}
