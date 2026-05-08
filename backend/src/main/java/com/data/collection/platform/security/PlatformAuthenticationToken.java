package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class PlatformAuthenticationToken extends AbstractAuthenticationToken {
  private final AuthUserResponse user;

  public PlatformAuthenticationToken(AuthUserResponse user) {
    super(authoritiesFor(user));
    this.user = user;
    setAuthenticated(user != null && user.authenticated());
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public AuthUserResponse getPrincipal() {
    return user;
  }

  private static Collection<? extends GrantedAuthority> authoritiesFor(AuthUserResponse user) {
    if (user == null || !user.authenticated()) {
      return List.of();
    }
    return List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
  }
}
