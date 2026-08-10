package com.data.collection.platform.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class LdapPlatformClient {
  private final RestClient restClient;

  public LdapPlatformClient(com.data.collection.platform.config.PlatformAuthProperties properties) {
    Duration connectTimeout = positiveTimeout(
        properties.getLdap().getConnectTimeoutMs(), "LDAP 连接超时");
    Duration readTimeout = positiveTimeout(
        properties.getLdap().getReadTimeoutMs(), "LDAP 读取超时");
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder()
        .baseUrl(trimBaseUrl(properties.getLdap().getBaseUrl()))
        .requestFactory(requestFactory)
        .build();
  }

  public LdapLoginData login(String loginId, String password) {
    try {
      LdapApiResponse<LdapLoginData> response = restClient.post()
          .uri("/api/v1/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new LdapLoginRequest(loginId, password))
          .retrieve()
          .body(new org.springframework.core.ParameterizedTypeReference<>() {});
      return requireSuccess(response, "LDAP 登录失败");
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().value() == 401) {
        throw new LdapInvalidCredentialsException();
      }
      throw new LdapPlatformClientException("LDAP 登录服务响应异常", exception);
    } catch (RestClientException exception) {
      throw new LdapPlatformClientException("LDAP 登录服务不可用", exception);
    }
  }

  public LdapUserData currentUser(String accessToken) {
    LdapApiResponse<LdapUserData> response = restClient.get()
        .uri("/api/v1/auth/me")
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(new org.springframework.core.ParameterizedTypeReference<>() {});
    return requireSuccess(response, "LDAP 用户信息读取失败");
  }

  public List<LdapRoleData> roles(String accessToken) {
    LdapApiResponse<List<LdapRoleData>> response = restClient.get()
        .uri("/api/v1/roles")
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(new org.springframework.core.ParameterizedTypeReference<>() {});
    return requireSuccess(response, "LDAP 角色信息读取失败");
  }

  public List<LdapUserData> users(String accessToken) {
    LdapApiResponse<List<LdapUserData>> response = restClient.get()
        .uri("/api/v1/users")
        .header("Authorization", "Bearer " + accessToken)
        .retrieve()
        .body(new org.springframework.core.ParameterizedTypeReference<>() {});
    return requireSuccess(response, "LDAP 用户全量读取失败");
  }

  private <T> T requireSuccess(LdapApiResponse<T> response, String fallbackMessage) {
    if (response == null || !response.success()) {
      throw new LdapPlatformClientException(response == null || response.message() == null
          ? fallbackMessage
          : response.message());
    }
    return response.data();
  }

  private String trimBaseUrl(String value) {
    String normalized = value == null || value.isBlank() ? "http://127.0.0.1:28081" : value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private Duration positiveTimeout(int timeoutMs, String settingName) {
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException(settingName + "必须大于 0 毫秒");
    }
    return Duration.ofMillis(timeoutMs);
  }

  public record LdapApiResponse<T>(boolean success, String code, String message, T data) {}

  public record LdapLoginRequest(String loginId, String password) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LdapLoginData(Long id, String userId, Set<String> roleCodes, String accessToken, Instant expiresAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LdapUserData(
      Long id,
      String userId,
      String realName,
      String email,
      String intranetEmail,
      String mobile,
      String employeeNo,
      String deptName,
      String deptCode,
      String jobTitle,
      String directLeaderRaw,
      String leaderRef,
      String accountStatus,
      Integer status,
      String employmentStatus,
      String ldapDn,
      Set<String> roleCodes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LdapRoleData(
      Long id,
      String roleCode,
      String roleName,
      Integer permissionLevel,
      Integer builtIn,
      Integer status,
      String remark) {}

  public static class LdapPlatformClientException extends RuntimeException {
    public LdapPlatformClientException(String message) {
      super(message);
    }

    public LdapPlatformClientException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class LdapInvalidCredentialsException extends RuntimeException {
    public LdapInvalidCredentialsException() {
      super("LDAP 拒绝登录凭据");
    }
  }
}
