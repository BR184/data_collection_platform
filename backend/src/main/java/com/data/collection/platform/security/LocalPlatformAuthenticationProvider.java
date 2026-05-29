package com.data.collection.platform.security;

import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class LocalPlatformAuthenticationProvider implements PlatformAuthenticationProvider {
  private final PlatformAuthProperties properties;
  private final PasswordEncoder passwordEncoder;

  public LocalPlatformAuthenticationProvider(PlatformAuthProperties properties) {
    this(properties, PasswordEncoderFactories.createDelegatingPasswordEncoder());
  }

  LocalPlatformAuthenticationProvider(PlatformAuthProperties properties, PasswordEncoder passwordEncoder) {
    this.properties = properties;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public AuthUserResponse authenticate(String username, String password) {
    String normalizedUsername = username.trim();
    if (normalizedUsername.equals(properties.getAdminUsername())
        && matchesCredential(password, properties.getAdminPassword())) {
      return new AuthUserResponse(normalizedUsername, "管理员", AuthRole.ADMIN, true);
    }
    if (normalizedUsername.equals(properties.getApprovalUsername())
        && matchesCredential(password, properties.getApprovalPassword())) {
      return new AuthUserResponse(normalizedUsername, "审批用户", AuthRole.APPROVAL, true);
    }
    return null;
  }

  private boolean matchesCredential(String rawPassword, String configuredPassword) {
    if (configuredPassword == null || rawPassword == null) {
      return false;
    }
    if (configuredPassword.startsWith("{")) {
      return passwordEncoder.matches(rawPassword, configuredPassword);
    }
    return MessageDigest.isEqual(
        rawPassword.getBytes(StandardCharsets.UTF_8),
        configuredPassword.getBytes(StandardCharsets.UTF_8));
  }
}
