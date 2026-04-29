package com.data.collection.platform.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void shouldReturnBadRequestForBusinessExceptions() throws Exception {
    mockMvc.perform(get("/failure/biz"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("B0001"));
  }

  @Test
  void shouldReturnBadRequestForInvalidArguments() throws Exception {
    mockMvc.perform(get("/failure/bad-request"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("A0400"));
  }

  @Test
  void shouldReturnServerErrorForUnhandledExceptions() throws Exception {
    mockMvc.perform(get("/failure/system"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("C0001"));
  }

  @RestController
  private static class FailingController {
    @GetMapping("/failure/biz")
    void biz() {
      throw new BizException("business failed");
    }

    @GetMapping("/failure/bad-request")
    void badRequest() {
      throw new IllegalArgumentException("invalid input");
    }

    @GetMapping("/failure/system")
    void system() {
      throw new IllegalStateException("boom");
    }
  }
}
