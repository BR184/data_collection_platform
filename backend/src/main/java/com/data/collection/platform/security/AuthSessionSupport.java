package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthSessionSupport {
  public static final String SESSION_USER_KEY = "platform.auth.user";

  private AuthSessionSupport() {}

  public static AuthUserResponse currentUser(HttpServletRequest request) {
    AuthUserResponse securityUser = currentSecurityUser();
    if (securityUser.authenticated()) {
      return securityUser;
    }
    HttpSession session = request.getSession(false);
    if (session == null) {
      return AuthUserResponse.guest();
    }
    Object user = session.getAttribute(SESSION_USER_KEY);
    return user instanceof AuthUserResponse authUser ? authUser : AuthUserResponse.guest();
  }

  public static void updateUser(HttpServletRequest request, AuthUserResponse user) {
    HttpSession session = request.getSession(false);
    if (session != null && user != null) {
      session.setAttribute(SESSION_USER_KEY, user);
    }
    if (user != null && user.authenticated()) {
      SecurityContextHolder.getContext().setAuthentication(new PlatformAuthenticationToken(user));
    }
  }

  private static AuthUserResponse currentSecurityUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return AuthUserResponse.guest();
    }
    Object principal = authentication.getPrincipal();
    return principal instanceof AuthUserResponse authUser ? authUser : AuthUserResponse.guest();
  }

}
