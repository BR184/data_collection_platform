package com.data.collection.platform.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.service.SegmentComputeService;
import com.data.collection.platform.service.SegmentCostEstimator;
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
                    new SemanticTagGroupService(), segmentComputeService))
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
        .andExpect(jsonPath("$.groups[1].values[0].valueKey").value("P1"));
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
}
