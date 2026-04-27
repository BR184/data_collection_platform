package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardBreakdownRowResponse;
import com.data.collection.platform.entity.CodeReviewMultiBoardOverviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.service.CodeReviewIllegalRecordQueryRequest;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
import com.data.collection.platform.service.CodeReviewMultiBoardService;
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
class CodeReviewControllerTest {

  @Mock
  private CodeReviewIllegalRecordService codeReviewIllegalRecordService;

  @Mock
  private CodeReviewMultiBoardService codeReviewMultiBoardService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CodeReviewController(
                    codeReviewIllegalRecordService,
                    codeReviewMultiBoardService))
            .build();
  }

  @Test
  void shouldReturnIllegalRecords() throws Exception {
    when(codeReviewIllegalRecordService.listRecords(
            new CodeReviewIllegalRecordQueryRequest(
                325L,
                "repo-a",
                "2026-04-01",
                "2026-04-21",
                "sample",
                "Project X",
                "merge_request",
                "master",
                "Alice",
                "支付模块",
                "缺少模块标签",
                "101",
                "王老师",
                "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"owner\",\"operator\":\"eq\",\"value\":\"王老师\"}]}",
                2,
                10,
                "mergedAt",
                "desc",
                "{\"enabled\":true,\"groups\":[],\"updatedAt\":null}")))
        .thenReturn(new CodeReviewIllegalRecordListResponse(List.of(), 0, 2, 10, "mergedAt", "desc"));

    mockMvc.perform(get("/api/code-review/illegal-records")
            .param("projectId", "325")
            .param("repositoryName", "repo-a")
            .param("mergedAtStart", "2026-04-01")
            .param("mergedAtEnd", "2026-04-21")
            .param("keyword", "sample")
            .param("projectName", "Project X")
            .param("requestType", "merge_request")
            .param("targetBranch", "master")
            .param("mergedBy", "Alice")
            .param("moduleName", "支付模块")
            .param("illegalType", "缺少模块标签")
            .param("mergeRequestIid", "101")
            .param("owner", "王老师")
            .param("filterGroup", "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"owner\",\"operator\":\"eq\",\"value\":\"王老师\"}]}")
            .param("page", "2")
            .param("size", "10")
            .param("sortBy", "mergedAt")
            .param("sortOrder", "desc")
            .param("ruleConfig", "{\"enabled\":true,\"groups\":[],\"updatedAt\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.page").value(2))
        .andExpect(jsonPath("$.data.size").value(10));
  }

  @Test
  void shouldReturnIllegalRecordFilterOptions() throws Exception {
    when(codeReviewIllegalRecordService.getFilterOptions(null))
        .thenReturn(new CodeReviewIllegalRecordFilterOptionsResponse(
            List.of(new OptionItemResponse("合并请求", "merge_request")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()));

    mockMvc.perform(get("/api/code-review/illegal-records/filter-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.requestTypes[0].value").value("merge_request"));
  }

  @Test
  void shouldReturnIllegalRecordRuleExplanation() throws Exception {
    when(codeReviewIllegalRecordService.getRuleExplanation())
        .thenReturn(new StatisticBoardRuleExplanationResponse(
            "code-review-illegal-records",
            true,
            "代码走查非法记录规则说明",
            "code-review-illegal-records@2026-04-08-v3",
            "仅统计已接入本地镜像的 MR 相关数据。",
            "非法记录规则说明。",
            List.of(new StatisticRuleFlowStep(
                "source-load",
                "加载合并请求",
                "读取本地镜像中的 MR 数据。",
                10,
                10,
                List.of(new StatisticRuleFlowStepSample("#1", "示例")))),
            List.of(new StatisticRuleMetricDefinition(
                "illegalTypes",
                "非法类型",
                "根据缺失字段判定非法类型。",
                "非法类型 = 缺少模块标签/责任人/指标",
                "只读说明")),
            null));

    mockMvc.perform(get("/api/code-review/illegal-records/rule-explanation"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.boardKey").value("code-review-illegal-records"))
        .andExpect(jsonPath("$.data.supported").value(true))
        .andExpect(jsonPath("$.data.flowSteps[0].key").value("source-load"))
        .andExpect(jsonPath("$.data.metricDefinitions[0].key").value("illegalTypes"));
  }

  @Test
  void shouldPreviewIllegalRecordRuleConfig() throws Exception {
    when(codeReviewIllegalRecordService.previewRuleConfig(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new CodeReviewRulePreviewResponse(
            10,
            4,
            -6,
            40.0,
            List.of(new CodeReviewRulePreviewSample(
                1L,
                101,
                "示例项目",
                "支付模块",
                "王老师",
                "master",
                "示例合并请求内容",
                List.of("满足：模块名称为空")))));

    mockMvc.perform(post("/api/code-review/illegal-records/rule-config/preview")
            .contentType("application/json")
            .content("""
                {
                  "keyword": "",
                  "ruleConfig": {
                    "enabled": true,
                    "groups": [
                      {
                        "id": "g1",
                        "matchMode": "all",
                        "conditions": [
                          {
                            "id": "c1",
                            "fieldKey": "moduleName",
                            "operator": "isEmpty",
                            "value": ""
                          }
                        ]
                      }
                    ],
                    "updatedAt": null
                  }
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.baseTotal").value(10))
        .andExpect(jsonPath("$.data.filteredTotal").value(4))
        .andExpect(jsonPath("$.data.samples[0].mergeRequestIid").value(101));
  }

  @Test
  void shouldReturnRealtimeStatusAndRefreshMessage() throws Exception {
    RealtimeWorkspaceStatusResponse status =
        new RealtimeWorkspaceStatusResponse(
            "code-review-illegal-records",
            true,
            "ready",
            "ok",
            false,
            LocalDateTime.of(2026, 4, 24, 9, 0),
            null,
            null);
    when(codeReviewIllegalRecordService.getRealtimeStatus()).thenReturn(status);
    when(codeReviewIllegalRecordService.requestRealtimeRefresh()).thenReturn(status);

    mockMvc.perform(get("/api/code-review/illegal-records/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.workspaceKey").value("code-review-illegal-records"));

    mockMvc.perform(post("/api/code-review/illegal-records/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("已开始刷新最新数据"))
        .andExpect(jsonPath("$.data.status").value("ready"));
  }

  @Test
  void shouldReturnMultiBoardSourceOptionsAndOverview() throws Exception {
    when(codeReviewMultiBoardService.listSourceOptions())
        .thenReturn(List.of(
            new OptionItemResponse("CC", "cc"),
            new OptionItemResponse("DGM", "dgm")));
    when(codeReviewMultiBoardService.getOverview("dgm"))
        .thenReturn(
            new CodeReviewMultiBoardOverviewResponse(
                "dgm",
                "DGM",
                6,
                4,
                2,
                21.35,
                9,
                18.5,
                64.2,
                List.of(new CodeReviewMultiBoardBreakdownRowResponse("支付中心", "支付中心", 3, 2, 20.0, 4, 16.0, 52.0)),
                List.of(new CodeReviewMultiBoardBreakdownRowResponse("张三", "张三", 2, 2, 22.5, 1, 14.0, 48.0))));

    mockMvc.perform(get("/api/code-review/multi-board/source-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].value").value("cc"))
        .andExpect(jsonPath("$.data[1].value").value("dgm"));

    mockMvc.perform(get("/api/code-review/multi-board/overview").param("source", "dgm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source").value("dgm"))
        .andExpect(jsonPath("$.data.sourceLabel").value("DGM"))
        .andExpect(jsonPath("$.data.moduleRows[0].rowLabel").value("支付中心"));
  }
}
