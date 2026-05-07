package com.data.collection.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.AuthUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProtectedController())
            .addInterceptors(new PlatformAuthorizationInterceptor(new ObjectMapper()))
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
        new AuthUserResponse("approval", "审批用户", AuthRole.APPROVAL, true));

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
        new AuthUserResponse("admin", "管理员", AuthRole.ADMIN, true));

    mockMvc.perform(post("/protected/admin").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @RestController
  static class ProtectedController {
    @PostMapping("/protected/admin")
    @RequireRole(AuthRole.ADMIN)
    TestResponse adminOnly() {
      return new TestResponse(true);
    }
  }

  record TestResponse(boolean success) {}
}
