package com.data.collection.platform.entity.backup;

/**
 * 备份配置视图。远程密码永不回显（仅 hasRemotePassword 布尔）；
 * localRootEffective 为只读展示的实际本地根目录，secretKeyConfigured 指示服务端主密钥是否可用。
 */
public record BackupSettingsResponse(
    boolean enabled,
    String scheduleTime,
    int retentionCopies,
    String storageMode,
    String localSubdirectory,
    String remoteHost,
    int remotePort,
    String remoteUsername,
    boolean hasRemotePassword,
    String remoteDirectory,
    String remoteHostKeyFingerprint,
    long version,
    String updatedAt,
    String localRootEffective,
    boolean secretKeyConfigured,
    String instanceLabel) {}
