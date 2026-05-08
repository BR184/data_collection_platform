package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.AuthLoginRequest;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformAuthenticationProvider;
import com.data.collection.platform.security.PlatformAuthenticationToken;
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

  public AuthController(PlatformAuthenticationProvider authenticationProvider) {
    this.authenticationProvider = authenticationProvider;
  }

  @GetMapping("/current")
  public ApiResponse<AuthUserResponse> current(HttpServletRequest request) {
    return ApiResponse.success(AuthSessionSupport.currentUser(request));
  }

  @PostMapping("/login")
  public ApiResponse<AuthUserResponse> login(
      @Valid @RequestBody AuthLoginRequest request,
      HttpSession session
  ) {
    AuthUserResponse user = authenticationProvider.authenticate(request.username(), request.password());
    if (user == null) {
      return ApiResponse.fail(ResultCode.BAD_REQUEST, "用户名或密码错误");
    }
    session.setAttribute(AuthSessionSupport.SESSION_USER_KEY, user);
    SecurityContextHolder.getContext().setAuthentication(new PlatformAuthenticationToken(user));
    return ApiResponse.success("登录成功", user);
  }

  @PostMapping("/logout")
  public ApiResponse<AuthUserResponse> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    return ApiResponse.success("已退出登录", AuthUserResponse.guest());
  }
}
