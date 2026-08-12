package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig
@ContextConfiguration(
    classes = {
      PlatformSecurityConfiguration.class,
      PlatformCookieConfiguration.class,
      PlatformSecurityConfigurationTest.SecurityTestConfiguration.class,
      PlatformSecurityConfigurationTest.SecurityProbeController.class
    })
class PlatformSecurityConfigurationTest {
  @Autowired
  private WebApplicationContext context;
  @Autowired
  private ExternalApiProperties externalApiProperties;
  @Autowired
  private PlatformCookieNames platformCookieNames;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    externalApiProperties.setEnabled(false);
    externalApiProperties.setClients(List.of());
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldKeepExternalBearerRequestsStatelessAndSeparateFromBrowserCsrf() throws Exception {
    ExternalApiProperties.Client client = new ExternalApiProperties.Client();
    client.setClientId("sample-client");
    client.setTokenSha256(sha256Hex("external-test-token"));
    client.setAllowedDatasets(List.of("sample-dataset"));
    externalApiProperties.setEnabled(true);
    externalApiProperties.setClients(List.of(client));

    mockMvc.perform(get("/api/external/v1/datasets/security-probe")
            .header("Authorization", "Bearer external-test-token"))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist(platformCookieNames.csrfCookieName()));

    mockMvc.perform(get("/api/auth/csrf-probe"))
        .andExpect(status().isOk())
        .andExpect(cookie().exists(platformCookieNames.csrfCookieName()))
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
        .andExpect(header().exists("X-XSRF-TOKEN"));
  }

  @Test
  void shouldAllowSystemHookWithoutSessionOrCsrfToken() throws Exception {
    mockMvc.perform(post("/api/gitlab-sync/system-hook")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true));
  }

  @Test
  void shouldAllowCodeReviewIllegalRecordRefreshWithoutSessionOrCsrfToken() throws Exception {
    mockMvc.perform(post("/api/code-review/illegal-records/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true));

    mockMvc.perform(post("/api/code-review/illegal-records/refresh-one")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"cc\",\"projectId\":9,\"mergeRequestIid\":24515}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true));
  }

  @Test
  void shouldAcceptRawCookieCsrfTokenForAuthenticatedMutatingRequests() throws Exception {
    MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf-probe"))
        .andExpect(status().isOk())
        .andReturn();
    Cookie csrfCookie = csrfResult.getResponse().getCookie(platformCookieNames.csrfCookieName());
    assertThat(csrfCookie).isNotNull();
    assertThat(csrfCookie.isHttpOnly()).isTrue();
    assertThat(csrfResult.getResponse().getHeader("X-XSRF-TOKEN"))
        .isEqualTo(csrfCookie.getValue());

    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        AuthSessionSupport.SESSION_USER_KEY,
        new AuthUserResponse(
            "admin",
            "管理员",
            Set.of("SUPER_ADMIN"),
            List.of("超级管理员"),
            Set.of("system.permission.manage"),
            true));

    mockMvc.perform(post("/api/protected-post")
            .session(session)
            .cookie(csrfCookie)
            .header("X-XSRF-TOKEN", csrfCookie.getValue())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.saved").value(true));
  }

  @Test
  void shouldRejectReviewProblemItemDeleteWithoutSessionEvenWhenCsrfTokenIsValid() throws Exception {
    MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf-probe"))
        .andExpect(status().isOk())
        .andReturn();
    Cookie csrfCookie = csrfResult.getResponse().getCookie(platformCookieNames.csrfCookieName());
    assertThat(csrfCookie).isNotNull();

    mockMvc.perform(delete("/api/review-data/records/7/problem-items/13")
            .cookie(csrfCookie)
            .header("X-XSRF-TOKEN", csrfCookie.getValue()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  static class SecurityTestConfiguration {
    @Bean
    PlatformAuthProperties platformAuthProperties() {
      PlatformAuthProperties properties = new PlatformAuthProperties();
      properties.setCsrfEnabled(true);
      return properties;
    }

    @Bean
    ExternalApiProperties externalApiProperties() {
      return new ExternalApiProperties();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @RestController
  static class SecurityProbeController {
    @GetMapping("/api/external/v1/datasets/security-probe")
    ApiResponse<Map<String, Object>> externalSecurityProbe() {
      return ApiResponse.success(Map.of("ready", true));
    }

    @GetMapping("/api/auth/csrf-probe")
    ApiResponse<Map<String, Object>> csrfProbe() {
      return ApiResponse.success(Map.of("ready", true));
    }

    @DeleteMapping("/api/review-data/records/{recordId}/problem-items/{itemId}")
    ApiResponse<Map<String, Object>> deleteReviewProblemItem() {
      return ApiResponse.success("删除评审问题成功", Map.of("deleted", true));
    }

    @PostMapping("/api/gitlab-sync/system-hook")
    ApiResponse<Map<String, Object>> systemHook(
        @RequestBody(required = false) Map<String, Object> payload) {
      return ApiResponse.success("GitLab System Hook 已接收", Map.of("accepted", true));
    }

    @PostMapping("/api/code-review/illegal-records/refresh")
    ApiResponse<Map<String, Object>> codeReviewRefresh() {
      return ApiResponse.success("已开始刷新最新数据", Map.of("accepted", true));
    }

    @PostMapping("/api/code-review/illegal-records/refresh-one")
    ApiResponse<Map<String, Object>> codeReviewRefreshOne(
        @RequestBody(required = false) Map<String, Object> payload) {
      return ApiResponse.success("已刷新本条合并请求数据", Map.of("accepted", true));
    }

    @PostMapping("/api/protected-post")
    ApiResponse<Map<String, Object>> protectedPost() {
      return ApiResponse.success(Map.of("saved", true));
    }
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JDK 不支持 SHA-256", exception);
    }
  }
}
