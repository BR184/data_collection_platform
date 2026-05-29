package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class PlatformSessionAuthenticationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      HttpSession session = request.getSession(false);
      Object user = session == null ? null : session.getAttribute(AuthSessionSupport.SESSION_USER_KEY);
      if (user instanceof AuthUserResponse authUser && authUser.authenticated()) {
        SecurityContextHolder.getContext().setAuthentication(new PlatformAuthenticationToken(authUser));
      }
    }
    filterChain.doFilter(request, response);
  }
}
