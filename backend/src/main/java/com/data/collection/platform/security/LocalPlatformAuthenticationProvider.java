package com.data.collection.platform.security;

import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import org.springframework.stereotype.Component;

@Component
public class LocalPlatformAuthenticationProvider implements PlatformAuthenticationProvider {
  private final PlatformAuthProperties properties;

  public LocalPlatformAuthenticationProvider(PlatformAuthProperties properties) {
    this.properties = properties;
  }

  @Override
  public AuthUserResponse authenticate(String username, String password) {
    String normalizedUsername = username.trim();
    if (normalizedUsername.equals(properties.getAdminUsername()) && password.equals(properties.getAdminPassword())) {
      return new AuthUserResponse(normalizedUsername, "管理员", AuthRole.ADMIN, true);
    }
    if (normalizedUsername.equals(properties.getApprovalUsername()) && password.equals(properties.getApprovalPassword())) {
      return new AuthUserResponse(normalizedUsername, "审批用户", AuthRole.APPROVAL, true);
    }
    return null;
  }
}
