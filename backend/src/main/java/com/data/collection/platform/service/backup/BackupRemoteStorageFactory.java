package com.data.collection.platform.service.backup;

/**
 * 远程存储会话工厂；生产实现基于 sshj，测试注入内嵌 SSH 服务器或替身。
 * open 仅完成 TCP/密钥交换并采集指纹，绝不发送凭据——认证由调用方在指纹校验后显式触发。
 */
@FunctionalInterface
public interface BackupRemoteStorageFactory {
  BackupRemoteStorage open(BackupRemoteEndpoint endpoint);
}
