package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

public final class AuthSessionSupport {
  public static final String SESSION_USER_KEY = "platform.auth.user";

  private AuthSessionSupport() {}

  public static AuthUserResponse currentUser(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return AuthUserResponse.guest();
    }
    Object user = session.getAttribute(SESSION_USER_KEY);
    return user instanceof AuthUserResponse authUser ? authUser : AuthUserResponse.guest();
  }

  public static boolean hasRole(AuthUserResponse user, AuthRole requiredRole) {
    if (user == null || !user.authenticated()) {
      return false;
    }
    if (requiredRole == AuthRole.ADMIN) {
      return user.role() == AuthRole.ADMIN;
    }
    if (requiredRole == AuthRole.APPROVAL) {
      return user.role() == AuthRole.APPROVAL || user.role() == AuthRole.ADMIN;
    }
    return user.role() == requiredRole;
  }
}
