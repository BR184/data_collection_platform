package com.data.collection.platform.config;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private String url = "";
    private String baseDn = "";
    private String userSearchBase = "";
    private String userSearchFilter = "";
    private String managerDn = "";
    private String managerPassword = "";
    private String groupSearchBase = "";
    private String groupSearchFilter = "";
    private Map<String, String> groupRoleMappings = new LinkedHashMap<>();

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getBaseDn() {
      return baseDn;
    }

    public void setBaseDn(String baseDn) {
      this.baseDn = baseDn;
    }

    public String getUserSearchBase() {
      return userSearchBase;
    }

    public void setUserSearchBase(String userSearchBase) {
      this.userSearchBase = userSearchBase;
    }

    public String getUserSearchFilter() {
      return userSearchFilter;
    }

    public void setUserSearchFilter(String userSearchFilter) {
      this.userSearchFilter = userSearchFilter;
    }

    public String getManagerDn() {
      return managerDn;
    }

    public void setManagerDn(String managerDn) {
      this.managerDn = managerDn;
    }

    public String getManagerPassword() {
      return managerPassword;
    }

    public void setManagerPassword(String managerPassword) {
      this.managerPassword = managerPassword;
    }

    public String getGroupSearchBase() {
      return groupSearchBase;
    }

    public void setGroupSearchBase(String groupSearchBase) {
      this.groupSearchBase = groupSearchBase;
    }

    public String getGroupSearchFilter() {
      return groupSearchFilter;
    }

    public void setGroupSearchFilter(String groupSearchFilter) {
      this.groupSearchFilter = groupSearchFilter;
    }

    public Map<String, String> getGroupRoleMappings() {
      return groupRoleMappings;
    }

    public void setGroupRoleMappings(Map<String, String> groupRoleMappings) {
      this.groupRoleMappings = groupRoleMappings;
    }
  }
}
