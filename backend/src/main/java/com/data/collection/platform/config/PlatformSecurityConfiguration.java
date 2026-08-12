package com.data.collection.platform.config;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.security.ExternalApiAuthenticationFilter;
import com.data.collection.platform.security.PlatformCsrfCookieFilter;
import com.data.collection.platform.security.PlatformSessionAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class PlatformSecurityConfiguration {
  private static final String SYSTEM_HOOK_PATH = "/api/gitlab-sync/system-hook";
  private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
  private static final String CODE_REVIEW_ILLEGAL_REFRESH_PATH = "/api/code-review/illegal-records/refresh";
  private static final String CODE_REVIEW_ILLEGAL_REFRESH_ONE_PATH = "/api/code-review/illegal-records/refresh-one";
  private static final String[] SYSTEM_SETTINGS_API_PATHS = {
      // 兼容模式-MatchMode：系统设置/数据库兼容模式临时设置 API，删除兼容模式时同步移除白名单。
      "/api/code-review/match-mode-db-settings/**",
      "/api/database-browser/**",
      "/api/gitlab-sync/**"
  };

  @Bean
  @Order(1)
  public SecurityFilterChain externalApiSecurityFilterChain(
      HttpSecurity http,
      ExternalApiProperties externalApiProperties,
      ObjectMapper objectMapper) throws Exception {
    http
        .securityMatcher("/api/external/**")
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ExternalApiAuthenticationFilter(externalApiProperties, objectMapper),
            UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain platformSecurityFilterChain(
      HttpSecurity http,
      PlatformAuthProperties authProperties,
      PlatformCookieNames cookieNames,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler) throws Exception {
    if (authProperties.isCsrfEnabled()) {
      http.csrf(csrf -> csrf
          .csrfTokenRepository(csrfTokenRepository(cookieNames))
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
          .ignoringRequestMatchers(
              SYSTEM_HOOK_PATH,
              CODE_REVIEW_ILLEGAL_REFRESH_PATH,
              CODE_REVIEW_ILLEGAL_REFRESH_ONE_PATH));
    } else {
      http.csrf(AbstractHttpConfigurer::disable);
    }

    http
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(exceptionHandling -> exceptionHandling
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(new PlatformSessionAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new PlatformCsrfCookieFilter(), PlatformSessionAuthenticationFilter.class)
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers(SYSTEM_HOOK_PATH).permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
            .requestMatchers(HttpMethod.HEAD, "/api/**").authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
            .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/**/delete")).authenticated()
            .requestMatchers(HttpMethod.POST, CODE_REVIEW_ILLEGAL_REFRESH_PATH).permitAll()
            .requestMatchers(HttpMethod.POST, CODE_REVIEW_ILLEGAL_REFRESH_ONE_PATH).permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll());
    return http.build();
  }

  private CookieCsrfTokenRepository csrfTokenRepository(PlatformCookieNames cookieNames) {
    CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
    repository.setCookieName(cookieNames.csrfCookieName());
    repository.setHeaderName(CSRF_HEADER_NAME);
    repository.setCookieCustomizer(cookie -> cookie
        .path("/")
        .httpOnly(true)
        .sameSite("Lax"));
    return repository;
  }

  @Bean
  public AuthenticationEntryPoint platformAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return (request, response, authException) -> {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      writeJsonFailure(objectMapper, response, ResultCode.UNAUTHORIZED, "请先登录");
    };
  }

  @Bean
  public AccessDeniedHandler platformAccessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, accessDeniedException) -> {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      writeJsonFailure(objectMapper, response, ResultCode.FORBIDDEN, "当前账号无权执行该操作");
    };
  }

  private static void writeJsonFailure(
      ObjectMapper objectMapper,
      HttpServletResponse response,
      ResultCode resultCode,
      String message) throws java.io.IOException {
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getWriter(), ApiResponse.fail(resultCode, message));
  }
}
