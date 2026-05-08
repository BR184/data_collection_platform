package com.data.collection.platform.config;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class PlatformSecurityConfiguration {
  @Bean
  public SecurityFilterChain platformSecurityFilterChain(
      HttpSecurity http,
      PlatformAuthProperties authProperties,
      AuthenticationEntryPoint authenticationEntryPoint,
      AccessDeniedHandler accessDeniedHandler) throws Exception {
    if (authProperties.isCsrfEnabled()) {
      http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
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
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
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
