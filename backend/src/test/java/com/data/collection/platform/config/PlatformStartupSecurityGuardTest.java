package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class PlatformStartupSecurityGuardTest {
  @Test
  void shouldRejectUnsafeDefaultsByDefault() {
    PlatformAuthProperties properties = new PlatformAuthProperties();

    PlatformStartupSecurityGuard guard =
        new PlatformStartupSecurityGuard(properties, new MockEnvironment());

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PLATFORM_ADMIN_PASSWORD")
        .hasMessageContaining("DATASOURCE_PASSWORD")
        .hasMessageContaining("GITLAB_WEB_BASE_URL");
  }

  @Test
  void shouldAllowLocalDefaultCredentialsWhenSecureConfigNotRequired() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    properties.setSecureConfigRequired(false);

    PlatformStartupSecurityGuard guard =
        new PlatformStartupSecurityGuard(properties, new MockEnvironment());

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void shouldRejectUnsafeDefaultsWhenSecureConfigRequired() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    properties.setSecureConfigRequired(true);

    PlatformStartupSecurityGuard guard =
        new PlatformStartupSecurityGuard(properties, new MockEnvironment());

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PLATFORM_ADMIN_PASSWORD")
        .hasMessageContaining("DATASOURCE_PASSWORD")
        .hasMessageContaining("GITLAB_WEB_BASE_URL");
  }

  @Test
  void shouldRejectPlaintextLocalPasswordsWhenSecureConfigRequired() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    properties.setAdminPassword("changed-admin-password");
    properties.setApprovalPassword("changed-approval-password");
    MockEnvironment environment =
        secureEnvironment();

    PlatformStartupSecurityGuard guard = new PlatformStartupSecurityGuard(properties, environment);

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PLATFORM_ADMIN_PASSWORD 必须使用")
        .hasMessageContaining("PLATFORM_APPROVAL_PASSWORD 必须使用");
  }

  @Test
  void shouldAllowHashedLocalPasswordsWhenSecureConfigRequired() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    properties.setAdminPassword(encoder.encode("changed-admin-password"));
    properties.setApprovalPassword(encoder.encode("changed-approval-password"));

    PlatformStartupSecurityGuard guard =
        new PlatformStartupSecurityGuard(properties, secureEnvironment());

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  private MockEnvironment secureEnvironment() {
    return new MockEnvironment()
        .withProperty("spring.datasource.password", "db-password")
        .withProperty("platform.gitlab-mirror.web-base-url", "https://gitlab.example.test");
  }
}
