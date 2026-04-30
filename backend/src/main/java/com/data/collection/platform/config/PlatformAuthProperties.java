package com.data.collection.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.auth")
public class PlatformAuthProperties {
  private String adminUsername = "admin";
  private String adminPassword = "admin123";
  private String approvalUsername = "approval";
  private String approvalPassword = "approval";

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
}
