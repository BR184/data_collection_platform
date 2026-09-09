package com.data.collection.platform.entity.backup;

/**
 * 远程连接测试请求：使用提交的配置（未保存也可测）做纯只读三查。
 * remotePassword 为空且服务端已有保存密码时使用已存密码；remoteHostKeyFingerprint 为已保存
 * 指纹（或空表示首次采集）。
 */
public record BackupConnectionTestRequest(
    String remoteHost,
    Integer remotePort,
    String remoteUsername,
    String remotePassword,
    String remoteDirectory,
    String remoteHostKeyFingerprint) {}
