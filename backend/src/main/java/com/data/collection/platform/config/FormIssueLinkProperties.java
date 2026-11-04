package com.data.collection.platform.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 表单就绪投放开关与链接口径。
 *
 * <p>默认关闭：未显式开启时不登记、不发送，生产配置、凭据与其他工具评论保持原状。开启后仅对
 * 已登记 {@code FORM} 权威的 CCProduct 议题，在完整来源发布事务内登记，并发布一条同时包含
 * 响应、回复两个入口的评论；平台对外地址缺失时无法生成入口链接，同样不发送。
 */
@ConfigurationProperties(prefix = "platform.form-issue-link")
public class FormIssueLinkProperties {

  /** 是否开启就绪投放；默认关闭。 */
  private boolean enabled;

  /** 平台对外访问地址（协议+主机+可选端口），用于生成填写入口链接；空值视为不可投放。 */
  private String externalBaseUrl;

  /** 每轮补发最多处理的已登记议题数；无积压时保持轻量。 */
  private int batchSize = 20;

  /** 单次 GitLab 评论请求超时。 */
  private Duration requestTimeout = Duration.ofSeconds(10);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getExternalBaseUrl() {
    return externalBaseUrl;
  }

  public void setExternalBaseUrl(String externalBaseUrl) {
    this.externalBaseUrl = externalBaseUrl;
  }

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
