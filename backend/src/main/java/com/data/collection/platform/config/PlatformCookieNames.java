package com.data.collection.platform.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 同一平台实例的 Session 与 CSRF Cookie 命名空间。 */
public record PlatformCookieNames(
    String sessionCookieName,
    String csrfCookieName) {
  private static final int NAMESPACE_HEX_LENGTH = 24;

  /**
   * 从稳定实例身份生成不泄露部署名称的 Cookie 名。
   *
   * @param instanceId 同一浏览器可访问范围内稳定且唯一的平台实例身份
   * @return 该实例唯一且可跨升级复用的 Session 与 CSRF Cookie 名
   */
  public static PlatformCookieNames forInstance(String instanceId) {
    if (!StringUtils.hasText(instanceId)) {
      throw new IllegalArgumentException("平台实例身份不能为空");
    }
    String canonicalId = instanceId.strip().toLowerCase(Locale.ROOT);
    String namespace = sha256Hex(canonicalId).substring(0, NAMESPACE_HEX_LENGTH).toUpperCase(Locale.ROOT);
    return new PlatformCookieNames(
        "QAFLEX_SESSION_" + namespace,
        "QAFLEX_XSRF_" + namespace);
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
    }
  }
}
