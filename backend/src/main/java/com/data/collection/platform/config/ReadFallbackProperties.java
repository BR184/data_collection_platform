package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 持续可读版本的运行策略。
 *
 * <p>这些是策略控制的推荐初值，不构成容量证明；目标负载基准后再调整。前台等待与后台跟踪只影响交互与告警，
 * 不影响“已发布版本始终可读”这一不变量。
 */
@ConfigurationProperties(prefix = "platform.read-fallback")
public class ReadFallbackProperties {
  /** 手动刷新前台等待总上限（毫秒）；超时后回到可操作的旧完整视图并继续后台跟踪。 */
  private int foregroundWaitMs = 5000;
  /** 页面失联后租约自然失效时长（秒）。 */
  private int pageLeaseSeconds = 300;
  /** 可见页面续约间隔（秒）。 */
  private int heartbeatSeconds = 60;
  /** 已出现新版本的历史页进入安全换版的时限（分钟）；不作用于“当前版已经很老”。 */
  private int historicalViewMaxAgeMinutes = 60;
  /** 无引用历史版本的最小回收宽限（分钟）。 */
  private int retirementGraceMinutes = 15;
  /** 低优先级有界清理批次行数。 */
  private int gcBatchSize = 500;
  /** 已批准：仅控制管理员告警升级，不删除当前版本、不关闭读取。 */
  private int escalateAfterHours = 24;

  public int getForegroundWaitMs() {
    return foregroundWaitMs;
  }

  public void setForegroundWaitMs(int foregroundWaitMs) {
    this.foregroundWaitMs = foregroundWaitMs;
  }

  public int getPageLeaseSeconds() {
    return pageLeaseSeconds;
  }

  public void setPageLeaseSeconds(int pageLeaseSeconds) {
    this.pageLeaseSeconds = pageLeaseSeconds;
  }

  public int getHeartbeatSeconds() {
    return heartbeatSeconds;
  }

  public void setHeartbeatSeconds(int heartbeatSeconds) {
    this.heartbeatSeconds = heartbeatSeconds;
  }

  public int getHistoricalViewMaxAgeMinutes() {
    return historicalViewMaxAgeMinutes;
  }

  public void setHistoricalViewMaxAgeMinutes(int historicalViewMaxAgeMinutes) {
    this.historicalViewMaxAgeMinutes = historicalViewMaxAgeMinutes;
  }

  public int getRetirementGraceMinutes() {
    return retirementGraceMinutes;
  }

  public void setRetirementGraceMinutes(int retirementGraceMinutes) {
    this.retirementGraceMinutes = retirementGraceMinutes;
  }

  public int getGcBatchSize() {
    return gcBatchSize;
  }

  public void setGcBatchSize(int gcBatchSize) {
    this.gcBatchSize = gcBatchSize;
  }

  public int getEscalateAfterHours() {
    return escalateAfterHours;
  }

  public void setEscalateAfterHours(int escalateAfterHours) {
    this.escalateAfterHours = escalateAfterHours;
  }
}
