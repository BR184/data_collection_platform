package com.data.collection.platform.security;

import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.PlatformPermissionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "platform.auth", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalPlatformAuthenticationProvider implements PlatformAuthenticationProvider {
  private final PlatformAuthProperties properties;
  private final PasswordEncoder passwordEncoder;
  private final PlatformPermissionService permissionService;

  @Autowired
  public LocalPlatformAuthenticationProvider(
      PlatformAuthProperties properties, PlatformPermissionService permissionService) {
    this(properties, permissionService, PasswordEncoderFactories.createDelegatingPasswordEncoder());
  }

  public LocalPlatformAuthenticationProvider(PlatformAuthProperties properties) {
    this(properties, null, PasswordEncoderFactories.createDelegatingPasswordEncoder());
  }

  LocalPlatformAuthenticationProvider(
      PlatformAuthProperties properties,
      PlatformPermissionService permissionService,
      PasswordEncoder passwordEncoder) {
    this.properties = properties;
    this.permissionService = permissionService;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  public AuthUserResponse authenticate(String username, String password) {
    String normalizedUsername = username.trim();
    if (normalizedUsername.equals(properties.getAdminUsername())
        && matchesCredential(password, properties.getAdminPassword())) {
      return buildUser(normalizedUsername, "管理员", "SUPER_ADMIN");
    }
    if (normalizedUsername.equals(properties.getApprovalUsername())
        && matchesCredential(password, properties.getApprovalPassword())) {
      return buildUser(normalizedUsername, "审批用户", "NORMAL_USER");
    }
    return null;
  }

  private AuthUserResponse buildUser(String username, String displayName, String roleCode) {
    java.util.Set<String> permissions = permissionService == null
        ? java.util.Set.of()
        : permissionService.permissionsForRoles(java.util.Set.of(roleCode));
    java.util.List<String> roleNames = permissionService == null
        ? java.util.List.of(displayName)
        : permissionService.roleNames(java.util.Set.of(roleCode));
    return new AuthUserResponse(username, displayName, java.util.Set.of(roleCode),
        roleNames.isEmpty() ? java.util.List.of(displayName) : roleNames, permissions, true);
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
