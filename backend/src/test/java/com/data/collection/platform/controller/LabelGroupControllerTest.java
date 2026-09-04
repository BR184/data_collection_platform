package com.data.collection.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.GlobalExceptionHandler;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRulePreviewResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupMemberResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.labelgroup.LabelDimensionCatalogService;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCandidateService;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCatalogService;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleEvaluationService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.data.collection.platform.service.labelgroup.LabelGroupService;
import com.data.collection.platform.service.labelgroup.LabelValueQueryService;
import com.data.collection.platform.service.labelgroup.LabelValueKind;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LabelGroupControllerTest {

  @Mock private LabelValueQueryService labelValueQueryService;
  @Mock private LabelGroupService labelGroupService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private LabelGroupDynamicRuleCandidateService dynamicRuleCandidateService;
  @Mock private LabelGroupDynamicRuleEvaluationService dynamicRuleEvaluationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new LabelGroupController(
                    new LabelDimensionCatalogService(),
                    labelValueQueryService,
                    labelGroupService,
                    labelGroupExpansionService,
                    new LabelGroupDynamicRuleCatalogService(),
                    dynamicRuleCandidateService,
                    dynamicRuleEvaluationService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void shouldReturnDimensions() throws Exception {
    mockMvc.perform(get("/api/label-groups/dimensions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].key").value("module"))
        .andExpect(jsonPath("$.data[0].name").value("模块"))
        .andExpect(jsonPath("$.data[0].valueKind").value("STRING_LITERAL"));
  }

  @Test
  void shouldReturnDimensionValuesWithExplicitScopeParameters() throws Exception {
    when(labelValueQueryService.listValues(eq("module"), eq("review-data-home"), eq("cc"), eq("草"), eq(1), eq(20)))
        .thenReturn(
            new LabelValuePageResponse(
                List.of(new LabelValueResponse("草图", "草图", LabelValueKind.STRING_LITERAL, "FACT", 12)),
                1,
                1,
                20));

    mockMvc.perform(
            get("/api/label-groups/dimensions/module/values")
                .param("pageKey", "review-data-home")
                .param("sourceInstanceId", "cc")
                .param("keyword", "草")
                .param("page", "1")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items[0].value").value("草图"))
        .andExpect(jsonPath("$.data.items[0].label").value("草图"))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void shouldReturnCompatiblePages() throws Exception {
    mockMvc.perform(get("/api/label-groups/dimensions/person/compatible-pages"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].pageKey").value("review-data-home"))
        .andExpect(jsonPath("$.data[0].pageName").value("评审数据管理"))
        .andExpect(jsonPath("$.data[1].pageKey").value("question-metrics-issue-search"))
        .andExpect(jsonPath("$.data[2].pageKey").value("customer-issues-cc-product-issues"));
  }

  @Test
  void shouldReturnDynamicRuleSourcesForDslForm() throws Exception {
    mockMvc.perform(get("/api/label-groups/dynamic-rule-sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].key").value("review_records"))
        .andExpect(jsonPath("$.data[0].name").value("评审记录"))
        .andExpect(jsonPath("$.data[0].fields[0].key").value("id"))
        .andExpect(jsonPath("$.data[0].fields[0].valueType").value("NUMBER"));
  }

  @Test
  void shouldReturnDynamicRuleRelationsForDslForm() throws Exception {
    mockMvc.perform(get("/api/label-groups/dynamic-rule-relations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].name").value("评审记录-问题项记录ID"))
        .andExpect(jsonPath("$.data[0].leftSourceKey").value("review_records"))
        .andExpect(jsonPath("$.data[0].rightSourceKey").value("review_problem_items"));
  }

  @Test
  void shouldReturnDynamicRuleFieldCandidates() throws Exception {
    when(dynamicRuleCandidateService.listCandidates(eq("issue_fact"), eq("moduleName"), eq("草"), eq(1), eq(50)))
        .thenReturn(
            new LabelValuePageResponse(
                List.of(new LabelValueResponse("草图", "草图", LabelValueKind.STRING_LITERAL, "FACT", 12)),
                1,
                1,
                50));

    mockMvc.perform(
            get("/api/label-groups/dynamic-rule-sources/issue_fact/fields/moduleName/candidates")
                .param("keyword", "草")
                .param("page", "1")
                .param("size", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.items[0].value").value("草图"))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  void shouldPreviewDynamicRuleMembers() throws Exception {
    when(dynamicRuleEvaluationService.preview(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new LabelGroupDynamicRulePreviewResponse(
                "STRING",
                "SUCCESS",
                "已计算出 1 个成员",
                List.of(new LabelGroupMemberResponse(null, "张三", "张三", true, 0))));

    mockMvc.perform(
            post("/api/label-groups/dynamic-rule-preview")
                .contentType("application/json")
                .content(
                    """
                    {
                      "ruleConfig": {
                        "rootSourceKey": "issue_fact",
                        "output": {
                          "sourceKey": "issue_fact",
                          "fieldKey": "assigneeName",
                          "valueType": "STRING",
                          "labelFieldKey": "assigneeName",
                          "normalizer": "TRIM"
                        },
                        "filters": [],
                        "relations": [],
                        "groupBy": []
                      }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.members[0].value").value("张三"));
  }

  @Test
  void shouldReturnChineseErrorForUnknownDimension() throws Exception {
    when(labelValueQueryService.listValues(eq("missing"), eq(null), eq(null), eq(null), eq(1), eq(20)))
        .thenThrow(new BizException("标签维度不存在：missing"));

    mockMvc.perform(get("/api/label-groups/dimensions/missing/values"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.message").value("标签维度不存在：missing"));
  }

  @Test
  void shouldListGroups() throws Exception {
    when(labelGroupService.list(eq("STRING"), eq("核心"), eq(true)))
        .thenReturn(List.of(groupResponse()));

    mockMvc.perform(
            get("/api/label-groups")
                .param("valueType", "STRING")
                .param("keyword", "核心")
                .param("enabled", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].name").value("核心人员"))
        .andExpect(jsonPath("$.data[0].valueType").value("STRING"))
        .andExpect(jsonPath("$.data[0].memberCount").value(2));
  }

  @Test
  void shouldNotResolveDimensionKeyWhenListingGroups() throws Exception {
    when(labelGroupService.list(isNull(), isNull(), isNull())).thenReturn(List.of(groupResponse()));

    mockMvc.perform(get("/api/label-groups").param("dimensionKey", "module"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void shouldCreateGroup() throws Exception {
    when(labelGroupService.create(org.mockito.ArgumentMatchers.any())).thenReturn(groupResponse());

    mockMvc.perform(
            post("/api/label-groups")
                .contentType("application/json")
                .content(
                    """
                    {
                      "name": "核心人员",
                      "groupType": "STATIC",
                      "members": [
                        {"value": "张三", "label": "张三"}
                      ]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.name").value("核心人员"));
  }

  @Test
  void shouldExpandGroup() throws Exception {
    when(labelGroupExpansionService.expand(
            eq(1L),
            eq("STRING"),
            eq("closure_status"),
            eq("customer-issues-cc-product-issues"),
            isNull()))
        .thenReturn(
            new LabelGroupExpansionResponse(
                1L, "闭环状态", "STRING", List.of("需求如此", "设计如此"), List.of()));

    mockMvc.perform(
            post("/api/label-groups/1/expand")
                .param("valueType", "STRING")
                .param("fieldKey", "closure_status")
                .param("pageKey", "customer-issues-cc-product-issues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.values[0]").value("需求如此"))
        .andExpect(jsonPath("$.data.values[1]").value("设计如此"));
  }

  @Test
  void shouldNotResolveDimensionKeyWhenExpandingGroup() throws Exception {
    when(labelGroupExpansionService.expand(eq(1L), isNull(), eq("moduleName"), isNull(), isNull()))
        .thenReturn(new LabelGroupExpansionResponse(1L, "核心模块", "STRING", List.of("草图"), List.of()));

    mockMvc.perform(post("/api/label-groups/1/expand").param("dimensionKey", "module").param("fieldKey", "moduleName"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.values[0]").value("草图"));
  }

  @Test
  void shouldDeleteGroup() throws Exception {
    doNothing().when(labelGroupService).delete(1L);

    mockMvc.perform(delete("/api/label-groups/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  private LabelGroupResponse groupResponse() {
    return new LabelGroupResponse(
        1L,
        "核心人员",
        "STRING",
        "STATIC",
        "SAME_TYPE",
        null,
        "常用人员",
        true,
        2,
        List.of(new LabelGroupMemberResponse(1L, "张三", "张三", true, 0)),
        List.of(),
        null,
        List.of(new LabelGroupMemberResponse(null, "张三", "张三", true, 0)),
        false,
        "system",
        OffsetDateTime.parse("2026-06-10T10:00:00+08:00"),
        "system",
        OffsetDateTime.parse("2026-06-10T10:00:00+08:00"));
  }
}
