package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.AuthLoginRequest;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformAuthenticationProvider;
import com.data.collection.platform.security.PlatformAuthenticationToken;
import com.data.collection.platform.service.OperationAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final PlatformAuthenticationProvider authenticationProvider;
  private final OperationAuditService operationAuditService;

  public AuthController(
      PlatformAuthenticationProvider authenticationProvider,
      OperationAuditService operationAuditService) {
    this.authenticationProvider = authenticationProvider;
    this.operationAuditService = operationAuditService;
  }

  @GetMapping("/current")
  public ApiResponse<AuthUserResponse> current(HttpServletRequest request) {
    return ApiResponse.success(AuthSessionSupport.currentUser(request));
  }

  @PostMapping("/login")
  public ApiResponse<AuthUserResponse> login(
      @Valid @RequestBody AuthLoginRequest request,
      HttpSession session,
      HttpServletRequest servletRequest
  ) {
    AuthUserResponse user = authenticationProvider.authenticate(request.username(), request.password());
    if (user == null) {
      recordAuthAudit(
          AuthUserResponse.guest(),
          "POST",
          "/api/auth/login",
          servletRequest,
          HttpServletResponseStatus.BAD_REQUEST,
          "LOGIN_FAILED",
          "username=" + safeUsername(request.username()));
      return ApiResponse.fail(ResultCode.BAD_REQUEST, "用户名或密码错误");
    }
    session.setAttribute(AuthSessionSupport.SESSION_USER_KEY, user);
    SecurityContextHolder.getContext().setAuthentication(new PlatformAuthenticationToken(user));
    recordAuthAudit(user, "POST", "/api/auth/login", servletRequest, 200, "", "username=" + user.username());
    return ApiResponse.success("登录成功", user);
  }

  @PostMapping("/logout")
  public ApiResponse<AuthUserResponse> logout(HttpServletRequest request) {
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    recordAuthAudit(user, "POST", "/api/auth/logout", request, 200, "", "username=" + user.username());
    return ApiResponse.success("已退出登录", AuthUserResponse.guest());
  }

  private void recordAuthAudit(
      AuthUserResponse user,
      String method,
      String path,
      HttpServletRequest request,
      int responseStatus,
      String errorMessage,
      String requestSummary) {
    operationAuditService.record(
        user,
        method,
        path,
        request.getRemoteAddr(),
        responseStatus,
        errorMessage,
        requestSummary);
  }

  private String safeUsername(String username) {
    if (username == null || username.isBlank()) {
      return "";
    }
    return username.strip().replaceAll("[\\r\\n\\t]", "");
  }

  private static final class HttpServletResponseStatus {
    private static final int BAD_REQUEST = 400;

    private HttpServletResponseStatus() {}
  }
}
