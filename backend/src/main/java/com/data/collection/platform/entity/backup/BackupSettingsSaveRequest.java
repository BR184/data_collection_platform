package com.data.collection.platform.entity.backup;

/**
 * 备份配置保存请求。remotePassword 为空表示不修改已存密码；version 为乐观锁期望值
 * （配置尚未落库时传 0）。字段合法性由服务层统一校验并给出中文业务错误。
 */
public record BackupSettingsSaveRequest(
    boolean enabled,
    String scheduleTime,
    Integer retentionCopies,
    String storageMode,
    String localSubdirectory,
    String remoteHost,
    Integer remotePort,
    String remoteUsername,
    String remotePassword,
    String remoteDirectory,
    String remoteHostKeyFingerprint,
    Long version) {}
