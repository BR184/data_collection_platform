package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ExternalApiStartupSecurityGuardTest {
  @Test
  void shouldAllowDisabledExternalApiWithoutClients() {
    ExternalApiProperties properties = new ExternalApiProperties();

    ExternalApiStartupSecurityGuard guard = new ExternalApiStartupSecurityGuard(properties);

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void shouldRejectEnabledExternalApiWithoutClients() {
    ExternalApiProperties properties = new ExternalApiProperties();
    properties.setEnabled(true);

    ExternalApiStartupSecurityGuard guard = new ExternalApiStartupSecurityGuard(properties);

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("至少配置一个客户端");
  }

  @Test
  void shouldRejectInvalidClientContract() {
    ExternalApiProperties properties = enabledProperties(client("", "invalid", List.of()));

    ExternalApiStartupSecurityGuard guard = new ExternalApiStartupSecurityGuard(properties);

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-id 不能为空")
        .hasMessageContaining("token-sha256 必须是 64 位十六进制")
        .hasMessageContaining("allowed-datasets 不能为空");
  }

  @Test
  void shouldRejectDuplicateClientIds() {
    ExternalApiProperties properties = enabledProperties(
        client("bi-dashboard", "a".repeat(64), List.of("bi-dashboard")),
        client("bi-dashboard", "b".repeat(64), List.of("bi-dashboard")));

    ExternalApiStartupSecurityGuard guard = new ExternalApiStartupSecurityGuard(properties);

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("client-id 重复: bi-dashboard");
  }

  @Test
  void shouldAllowValidExternalApiConfiguration() {
    ExternalApiProperties properties = enabledProperties(
        client("bi-dashboard", "a".repeat(64), List.of("bi-dashboard")));

    ExternalApiStartupSecurityGuard guard = new ExternalApiStartupSecurityGuard(properties);

    assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  private ExternalApiProperties enabledProperties(ExternalApiProperties.Client... clients) {
    ExternalApiProperties properties = new ExternalApiProperties();
    properties.setEnabled(true);
    properties.setClients(List.of(clients));
    return properties;
  }

  private ExternalApiProperties.Client client(
      String clientId, String tokenSha256, List<String> allowedDatasets) {
    ExternalApiProperties.Client client = new ExternalApiProperties.Client();
    client.setClientId(clientId);
    client.setTokenSha256(tokenSha256);
    client.setAllowedDatasets(allowedDatasets);
    return client;
  }
}
