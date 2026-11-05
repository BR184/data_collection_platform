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

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }
}
