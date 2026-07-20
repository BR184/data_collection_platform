package com.data.collection.platform.security;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.config.ExternalApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates only the versioned external API with deployment-managed bearer tokens. */
public class ExternalApiAuthenticationFilter extends OncePerRequestFilter {
  private static final String EXTERNAL_API_PREFIX = "/api/external/";
  private static final String BEARER_PREFIX = "Bearer ";

  private final ExternalApiProperties properties;
  private final ObjectMapper objectMapper;

  public ExternalApiAuthenticationFilter(ExternalApiProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith(EXTERNAL_API_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!properties.isEnabled()) {
      writeFailure(response, HttpServletResponse.SC_NOT_FOUND, ResultCode.NOT_FOUND, "请求资源不存在");
      return;
    }
    String token = bearerToken(request.getHeader("Authorization"));
    ExternalApiClientPrincipal client = findClient(token);
    if (client == null) {
      writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, ResultCode.UNAUTHORIZED, "外部接口认证失败");
      return;
    }
    SecurityContextHolder.getContext().setAuthentication(new ExternalApiAuthenticationToken(client));
    filterChain.doFilter(request, response);
  }

  private ExternalApiClientPrincipal findClient(String token) {
    if (!StringUtils.hasText(token)) {
      return null;
    }
    String tokenHash = sha256Hex(token);
    return properties.getClients().stream()
        .filter(client -> StringUtils.hasText(client.getClientId()))
        .filter(client -> secureEquals(tokenHash, normalizeHash(client.getTokenSha256())))
        .findFirst()
        .map(client -> new ExternalApiClientPrincipal(
            client.getClientId().trim(),
            client.getAllowedDatasets().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim())
                .collect(Collectors.toUnmodifiableSet())))
        .orElse(null);
  }

  private String bearerToken(String header) {
    if (!StringUtils.hasText(header) || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return null;
    }
    String token = header.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }

  private String normalizeHash(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
  }

  private boolean secureEquals(String left, String right) {
    if (left.isEmpty() || right.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private String sha256Hex(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 不支持 SHA-256", exception);
    }
  }

  private void writeFailure(HttpServletResponse response, int status, ResultCode code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
  }
}
