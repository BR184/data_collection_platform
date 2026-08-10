package com.data.collection.platform.security;

/**
 * 表示外部认证服务或登录必要的本地身份镜像暂时不可用。
 *
 * <p>该异常与凭据无效严格区分，调用方应返回可重试的服务不可用响应，不能误报为密码错误。</p>
 */
public class AuthenticationServiceUnavailableException extends RuntimeException {
  public AuthenticationServiceUnavailableException(String message) {
    super(message);
  }

  public AuthenticationServiceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
