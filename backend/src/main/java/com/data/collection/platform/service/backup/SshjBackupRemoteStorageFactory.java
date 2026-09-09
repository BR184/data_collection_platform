package com.data.collection.platform.service.backup;

import org.springframework.stereotype.Component;

/** 生产环境的远程备份存储工厂：基于 sshj，连接超时取部署配置。 */
@Component
public class SshjBackupRemoteStorageFactory implements BackupRemoteStorageFactory {
  private final BackupConfigurationProperties properties;

  public SshjBackupRemoteStorageFactory(BackupConfigurationProperties properties) {
    this.properties = properties;
  }

  @Override
  public BackupRemoteStorage open(BackupRemoteEndpoint endpoint) {
    return new SshjBackupRemoteStorage(endpoint, properties.getRemoteConnectTimeoutMs());
  }
}
