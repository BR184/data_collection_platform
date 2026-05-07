package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class PlatformStartupSecurityGuardTest {
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
}
