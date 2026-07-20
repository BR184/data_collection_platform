package com.data.collection.platform.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for server-to-server, read-only data access. */
@ConfigurationProperties(prefix = "platform.external-api")
public class ExternalApiProperties {
  private boolean enabled;
  private List<Client> clients = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public List<Client> getClients() {
    return clients;
  }

  public void setClients(List<Client> clients) {
    this.clients = clients == null ? new ArrayList<>() : new ArrayList<>(clients);
  }

  public static class Client {
    private String clientId;
    private String tokenSha256;
    private List<String> allowedDatasets = new ArrayList<>();

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getTokenSha256() {
      return tokenSha256;
    }

    public void setTokenSha256(String tokenSha256) {
      this.tokenSha256 = tokenSha256;
    }

    public List<String> getAllowedDatasets() {
      return allowedDatasets;
    }

    public void setAllowedDatasets(List<String> allowedDatasets) {
      this.allowedDatasets = allowedDatasets == null ? new ArrayList<>() : new ArrayList<>(allowedDatasets);
    }
  }
}
