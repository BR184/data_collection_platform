package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.auth")
public class PlatformAuthProperties {
  private String provider = "local";
  private String adminUsername = "admin";
  private String adminPassword = "admin123";
  private String approvalUsername = "approval";
  private String approvalPassword = "approval";
  private boolean secureConfigRequired = true;
  private boolean csrfEnabled = true;
  private Ldap ldap = new Ldap();

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getAdminUsername() {
    return adminUsername;
  }

  public void setAdminUsername(String adminUsername) {
    this.adminUsername = adminUsername;
  }

  public String getAdminPassword() {
    return adminPassword;
  }

  public void setAdminPassword(String adminPassword) {
    this.adminPassword = adminPassword;
  }

  public String getApprovalUsername() {
    return approvalUsername;
  }

  public void setApprovalUsername(String approvalUsername) {
    this.approvalUsername = approvalUsername;
  }

  public String getApprovalPassword() {
    return approvalPassword;
  }

  public void setApprovalPassword(String approvalPassword) {
    this.approvalPassword = approvalPassword;
  }

  public boolean isSecureConfigRequired() {
    return secureConfigRequired;
  }

  public void setSecureConfigRequired(boolean secureConfigRequired) {
    this.secureConfigRequired = secureConfigRequired;
  }

  public boolean isCsrfEnabled() {
    return csrfEnabled;
  }

  public void setCsrfEnabled(boolean csrfEnabled) {
    this.csrfEnabled = csrfEnabled;
  }

  public Ldap getLdap() {
    return ldap;
  }

  public void setLdap(Ldap ldap) {
    this.ldap = ldap;
  }

  public static class Ldap {
    private String baseUrl = "http://127.0.0.1:24837";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private boolean initialSyncRequired = true;

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
      return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
      this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
      return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isInitialSyncRequired() {
      return initialSyncRequired;
    }

    public void setInitialSyncRequired(boolean initialSyncRequired) {
      this.initialSyncRequired = initialSyncRequired;
    }
  }
}
