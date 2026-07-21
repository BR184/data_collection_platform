package com.data.collection.platform.config;

import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
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
    List<String> errors = new ArrayList<>();
    validateAuthenticationProvider(errors);
    if (authProperties.isSecureConfigRequired()) {
      validateSecureConfiguration(errors);
    }
    if (!errors.isEmpty()) {
      throw new IllegalStateException("安全配置检查失败：" + String.join("；", errors));
    }
  }

  private void validateAuthenticationProvider(List<String> errors) {
    String provider = normalizedProvider();
    if (!"ldap".equals(provider) && !"local".equals(provider)) {
      errors.add("PLATFORM_AUTH_PROVIDER 仅支持 ldap 或 local");
      return;
    }
    if ("ldap".equals(provider) && !isValidHttpUrl(authProperties.getLdap().getBaseUrl())) {
      errors.add("PLATFORM_LDAP_BASE_URL 必须是有效的 HTTP(S) 地址");
    }
  }

  private void validateSecureConfiguration(List<String> errors) {
    if (isLocalProvider()) {
      if (isDefaultAdminCredential()) {
        errors.add("PLATFORM_ADMIN_PASSWORD 不能使用默认值 admin123");
      }
      if (isDefaultApprovalCredential()) {
        errors.add("PLATFORM_APPROVAL_PASSWORD 不能使用默认值 approval");
      }
      if (!isPasswordHash(authProperties.getAdminPassword())) {
        errors.add("PLATFORM_ADMIN_PASSWORD 必须使用 {bcrypt} 等 Spring Security password hash");
      }
      if (!isPasswordHash(authProperties.getApprovalPassword())) {
        errors.add("PLATFORM_APPROVAL_PASSWORD 必须使用 {bcrypt} 等 Spring Security password hash");
      }
    }
    if (!StringUtils.hasText(environment.getProperty("spring.datasource.password"))) {
      errors.add("DATASOURCE_PASSWORD 不能为空");
    }
    String gitlabWebBaseUrl = environment.getProperty("platform.gitlab-mirror.web-base-url", "");
    if (!StringUtils.hasText(gitlabWebBaseUrl) || "http://localhost".equals(gitlabWebBaseUrl)) {
      errors.add("GITLAB_WEB_BASE_URL 不能留空或使用默认 http://localhost");
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

  private boolean isLocalProvider() {
    return "local".equals(normalizedProvider());
  }

  private boolean isPasswordHash(String password) {
    return StringUtils.hasText(password) && password.trim().startsWith("{");
  }

  private String normalizedProvider() {
    return StringUtils.hasText(authProperties.getProvider())
        ? authProperties.getProvider().trim().toLowerCase(Locale.ROOT) : "";
  }

  private boolean isValidHttpUrl(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    try {
      URI uri = new URI(value.trim());
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && StringUtils.hasText(uri.getHost());
    } catch (URISyntaxException exception) {
      return false;
    }
  }
}
