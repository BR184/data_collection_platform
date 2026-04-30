package com.data.collection.platform.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.config.PlatformAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    PlatformAuthProperties properties = new PlatformAuthProperties();
    properties.setAdminUsername("admin");
    properties.setAdminPassword("secret");
    properties.setApprovalUsername("approval");
    properties.setApprovalPassword("approval-secret");
    mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(properties)).build();
  }

  @Test
  void currentShouldReturnGuestWhenNoSessionExists() throws Exception {
    mockMvc.perform(get("/api/auth/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.role").value("GUEST"))
        .andExpect(jsonPath("$.data.authenticated").value(false));
  }

  @Test
  void loginShouldPersistAdminSession() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "admin",
                  "password": "secret"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.role").value("ADMIN"))
        .andExpect(jsonPath("$.data.authenticated").value(true))
        .andReturn();

    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    mockMvc.perform(get("/api/auth/current").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.role").value("ADMIN"));
  }

  @Test
  void loginShouldSupportApprovalRole() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "approval",
                  "password": "approval-secret"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.role").value("APPROVAL"));
  }

  @Test
  void loginShouldRejectUnknownCredentials() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "admin",
                  "password": "wrong"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("A0400"));
  }
}
