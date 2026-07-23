package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFactCountBreakdownResponse;
import com.data.collection.platform.entity.IssueFactDiagnosticsResponse;
import com.data.collection.platform.entity.IssueFactScopeDiagnosticsResponse;
import com.data.collection.platform.entity.IssueSourceReadinessResponse;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.IssueFactDiagnosticsService;
import com.data.collection.platform.service.IssueSourceReadinessService;
import com.data.collection.platform.common.response.ApiResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class FactBuildControllerTest {

  @Mock private FactBuildTaskService factBuildTaskService;
  @Mock private GitlabConfigService configService;
  @Mock private GitlabSyncCommandFacade syncCommandFacade;
  @Mock private IssueFactDiagnosticsService issueFactDiagnosticsService;
  @Mock private IssueSourceReadinessService issueSourceReadinessService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new FactBuildController(
                    factBuildTaskService,
                    configService,
                    syncCommandFacade,
                    issueFactDiagnosticsService,
                    issueSourceReadinessService))
            .build();
  }

  @Test
  void shouldSubmitManualFullRebuildForSelectedConfig() throws Exception {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(2L);
    when(configService.getConfigById(2L)).thenReturn(config);
    when(syncCommandFacade.manualFullFactRebuild(config))
        .thenReturn(
            ApiResponse.success(
                "同步已提交，等待调度器执行。",
                Map.of(
                    "accepted", true,
                    "runId", 42L,
                    "status", "QUEUED",
                    "action", "QUEUED",
                    "message", "同步已提交，等待调度器执行。")));

    mockMvc.perform(post("/api/facts/rebuild").param("configId", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.accepted").value(true))
        .andExpect(jsonPath("$.data.runId").value(42))
        .andExpect(jsonPath("$.data.status").value("QUEUED"));
    verify(configService).getConfigById(2L);
    verify(syncCommandFacade).manualFullFactRebuild(config);
  }

  @Test
  void shouldLoadLatestFactBuildTask() throws Exception {
    when(factBuildTaskService.latest("issue"))
        .thenReturn(
            new FactBuildTaskResponse(
                1L,
                "run-1",
                "issue",
                true,
                "SUCCESS",
                "MANUAL",
                "owner",
                6,
                "done",
                null,
                LocalDateTime.of(2026, 4, 30, 9, 0),
                LocalDateTime.of(2026, 4, 30, 9, 1),
                LocalDateTime.of(2026, 4, 30, 9, 0),
                LocalDateTime.of(2026, 4, 30, 9, 1)));

    mockMvc.perform(get("/api/facts/build-tasks/latest").param("scope", "issue"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.scope").value("issue"))
        .andExpect(jsonPath("$.data.status").value("SUCCESS"))
        .andExpect(jsonPath("$.data.affectedRows").value(6));
  }

  @Test
  void shouldLoadIssueFactDiagnostics() throws Exception {
    when(issueFactDiagnosticsService.getDiagnostics())
        .thenReturn(
            new IssueFactDiagnosticsResponse(
                LocalDateTime.of(2026, 4, 22, 16, 10),
                new IssueFactScopeDiagnosticsResponse("overall", "全部 Issue Fact", 20, 8, 6, 5, 2, 1),
                new IssueFactScopeDiagnosticsResponse("system-test", "系统测试", 6, 4, 0, 3, 1, 0),
                new IssueFactScopeDiagnosticsResponse("customer-issue", "客户问题", 14, 4, 6, 2, 1, 1),
                List.of(new IssueFactCountBreakdownResponse("环境部署问题", "环境部署问题", 3)),
                List.of(new IssueFactCountBreakdownResponse("需求理解偏差", "需求理解偏差", 2)),
                List.of(new IssueFactCountBreakdownResponse("CC_Product", "CC_Product", 14))));

    mockMvc.perform(get("/api/facts/issue-diagnostics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.overall.totalCount").value(20))
        .andExpect(jsonPath("$.data.customerIssue.withMilestoneTitleCount").value(6))
        .andExpect(jsonPath("$.data.customerIssueProjects[0].label").value("CC_Product"));
  }

  @Test
  void shouldLoadIssueSourceReadiness() throws Exception {
    when(issueSourceReadinessService.getReadiness())
        .thenReturn(
            new IssueSourceReadinessResponse(
                LocalDateTime.of(2026, 4, 22, 16, 20),
                2,
                15,
                3,
                7,
                1,
                6,
                4,
                5,
                List.of(new IssueFactCountBreakdownResponse("325", "CC_Product", 6)),
                List.of()));

    mockMvc.perform(get("/api/facts/issue-source-readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.customerProjectIssueCount").value(6))
        .andExpect(jsonPath("$.data.topIssueProjects[0].label").value("CC_Product"));
  }
}
