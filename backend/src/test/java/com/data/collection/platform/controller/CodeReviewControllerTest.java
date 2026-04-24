package com.data.collection.platform.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CodeReviewIllegalRecordService;
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

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new CodeReviewController(codeReviewIllegalRecordService)).build();
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
}
