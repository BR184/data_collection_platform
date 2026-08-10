package com.data.collection.platform.bi.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CAT 传输保护、路径和同步调度壳的部署配置；业务配置与稳定 ID 映射存放在数据库。 */
@ConfigurationProperties(prefix = "platform.bi.cat")
public class BiCatProperties {
  private Paths paths = new Paths();
  private long schedulerDelayMs = 60_000;
  private long initialDelayMs = 30_000;
  private int leaseMinutes = 30;
  private int connectTimeoutMs = 3_000;
  private int readTimeoutMs = 15_000;
  private int maxResponseBytes = 5 * 1024 * 1024;
  private int retainedSnapshotCount = 10;

  public Paths getPaths() {
    return paths;
  }

  public void setPaths(Paths paths) {
    this.paths = paths == null ? new Paths() : paths;
  }

  public long getSchedulerDelayMs() {
    return schedulerDelayMs;
  }

  public void setSchedulerDelayMs(long schedulerDelayMs) {
    this.schedulerDelayMs = schedulerDelayMs;
  }

  public long getInitialDelayMs() {
    return initialDelayMs;
  }

  public void setInitialDelayMs(long initialDelayMs) {
    this.initialDelayMs = initialDelayMs;
  }

  public int getLeaseMinutes() {
    return leaseMinutes;
  }

  public void setLeaseMinutes(int leaseMinutes) {
    this.leaseMinutes = leaseMinutes;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public int getMaxResponseBytes() {
    return maxResponseBytes;
  }

  public void setMaxResponseBytes(int maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
  }

  public int getRetainedSnapshotCount() {
    return retainedSnapshotCount;
  }

  public void setRetainedSnapshotCount(int retainedSnapshotCount) {
    this.retainedSnapshotCount = retainedSnapshotCount;
  }

  /** CAT 部署可能增加上下文前缀，因此四个运行路径均由部署配置覆盖。 */
  public static class Paths {
    private String projects = "/integrationSearch/getAllProject";
    private String phaseTree = "/testingPhase/getAllByProjectId";
    private String statistics = "/integrationSearch/getStatisticsInfoByTPId";
    private String featureStatistics = "/getFeatureInfoByModuleId";

    public String getProjects() {
      return projects;
    }

    public void setProjects(String projects) {
      this.projects = projects;
    }

    public String getPhaseTree() {
      return phaseTree;
    }

    public void setPhaseTree(String phaseTree) {
      this.phaseTree = phaseTree;
    }

    public String getStatistics() {
      return statistics;
    }

    public void setStatistics(String statistics) {
      this.statistics = statistics;
    }

    public String getFeatureStatistics() {
      return featureStatistics;
    }

    public void setFeatureStatistics(String featureStatistics) {
      this.featureStatistics = featureStatistics;
    }
  }

}
