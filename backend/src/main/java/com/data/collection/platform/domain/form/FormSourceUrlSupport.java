package com.data.collection.platform.domain.form;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 表单来源 Web 基址的规范化；只接受已保存来源配置使用的 http/https 基址。 */
public final class FormSourceUrlSupport {

  private FormSourceUrlSupport() {}

  public static String canonicalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("GitLab Web 基址不能为空");
    }
    URI uri;
    try {
      uri = new URI(raw.trim());
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("GitLab Web 基址格式不正确: " + raw, error);
    }
    String scheme = uri.getScheme();
    if (scheme == null
        || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
      throw new IllegalArgumentException("GitLab Web 基址只支持 http 或 https: " + raw);
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("GitLab Web 基址必须包含主机: " + raw);
    }
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("GitLab Web 基址不得包含用户信息、查询或片段: " + raw);
    }
    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = normalizedPort(normalizedScheme, uri.getPort());
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/") && !path.isEmpty()) {
      path = path.substring(0, path.length() - 1);
    }
    return normalizedScheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
  }

  private static int normalizedPort(String scheme, int port) {
    if (port < 0) {
      return -1;
    }
    if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
      return -1;
    }
    return port;
  }
}
