package com.data.collection.platform.config;

import com.data.collection.platform.security.PlatformAuditInterceptor;
import com.data.collection.platform.security.PlatformAuthorizationInterceptor;
import com.data.collection.platform.service.OperationAuditService;
import com.data.collection.platform.service.PagePermissionKeyResolver;
import com.data.collection.platform.service.PlatformPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PlatformWebConfiguration implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;
  private final OperationAuditService operationAuditService;
  private final PlatformPermissionService permissionService;
  private final PagePermissionKeyResolver pagePermissionKeyResolver;

  public PlatformWebConfiguration(
      ObjectMapper objectMapper,
      OperationAuditService operationAuditService,
      PlatformPermissionService permissionService,
      PagePermissionKeyResolver pagePermissionKeyResolver) {
    this.objectMapper = objectMapper;
    this.operationAuditService = operationAuditService;
    this.permissionService = permissionService;
    this.pagePermissionKeyResolver = pagePermissionKeyResolver;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(
        new PlatformAuthorizationInterceptor(objectMapper, permissionService, pagePermissionKeyResolver));
    registry.addInterceptor(new PlatformAuditInterceptor(operationAuditService)).addPathPatterns("/api/**");
  }
}
