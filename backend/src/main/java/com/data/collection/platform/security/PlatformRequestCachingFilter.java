package com.data.collection.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
public class PlatformRequestCachingFilter extends OncePerRequestFilter {
  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final int REQUEST_CACHE_LIMIT = 8_192;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return !uri.startsWith("/api/")
        || uri.startsWith("/api/auth/")
        || !MUTATING_METHODS.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    if (request instanceof ContentCachingRequestWrapper) {
      filterChain.doFilter(request, response);
      return;
    }
    filterChain.doFilter(new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT), response);
  }
}
