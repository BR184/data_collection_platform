package com.data.collection.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.PagePermissionKeyResolver;
import com.data.collection.platform.service.PlatformPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class PlatformAuthorizationInterceptorTest {
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    PlatformPermissionService permissionService = mock(PlatformPermissionService.class);
    when(permissionService.permissionsForRoles(any())).thenAnswer(invocation -> {
      Set<String> roles = invocation.getArgument(0);
      return roles.contains("SUPER_ADMIN") ? Set.of("system.permission.manage") : Set.of();
    });
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProtectedController())
            .addInterceptors(new PlatformAuthorizationInterceptor(
                new ObjectMapper(), permissionService, mock(PagePermissionKeyResolver.class)))
            .build();
  }

  @Test
  void shouldRejectAnonymousUserForAdminOperation() throws Exception {
    mockMvc.perform(post("/protected/admin"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("A0301"));
  }

  @Test
  void shouldRejectApprovalUserForAdminOperation() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        AuthSessionSupport.SESSION_USER_KEY,
        new AuthUserResponse(
            "approval", "审批用户", Set.of("NORMAL_USER"), List.of("普通用户"), Set.of(), true));

    mockMvc.perform(post("/protected/admin").session(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("A0303"));
  }

  @Test
  void shouldAllowAdminUserForAdminOperation() throws Exception {
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

    mockMvc.perform(post("/protected/admin").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @RestController
  static class ProtectedController {
    @PostMapping("/protected/admin")
    @RequirePermission("system.permission.manage")
    TestResponse adminOnly() {
      return new TestResponse(true);
    }
  }

  record TestResponse(boolean success) {}
}
