package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

public class PlatformAuditInterceptor implements HandlerInterceptor {
  private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final int MAX_REQUEST_SUMMARY_LENGTH = 4_000;
  private static final Pattern QUERY_SECRET_PATTERN =
      Pattern.compile("(?i)(^|[&;\\s])([^=&;\\s]*(?:password|token|secret|authorization|cookie)[^=&;\\s]*=)[^&;\\s]*");
  private static final Pattern JSON_SECRET_PATTERN =
      Pattern.compile("(?i)(\"[^\"]*(?:password|token|secret|authorization|cookie)[^\"]*\"\\s*:\\s*\")[^\"]*(\")");

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
        ex == null ? "" : sanitize(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage())),
        requestSummary(request));
  }

  private String requestSummary(HttpServletRequest request) {
    List<String> parts = new ArrayList<>();
    if (StringUtils.hasText(request.getQueryString())) {
      parts.add("query=" + sanitize(request.getQueryString()));
    }
    String body = requestBody(request);
    if (StringUtils.hasText(body)) {
      parts.add("body=" + sanitize(body.strip()));
    }
    return truncate(String.join("; ", parts));
  }

  private String requestBody(HttpServletRequest request) {
    if (!(request instanceof ContentCachingRequestWrapper wrapper)) {
      return "";
    }
    byte[] content = wrapper.getContentAsByteArray();
    if (content.length == 0) {
      return "";
    }
    return new String(content, charsetOf(wrapper));
  }

  private Charset charsetOf(HttpServletRequest request) {
    if (!StringUtils.hasText(request.getCharacterEncoding())) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(request.getCharacterEncoding());
    } catch (Exception ignored) {
      return StandardCharsets.UTF_8;
    }
  }

  private String sanitize(String value) {
    String redactedQuery = QUERY_SECRET_PATTERN.matcher(value).replaceAll("$1$2***");
    return JSON_SECRET_PATTERN.matcher(redactedQuery).replaceAll("$1***$2");
  }

  private String truncate(String value) {
    if (value.length() <= MAX_REQUEST_SUMMARY_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_REQUEST_SUMMARY_LENGTH);
  }
}
