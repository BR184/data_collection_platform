package com.data.collection.platform.config;

import java.time.LocalDateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 表单接管既有消费链的启用点。
 *
 * <p>启用点按资源创建时间一次性分流：创建时间不早于启用点的资源读取表单权威记录，其余资源
 * 保持原评论来源。未配置时一律不接管，避免部署后自动改变既有口径。
 */
@ConfigurationProperties(prefix = "platform.form-adoption")
public class FormAdoptionProperties {
  private LocalDateTime issueEnabledAt;

  public LocalDateTime getIssueEnabledAt() {
    return issueEnabledAt;
  }

  public void setIssueEnabledAt(LocalDateTime issueEnabledAt) {
    this.issueEnabledAt = issueEnabledAt;
  }
}
