package com.data.collection.platform.security;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class PlatformAuthorizationInterceptor implements HandlerInterceptor {
  private final ObjectMapper objectMapper;

  public PlatformAuthorizationInterceptor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      return true;
    }
    RequireRole requireRole = resolveRequireRole(handlerMethod);
    if (requireRole == null) {
      return true;
    }
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    if (!user.authenticated()) {
      writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED, "请先登录");
      return false;
    }
    boolean allowed =
        Arrays.stream(requireRole.value()).anyMatch(role -> AuthSessionSupport.hasRole(user, role));
    if (!allowed) {
      writeFailure(response, HttpServletResponse.SC_FORBIDDEN, ResultCode.FORBIDDEN, "当前账号无权执行该操作");
      return false;
    }
    return true;
  }

  private RequireRole resolveRequireRole(HandlerMethod handlerMethod) {
    Method method = handlerMethod.getMethod();
    RequireRole methodAnnotation = method.getAnnotation(RequireRole.class);
    if (methodAnnotation != null) {
      return methodAnnotation;
    }
    return handlerMethod.getBeanType().getAnnotation(RequireRole.class);
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
