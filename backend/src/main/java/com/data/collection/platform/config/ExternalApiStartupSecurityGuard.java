package com.data.collection.platform.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validates the deployment-owned external API client contract before serving traffic. */
@Component
public class ExternalApiStartupSecurityGuard implements ApplicationRunner {
  private static final String SHA_256_PATTERN = "[0-9a-fA-F]{64}";

  private final ExternalApiProperties properties;

  public ExternalApiStartupSecurityGuard(ExternalApiProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.isEnabled()) {
      return;
    }
    List<String> errors = validateClients(properties.getClients());
    if (!errors.isEmpty()) {
      throw new IllegalStateException("外部 API 安全配置检查失败：" + String.join("；", errors));
    }
  }

  private List<String> validateClients(List<ExternalApiProperties.Client> clients) {
    if (clients == null || clients.isEmpty()) {
      return List.of("PLATFORM_EXTERNAL_API_CLIENTS 至少配置一个客户端");
    }
    List<String> errors = new ArrayList<>();
    Set<String> clientIds = new HashSet<>();
    for (int index = 0; index < clients.size(); index++) {
      ExternalApiProperties.Client client = clients.get(index);
      String prefix = "PLATFORM_EXTERNAL_API_CLIENTS_" + index;
      if (client == null) {
        errors.add(prefix + " 不能为空");
        continue;
      }
      String clientId = StringUtils.hasText(client.getClientId()) ? client.getClientId().trim() : "";
      if (clientId.isEmpty()) {
        errors.add(prefix + "_CLIENT_ID client-id 不能为空");
      } else if (!clientIds.add(clientId)) {
        errors.add("client-id 重复: " + clientId);
      }
      String tokenHash = StringUtils.hasText(client.getTokenSha256())
          ? client.getTokenSha256().trim() : "";
      if (!tokenHash.matches(SHA_256_PATTERN)) {
        errors.add(prefix + "_TOKEN_SHA256 token-sha256 必须是 64 位十六进制");
      }
      boolean hasAllowedDataset = client.getAllowedDatasets().stream().anyMatch(StringUtils::hasText);
      if (!hasAllowedDataset) {
        errors.add(prefix + "_ALLOWED_DATASETS allowed-datasets 不能为空");
      }
    }
    return errors;
  }
}
