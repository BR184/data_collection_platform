package com.data.collection.platform.config;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
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
  private static final String[] SYSTEM_SETTINGS_API_PATHS = {
      // 兼容模式-MatchMode：系统设置/数据库兼容模式临时设置 API，删除兼容模式时同步移除白名单。
      "/api/code-review/match-mode-db-settings/**",
      "/api/database-browser/**",
      "/api/gitlab-sync/**"
  };

  @Bean
  public SecurityFilterChain platformSecurityFilterChain(
      HttpSecurity http,
      PlatformAuthProperties authProperties,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler) throws Exception {
    if (authProperties.isCsrfEnabled()) {
      http.csrf(csrf -> csrf
          .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
          .ignoringRequestMatchers(SYSTEM_HOOK_PATH));
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
            .requestMatchers(HttpMethod.GET, SYSTEM_SETTINGS_API_PATHS).authenticated()
            .requestMatchers(HttpMethod.HEAD, SYSTEM_SETTINGS_API_PATHS).authenticated()
            .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
            .requestMatchers(AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/**/delete")).authenticated()
            .requestMatchers(HttpMethod.POST, "/api/review-data/records").permitAll()
            .requestMatchers(HttpMethod.PUT, "/api/review-data/records/*").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/review-data/records/*/problem-items").permitAll()
            .requestMatchers(HttpMethod.PUT, "/api/review-data/records/*/problem-items/*").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
            .requestMatchers(HttpMethod.HEAD, "/api/**").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll());
    return http.build();
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
