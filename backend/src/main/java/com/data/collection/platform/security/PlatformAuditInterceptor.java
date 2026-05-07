package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

public class PlatformAuditInterceptor implements HandlerInterceptor {
  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final OperationAuditService operationAuditService;

  public PlatformAuditInterceptor(OperationAuditService operationAuditService) {
    this.operationAuditService = operationAuditService;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception ex) {
    if (!MUTATING_METHODS.contains(request.getMethod())) {
      return;
    }
    if (request.getRequestURI().startsWith("/api/auth/")) {
      return;
    }
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    operationAuditService.record(
        user,
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr(),
        response.getStatus(),
        ex == null ? "" : ex.getClass().getSimpleName() + ": " + ex.getMessage());
  }
}
