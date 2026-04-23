package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.IntegrationTestDetailResponse;
import com.data.collection.platform.entity.IntegrationTestDetailRowResponse;
import com.data.collection.platform.entity.IntegrationTestPhaseOptionResponse;
import com.data.collection.platform.entity.IntegrationTestProjectOptionResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryRowResponse;
import com.data.collection.platform.service.IntegrationTestFactBuildService;
import com.data.collection.platform.service.IntegrationTestQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class IntegrationTestControllerTest {

  @Mock private IntegrationTestFactBuildService integrationTestFactBuildService;
  @Mock private IntegrationTestQueryService integrationTestQueryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new IntegrationTestController(
                    integrationTestFactBuildService, integrationTestQueryService))
            .build();
  }

  @Test
  void shouldRebuildFacts() throws Exception {
    when(integrationTestFactBuildService.rebuildFacts(false))
        .thenReturn(new FactBuildResponse("integration-test", false, 8, "集成测试事实已按增量构建"));

    mockMvc.perform(post("/api/integration-tests/rebuild"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.scope").value("integration-test"))
        .andExpect(jsonPath("$.data.affectedRows").value(8));
  }

  @Test
  void shouldListProjectOptions() throws Exception {
    when(integrationTestQueryService.listProjectOptions())
        .thenReturn(List.of(new IntegrationTestProjectOptionResponse(325L, "CC_PRODUCT")));

    mockMvc.perform(get("/api/integration-tests/project-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].projectId").value(325))
        .andExpect(jsonPath("$.data[0].projectName").value("CC_PRODUCT"));
  }

  @Test
  void shouldReturnSummaryAndDetails() throws Exception {
    when(integrationTestQueryService.getSummary(325L, "R1集成测试"))
        .thenReturn(
            new IntegrationTestSummaryResponse(
                325L,
                "R1集成测试",
                1,
                2,
                LocalDateTime.of(2026, 4, 23, 21, 30),
                List.of(
                    new IntegrationTestSummaryRowResponse(
                        "草图", 2, 18, 15, 1, 3, 0, 1, new BigDecimal("83.33"), 1))));
    when(integrationTestQueryService.getDetails(325L, "R1集成测试", "草图", 1, 20, "noteUpdatedAt", "desc"))
        .thenReturn(
            new IntegrationTestDetailResponse(
                List.of(
                    new IntegrationTestDetailRowResponse(
                        1001L,
                        88L,
                        "#88",
                        325L,
                        "CC_PRODUCT",
                        "草图命令异常",
                        "草图",
                        "拉伸",
                        "新功能",
                        "张三",
                        10,
                        8,
                        1,
                        2,
                        0,
                        1,
                        new BigDecimal("80.00"),
                        true,
                        "opened",
                        "李四",
                        null,
                        LocalDateTime.of(2026, 4, 23, 20, 15),
                        LocalDateTime.of(2026, 4, 23, 20, 10))),
                1,
                1,
                20,
                "noteUpdatedAt",
                "desc"));

    mockMvc.perform(get("/api/integration-tests/summary").param("projectId", "325").param("testingPhase", "R1集成测试"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.moduleCount").value(1))
        .andExpect(jsonPath("$.data.rows[0].moduleName").value("草图"));

    mockMvc.perform(
            get("/api/integration-tests/details")
                .param("projectId", "325")
                .param("testingPhase", "R1集成测试")
                .param("moduleName", "草图"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records[0].issueIid").value(88))
        .andExpect(jsonPath("$.data.records[0].executor").value("张三"));
  }

  @Test
  void shouldListPhaseOptions() throws Exception {
    when(integrationTestQueryService.listPhaseOptions(325L))
        .thenReturn(List.of(new IntegrationTestPhaseOptionResponse(325L, "CC_PRODUCT", "R1集成测试", 12)));

    mockMvc.perform(get("/api/integration-tests/phase-options").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].testingPhase").value("R1集成测试"))
        .andExpect(jsonPath("$.data[0].recordCount").value(12));
  }
}
