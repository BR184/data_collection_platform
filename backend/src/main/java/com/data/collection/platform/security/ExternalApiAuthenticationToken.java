package com.data.collection.platform.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

public class ExternalApiAuthenticationToken extends AbstractAuthenticationToken {
  private final ExternalApiClientPrincipal principal;

  public ExternalApiAuthenticationToken(ExternalApiClientPrincipal principal) {
    super((Collection<? extends GrantedAuthority>) null);
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return "";
  }

  @Override
  public ExternalApiClientPrincipal getPrincipal() {
    return principal;
  }
}
