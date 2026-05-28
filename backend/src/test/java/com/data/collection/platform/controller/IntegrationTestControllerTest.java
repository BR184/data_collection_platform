package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.data.collection.platform.service.IntegrationTestExcelExportService;
import com.data.collection.platform.service.IntegrationTestFactBuildService;
import com.data.collection.platform.service.IntegrationTestQueryService;
import com.data.collection.platform.service.FactBuildOperationGuard;
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
  @Mock private IntegrationTestExcelExportService integrationTestExcelExportService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new IntegrationTestController(
                    integrationTestFactBuildService,
                    integrationTestQueryService,
                    integrationTestExcelExportService,
                    new FactBuildOperationGuard()))
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
  void shouldRebuildFactsForConfigWhenConfigIdIsProvided() throws Exception {
    when(integrationTestFactBuildService.rebuildFacts(false, 2L))
        .thenReturn(new FactBuildResponse("dgm:integration-test", false, 5, "ok"));

    mockMvc
        .perform(post("/api/integration-tests/rebuild").param("configId", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.scope").value("dgm:integration-test"))
        .andExpect(jsonPath("$.data.affectedRows").value(5));
  }

  @Test
  void shouldListProjectOptions() throws Exception {
    when(integrationTestQueryService.listProjectOptions(null))
        .thenReturn(List.of(new IntegrationTestProjectOptionResponse(325L, "CC_PRODUCT")));

    mockMvc.perform(get("/api/integration-tests/project-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].projectId").value(325))
        .andExpect(jsonPath("$.data[0].projectName").value("CC_PRODUCT"));
  }

  @Test
  void shouldReturnSummaryAndDetails() throws Exception {
    when(integrationTestQueryService.getSummary(325L, "R1集成测试", null))
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
    when(integrationTestQueryService.getDetails(
            325L, "R1集成测试", "草图", 1, 20, "noteUpdatedAt", "desc", null))
        .thenReturn(
            new IntegrationTestDetailResponse(
                List.of(
                    new IntegrationTestDetailRowResponse(
                        1001L,
                        88L,
                        "#88",
                        "http://gitlab.example.com/group/project/-/issues/88",
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
                        "PARSED",
                        null,
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
        .andExpect(jsonPath("$.data.records[0].issueLink").value("http://gitlab.example.com/group/project/-/issues/88"))
        .andExpect(jsonPath("$.data.records[0].executor").value("张三"));
  }

  @Test
  void shouldListPhaseOptions() throws Exception {
    when(integrationTestQueryService.listPhaseOptions(325L, null))
        .thenReturn(List.of(new IntegrationTestPhaseOptionResponse(325L, "CC_PRODUCT", "R1集成测试", 12)));

    mockMvc.perform(get("/api/integration-tests/phase-options").param("projectId", "325"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].testingPhase").value("R1集成测试"))
        .andExpect(jsonPath("$.data[0].recordCount").value(12));
  }

  @Test
  void shouldExportDetailsCsv() throws Exception {
    when(integrationTestQueryService.exportDetailsCsv(
            325L, "R1集成测试", "草图", "noteUpdatedAt", "desc", null))
        .thenReturn("\"议题编号\",\"标题\"\n\"#88\",\"草图命令异常\"\n");

    mockMvc
        .perform(
            get("/api/integration-tests/details/export")
                .param("projectId", "325")
                .param("testingPhase", "R1集成测试")
                .param("moduleName", "草图")
                .param("sortField", "noteUpdatedAt")
        .param("sortOrder", "desc"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
        .andExpect(header().string("Content-Disposition", "attachment; filename=\"integration-test-details.csv\""))
        .andExpect(content().string("\"议题编号\",\"标题\"\n\"#88\",\"草图命令异常\"\n"));
  }
  @Test
  void shouldExportModuleFunctionWorkbook() throws Exception {
    when(integrationTestExcelExportService.exportModuleFunctionWorkbook(325L, "CC2025R4集成测试", null))
        .thenReturn(new byte[] {1, 2, 3});

    mockMvc
        .perform(
            get("/api/integration-tests/module-function/export")
                .param("projectId", "325")
                .param("testingPhase", "CC2025R4集成测试"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("filename*=")))
        .andExpect(content().bytes(new byte[] {1, 2, 3}));
  }

  @Test
  void shouldExportComparisonWorkbook() throws Exception {
    when(integrationTestExcelExportService.exportComparisonWorkbook(
            325L, "CC2025R2集成测试", "CC2025R3集成测试", null))
        .thenReturn(new byte[] {4, 5, 6});

    mockMvc
        .perform(
            get("/api/integration-tests/comparison/export")
                .param("projectId", "325")
                .param("basePhase", "CC2025R2集成测试")
                .param("targetPhase", "CC2025R3集成测试"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("filename*=")))
        .andExpect(content().bytes(new byte[] {4, 5, 6}));
  }
}
