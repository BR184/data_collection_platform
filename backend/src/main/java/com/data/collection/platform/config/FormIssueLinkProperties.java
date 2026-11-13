package com.data.collection.platform.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 投放批大小与网络超时；业务开关和平台地址由管理员按来源/项目持久配置。 */
@ConfigurationProperties(prefix = "platform.form-issue-link")
public class FormIssueLinkProperties {

  /** 每轮补发最多处理的已登记议题数；无积压时保持轻量。 */
  private int batchSize = 20;

  /** 单次 GitLab 评论请求超时。 */
  private Duration requestTimeout = Duration.ofSeconds(10);

  /** 评论专用数据库操作预算，不改变全平台查询超时。 */
  private int databaseTimeoutSeconds = 5;

  public int getDatabaseTimeoutSeconds() {
    return databaseTimeoutSeconds;
  }

  public void setDatabaseTimeoutSeconds(int seconds) {
    if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("评论数据库预算须为1至60秒");
    databaseTimeoutSeconds = seconds;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("评论批大小须为1至100");
    this.batchSize = batchSize;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
        || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException("评论请求预算须大于0且不超过60秒");
    }
    this.requestTimeout = requestTimeout;
  }
}
