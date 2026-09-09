package com.data.collection.platform.service.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 备份功能的部署配置；备份策略（时刻/保留份数/远程目标）存放在数据库 backup_settings。 */
@ConfigurationProperties(prefix = "platform.backup")
public class BackupConfigurationProperties {
  /** 本地备份根目录：容器内为 compose 挂载点，直跑开发时落在 .tmp 下。 */
  private String root = "./.tmp/qaflex-backups";
  /** 实例标识：进入备份目录名与文件名；缺省回退 platform.instance.id。 */
  private String instanceLabel = "";
  /** 远程密码加密主密钥（base64 编码的 32 字节）；未配置时仅禁用远程备份。 */
  private String secretKey = "";
  private String pgDumpBin = "pg_dump";
  private String pgRestoreBin = "pg_restore";
  private int dumpTimeoutMinutes = 60;
  private int leaseSeconds = 300;
  private int headroomMb = 1024;
  private long schedulerDelayMs = 60_000;
  private long schedulerInitialDelayMs = 15_000;
  private int remoteConnectTimeoutMs = 10_000;

  public String getRoot() {
    return root;
  }

  public void setRoot(String root) {
    this.root = root;
  }

  public String getInstanceLabel() {
    return instanceLabel;
  }

  public void setInstanceLabel(String instanceLabel) {
    this.instanceLabel = instanceLabel;
  }

  public String getSecretKey() {
    return secretKey;
  }

  public void setSecretKey(String secretKey) {
    this.secretKey = secretKey;
  }

  public String getPgDumpBin() {
    return pgDumpBin;
  }

  public void setPgDumpBin(String pgDumpBin) {
    this.pgDumpBin = pgDumpBin;
  }

  public String getPgRestoreBin() {
    return pgRestoreBin;
  }

  public void setPgRestoreBin(String pgRestoreBin) {
    this.pgRestoreBin = pgRestoreBin;
  }

  public int getDumpTimeoutMinutes() {
    return dumpTimeoutMinutes;
  }

  public void setDumpTimeoutMinutes(int dumpTimeoutMinutes) {
    this.dumpTimeoutMinutes = dumpTimeoutMinutes;
  }

  public int getLeaseSeconds() {
    return leaseSeconds;
  }

  public void setLeaseSeconds(int leaseSeconds) {
    this.leaseSeconds = leaseSeconds;
  }

  public int getHeadroomMb() {
    return headroomMb;
  }

  public void setHeadroomMb(int headroomMb) {
    this.headroomMb = headroomMb;
  }

  public long getSchedulerDelayMs() {
    return schedulerDelayMs;
  }

  public void setSchedulerDelayMs(long schedulerDelayMs) {
    this.schedulerDelayMs = schedulerDelayMs;
  }

  public long getSchedulerInitialDelayMs() {
    return schedulerInitialDelayMs;
  }

  public void setSchedulerInitialDelayMs(long schedulerInitialDelayMs) {
    this.schedulerInitialDelayMs = schedulerInitialDelayMs;
  }

  public int getRemoteConnectTimeoutMs() {
    return remoteConnectTimeoutMs;
  }

  public void setRemoteConnectTimeoutMs(int remoteConnectTimeoutMs) {
    this.remoteConnectTimeoutMs = remoteConnectTimeoutMs;
  }
}
