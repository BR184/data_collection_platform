package com.data.collection.platform.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlatformStartupSecurityGuard implements ApplicationRunner {
  private final PlatformAuthProperties authProperties;
  private final Environment environment;

  public PlatformStartupSecurityGuard(PlatformAuthProperties authProperties, Environment environment) {
    this.authProperties = authProperties;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!authProperties.isSecureConfigRequired()) {
      return;
    }
    List<String> errors = new ArrayList<>();
    if (isDefaultAdminCredential()) {
      errors.add("PLATFORM_ADMIN_PASSWORD 不能使用默认值 admin123");
    }
    if (isDefaultApprovalCredential()) {
      errors.add("PLATFORM_APPROVAL_PASSWORD 不能使用默认值 approval");
    }
    if (!StringUtils.hasText(environment.getProperty("spring.datasource.password"))) {
      errors.add("DATASOURCE_PASSWORD 不能为空");
    }
    String gitlabWebBaseUrl = environment.getProperty("platform.gitlab-mirror.web-base-url", "");
    if (!StringUtils.hasText(gitlabWebBaseUrl) || "http://localhost".equals(gitlabWebBaseUrl)) {
      errors.add("GITLAB_WEB_BASE_URL 不能留空或使用默认 http://localhost");
    }
    if (!errors.isEmpty()) {
      throw new IllegalStateException("安全配置检查失败：" + String.join("；", errors));
    }
  }

  private boolean isDefaultAdminCredential() {
    return "admin".equals(authProperties.getAdminUsername())
        && "admin123".equals(authProperties.getAdminPassword());
  }

  private boolean isDefaultApprovalCredential() {
    return "approval".equals(authProperties.getApprovalUsername())
        && "approval".equals(authProperties.getApprovalPassword());
  }
}
