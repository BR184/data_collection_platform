package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.config.PlatformAuthProperties;
import com.data.collection.platform.entity.AuthLoginRequest;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private static final String SESSION_USER_KEY = "platform.auth.user";

  private final PlatformAuthProperties properties;

  public AuthController(PlatformAuthProperties properties) {
    this.properties = properties;
  }

  @GetMapping("/current")
  public ApiResponse<AuthUserResponse> current(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return ApiResponse.success(AuthUserResponse.guest());
    }
    Object user = session.getAttribute(SESSION_USER_KEY);
    if (user instanceof AuthUserResponse authUser) {
      return ApiResponse.success(authUser);
    }
    return ApiResponse.success(AuthUserResponse.guest());
  }

  @PostMapping("/login")
  public ApiResponse<AuthUserResponse> login(
      @Valid @RequestBody AuthLoginRequest request,
      HttpSession session
  ) {
    AuthUserResponse user = authenticate(request);
    if (user == null) {
      return ApiResponse.fail(ResultCode.BAD_REQUEST, "用户名或密码错误");
    }
    session.setAttribute(SESSION_USER_KEY, user);
    return ApiResponse.success("登录成功", user);
  }

  @PostMapping("/logout")
  public ApiResponse<AuthUserResponse> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return ApiResponse.success("已退出登录", AuthUserResponse.guest());
  }

  private AuthUserResponse authenticate(AuthLoginRequest request) {
    String username = request.username().trim();
    String password = request.password();
    if (username.equals(properties.getAdminUsername()) && password.equals(properties.getAdminPassword())) {
      return new AuthUserResponse(username, "管理员", AuthRole.ADMIN, true);
    }
    if (username.equals(properties.getApprovalUsername()) && password.equals(properties.getApprovalPassword())) {
      return new AuthUserResponse(username, "审批用户", AuthRole.APPROVAL, true);
    }
    return null;
  }
}
