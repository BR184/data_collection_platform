package com.data.collection.platform.security;

import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthUserResponse;

public class LdapPlatformAuthenticationProvider implements PlatformAuthenticationProvider {
  private final PlatformAuthProperties.Ldap properties;

  public LdapPlatformAuthenticationProvider(PlatformAuthProperties.Ldap properties) {
    this.properties = properties;
  }

  @Override
  public AuthUserResponse authenticate(String username, String password) {
    throw new UnsupportedOperationException("LDAP authentication is reserved for the unified account platform.");
  }

  public PlatformAuthProperties.Ldap getProperties() {
    return properties;
  }
}
