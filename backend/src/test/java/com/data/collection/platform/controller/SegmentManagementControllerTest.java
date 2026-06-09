package com.data.collection.platform.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.service.SegmentComputeService;
import com.data.collection.platform.service.SegmentCostEstimator;
import com.data.collection.platform.service.SegmentFilterPresetService;
import com.data.collection.platform.service.SegmentSchemaCompatibilityChecker;
import com.data.collection.platform.service.SemanticScopeRegistry;
import com.data.collection.platform.service.SemanticTagGroupService;
import com.data.collection.platform.service.TemplateOnlySemanticQueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SegmentManagementControllerTest {
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    SemanticScopeRegistry scopeRegistry = SemanticScopeRegistry.withDefaults();
    SegmentComputeService segmentComputeService =
        new SegmentComputeService(
            scopeRegistry,
            new SegmentCostEstimator(),
            new TemplateOnlySemanticQueryExecutor(),
            new SegmentSchemaCompatibilityChecker());
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new SegmentManagementController(
                    new SemanticTagGroupService(),
                    segmentComputeService,
                    new SegmentFilterPresetService()))
            .build();
  }

  @Test
  void shouldListStaticSemanticTagGroups() throws Exception {
    mockMvc
        .perform(get("/api/semantic-tag-groups/static").param("entityType", "issue"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entityType").value("issue"))
        .andExpect(jsonPath("$.schemaHash").isString())
        .andExpect(jsonPath("$.groups[0].groupKey").value("severity_level"))
        .andExpect(jsonPath("$.groups[0].values[0].valueKey").value("LEVEL1"))
        .andExpect(jsonPath("$.groups[1].groupKey").value("urgency"))
        .andExpect(jsonPath("$.groups[1].values[0].valueKey").value("P1"))
        .andExpect(jsonPath("$.groups[2].groupKey").value("system_test_exclusion_type"))
        .andExpect(jsonPath("$.groups[3].groupKey").value("delay_cause"))
        .andExpect(jsonPath("$.groups[3].values[0].valueKey").value("TECHNICAL_BLOCKER"))
        .andExpect(jsonPath("$.groups[7].groupKey").value("ratio_empty_value_policy"))
        .andExpect(jsonPath("$.groups[7].selectionMode").value("SINGLE"));
  }

  @Test
  void shouldCreateDynamicSegmentDefinition() throws Exception {
    String body =
        """
        {
          "name": "Open customer issues",
          "segmentType": "DYNAMIC",
          "entityType": "issue",
          "scenarioKey": "customer_issue",
          "scopeChain": ["customer_issue_base_scope", "customer_issue_open_scope"],
          "compositionMode": "INTERSECT",
          "ruleJson": "{\\"templateName\\":\\"customer_issue_open_scope\\"}",
          "memberSource": "RULE",
          "tagSchemaHash": "schema-v1",
          "createdBy": "pm"
        }
        """;

    mockMvc
        .perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.segmentType").value("DYNAMIC"))
        .andExpect(jsonPath("$.scopePlan.scopeKeys[0]").value("customer_issue_base_scope"))
        .andExpect(jsonPath("$.previewMemberCount").value(0))
        .andExpect(jsonPath("$.status").value("ENABLED"));
  }

  @Test
  void shouldDisableSegmentDefinition() throws Exception {
    String body =
        """
        {
          "name": "Temporary segment",
          "segmentType": "DYNAMIC",
          "entityType": "issue",
          "scenarioKey": "system_test",
          "scopeChain": ["system_test_issue_scope"],
          "compositionMode": "INTERSECT",
          "ruleJson": "{\\"templateName\\":\\"system_test_issue_scope\\"}",
          "memberSource": "RULE",
          "tagSchemaHash": "schema-v1",
          "createdBy": "pm"
        }
        """;

    mockMvc
        .perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            patch("/api/segments/1/disabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operator\":\"admin\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.status").value("DISABLED"));
  }

  @Test
  void shouldCreateUpdateApplyAndDeleteFilterPreset() throws Exception {
    String createBody =
        """
        {
          "presetName": "Open P1 issues",
          "ownerUserId": "pm",
          "visibility": "PRIVATE",
          "entityType": "issue",
          "scenarioKey": "customer_issue",
          "scopeKey": "customer_issue_open_scope",
          "dslJson": "{\\"conditions\\":[{\\"field\\":\\"urgency\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"P1\\"}]}",
          "tagSchemaHash": "schema-v1",
          "sourceDataWatermarkAtSave": "2026-06-09T10:00:00"
        }
        """;

    mockMvc
        .perform(post("/api/segment-filter-presets").contentType(MediaType.APPLICATION_JSON).content(createBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.presetName").value("Open P1 issues"))
        .andExpect(jsonPath("$.visibility").value("PRIVATE"))
        .andExpect(jsonPath("$.dslHash").isString());

    String updateBody =
        """
        {
          "presetName": "Open P2 issues",
          "visibility": "TEAM",
          "dslJson": "{\\"conditions\\":[{\\"field\\":\\"urgency\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"P2\\"}]}",
          "tagSchemaHash": "schema-v2",
          "sourceDataWatermarkAtSave": "2026-06-09T11:00:00"
        }
        """;

    mockMvc
        .perform(patch("/api/segment-filter-presets/1").contentType(MediaType.APPLICATION_JSON).content(updateBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.presetName").value("Open P2 issues"))
        .andExpect(jsonPath("$.visibility").value("TEAM"))
        .andExpect(jsonPath("$.tagSchemaHash").value("schema-v2"));

    mockMvc
        .perform(post("/api/segment-filter-presets/1/apply").param("currentTagSchemaHash", "schema-v1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaCompatible").value(false))
        .andExpect(jsonPath("$.compatibilityMessage").value("SCHEMA_REVIEW_REQUIRED"));

    mockMvc.perform(delete("/api/segment-filter-presets/1")).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/segment-filter-presets").param("ownerUserId", "pm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}
