package com.data.collection.platform.config;

import com.data.collection.platform.security.PlatformAuditInterceptor;
import com.data.collection.platform.security.PlatformAuthorizationInterceptor;
import com.data.collection.platform.service.OperationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PlatformWebConfiguration implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;
  private final OperationAuditService operationAuditService;

  public PlatformWebConfiguration(ObjectMapper objectMapper, OperationAuditService operationAuditService) {
    this.objectMapper = objectMapper;
    this.operationAuditService = operationAuditService;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new PlatformAuthorizationInterceptor(objectMapper));
    registry.addInterceptor(new PlatformAuditInterceptor(operationAuditService)).addPathPatterns("/api/**");
  }
}
