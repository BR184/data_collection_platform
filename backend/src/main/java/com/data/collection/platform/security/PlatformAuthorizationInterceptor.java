package com.data.collection.platform.security;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.PagePermissionKeyResolver;
import com.data.collection.platform.service.PlatformPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class PlatformAuthorizationInterceptor implements HandlerInterceptor {
  private final ObjectMapper objectMapper;
  private final PlatformPermissionService permissionService;
  private final PagePermissionKeyResolver pagePermissionKeyResolver;

  public PlatformAuthorizationInterceptor(
      ObjectMapper objectMapper,
      PlatformPermissionService permissionService,
      PagePermissionKeyResolver pagePermissionKeyResolver) {
    this.objectMapper = objectMapper;
    this.permissionService = permissionService;
    this.pagePermissionKeyResolver = pagePermissionKeyResolver;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    RequirePermission requirement = resolveRequirement(handlerMethod);
    RequirePagePermission pageRequirement = resolvePageRequirement(handlerMethod);
    if (requirement == null && pageRequirement == null) {
      return true;
    }
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    if (!user.authenticated()) {
      writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED, "请先登录");
      return false;
    }
    java.util.Set<String> permissions = permissionService.permissionsForRoles(user.roleCodes());
    boolean allowed = requirement == null
        ? permissions.contains(resolvePagePermission(pageRequirement, request))
        : requirement.requireAll()
            ? java.util.Arrays.stream(requirement.value()).allMatch(permissions::contains)
            : java.util.Arrays.stream(requirement.value()).anyMatch(permissions::contains);
    if (!allowed) {
      writeFailure(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN, "当前账号无权执行该操作");
      return false;
    }
    return true;
  }

  private RequirePermission resolveRequirement(HandlerMethod handlerMethod) {
    Method method = handlerMethod.getMethod();
    RequirePermission methodAnnotation = method.getAnnotation(RequirePermission.class);
    if (methodAnnotation != null) {
      return methodAnnotation;
    }
    return handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
  }

  private RequirePagePermission resolvePageRequirement(HandlerMethod handlerMethod) {
    return handlerMethod.getMethod().getAnnotation(RequirePagePermission.class);
  }

  @SuppressWarnings("unchecked")
  private String resolvePagePermission(
      RequirePagePermission requirement, HttpServletRequest request) {
    Map<String, String> variables = (Map<String, String>) request.getAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (variables == null) {
      throw new IllegalStateException("动态页面权限缺少路由变量");
    }
    return switch (requirement.resource()) {
      case STATISTIC_BOARD -> resolveBoardPermission(requirement.action(), variables.get("boardKey"));
      case ANALYTICS_DASHBOARD -> resolveDashboardPermission(
          requirement.action(), variables.get("dashboardKey"));
    };
  }

  private String resolveBoardPermission(RequirePagePermission.Action action, String boardKey) {
    return switch (action) {
      case VIEW -> pagePermissionKeyResolver.boardView(boardKey);
      case EXPORT -> pagePermissionKeyResolver.boardExport(boardKey);
      case ISSUE_EXPORT -> pagePermissionKeyResolver.boardIssueExport(boardKey);
    };
  }

  private String resolveDashboardPermission(
      RequirePagePermission.Action action, String dashboardKey) {
    return switch (action) {
      case VIEW -> pagePermissionKeyResolver.dashboardView(dashboardKey);
      case EXPORT -> pagePermissionKeyResolver.dashboardExport(dashboardKey);
      case ISSUE_EXPORT -> throw new IllegalArgumentException("分析看板不支持议题导出权限");
    };
  }

  private void writeFailure(
      HttpServletResponse response,
      int status,
      ResultCode resultCode,
      String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(resultCode, message));
  }
}
