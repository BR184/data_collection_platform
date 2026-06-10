package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticTagGroupServiceTest {
  private final SemanticTagGroupService service = SemanticTagGroupService.withDefaults();

  @Test
  void shouldReturnPhaseOneStaticGroupsWithSchemaHash() {
    SemanticTagGroupCatalog catalog = service.listStaticGroups("issue");

    assertThat(catalog.schemaHash()).hasSize(64);
    assertThat(catalog.groups())
        .extracting(SemanticTagGroupDefinition::groupKey)
        .containsExactly(
            "severity_level",
            "urgency",
            "system_test_exclusion_type",
            "delay_cause",
            "customer_issue_closure_status",
            "illegal_type",
            "defect_reason_standard");
  }

  @Test
  void shouldKeepSeverityAndUrgencyAsSeparateBusinessMeanings() {
    SemanticTagGroupCatalog catalog = service.listStaticGroups("issue");

    SemanticTagGroupDefinition severity = catalog.requireGroup("severity_level");
    SemanticTagGroupDefinition urgency = catalog.requireGroup("urgency");

    assertThat(severity.label()).isEqualTo("严重程度");
    assertThat(urgency.label()).isEqualTo("紧急程度");
    assertThat(severity.values())
        .extracting(SemanticTagValueDefinition::label)
        .containsExactly("一级缺陷", "二级缺陷", "三级缺陷", "建议类");
    assertThat(urgency.values())
        .extracting(SemanticTagValueDefinition::label)
        .containsExactly("P1", "P2", "P3");
  }

  @Test
  void shouldReturnIllegalTypeStaticValues() {
    SemanticTagGroupDefinition illegalType =
        service.listStaticGroups("issue").requireGroup("illegal_type");

    assertThat(illegalType.values())
        .extracting(SemanticTagValueDefinition::valueKey)
        .contains(
            "MISSING_SEVERITY",
            "MISSING_MODULE",
            "MISSING_REQUIRED_REPLY",
            "NON_UNIQUE_DEFECT_REASON",
            "INVALID_PLAN_RESOLVE_TIME",
            "LEVEL1_MISSING_OWNER_SIGN");
  }

  @Test
  void shouldReturnBusinessRuleStaticGroupsForPhaseThreeTemplate() {
    SemanticTagGroupCatalog catalog = service.listStaticGroups("issue");

    assertThat(catalog.requireGroup("system_test_exclusion_type").values())
        .extracting(SemanticTagValueDefinition::valueKey)
        .containsExactly(
            "FUNCTION_BLOCKED",
            "REJECTED",
            "SUGGESTION",
            "CLOSED_REJECTION",
            "CLOSED_REQUIREMENT_AS_IS");
    assertThat(catalog.requireGroup("delay_cause").values())
        .extracting(SemanticTagValueDefinition::valueKey)
        .containsExactly(
            "TECHNICAL_BLOCKER",
            "SOLUTION_BLOCKER",
            "RESOURCE_BLOCKER",
            "DATA_ANOMALY",
            "ALGORITHM_ISSUE",
            "MECHANISM_ISSUE",
            "COMPUTATION_EFFICIENCY");
    assertThat(catalog.groups())
        .extracting(SemanticTagGroupDefinition::groupKey)
        .doesNotContain("ratio_empty_value_policy");
  }

  @Test
  void shouldPreferEnabledDatabaseGroupsWhenConfigured() {
    SemanticTagGroupService dbBackedService =
        new SemanticTagGroupService(
            entityType -> List.of(
                new SemanticTagGroupDefinition(
                    entityType,
                    "customer_priority",
                    "Customer priority",
                    "STATIC",
                    "customer_priority_policy",
                    "SINGLE",
                    "EXACT",
                    true,
                    10,
                    List.of(new SemanticTagValueDefinition("VIP", "VIP customer", "STRING", "VIP", true, 10)))));

    SemanticTagGroupCatalog catalog = dbBackedService.listStaticGroups("issue");

    assertThat(catalog.groups()).hasSize(1);
    assertThat(catalog.requireGroup("customer_priority").selectionMode()).isEqualTo("SINGLE");
    assertThat(catalog.requireGroup("customer_priority").values())
        .extracting(SemanticTagValueDefinition::valueKey)
        .containsExactly("VIP");
    assertThat(catalog.schemaHash()).hasSize(64);
  }

  @Test
  void shouldLocalizeKnownDatabaseGroupsBeforeReturningToPages() {
    SemanticTagGroupService dbBackedService =
        new SemanticTagGroupService(
            entityType -> List.of(
                new SemanticTagGroupDefinition(
                    entityType,
                    "customer_issue_closure_status",
                    "Customer issue closure status",
                    "STATIC",
                    "customer_issue_closure_policy",
                    "MULTIPLE",
                    "EXACT",
                    true,
                    10,
                    List.of(
                        new SemanticTagValueDefinition(
                            "FIXED_DONE", "Fixed done", "STRING", "FIXED_DONE", true, 10),
                        new SemanticTagValueDefinition(
                            "NOT_REPRODUCED",
                            "Not reproduced",
                            "STRING",
                            "NOT_REPRODUCED",
                            true,
                            20)))));

    SemanticTagGroupDefinition group =
        dbBackedService.listStaticGroups("issue").requireGroup("customer_issue_closure_status");

    assertThat(group.label()).isEqualTo("客户问题闭环状态");
    assertThat(group.values())
        .extracting(SemanticTagValueDefinition::label)
        .containsExactly("已修复/完成", "未复现");
  }

  @Test
  void shouldFallbackToStaticIssueGroupsWhenDatabaseHasNoDefinitions() {
    SemanticTagGroupService dbBackedService = new SemanticTagGroupService(entityType -> List.of());

    SemanticTagGroupCatalog catalog = dbBackedService.listStaticGroups("issue");

    assertThat(catalog.groups())
        .extracting(SemanticTagGroupDefinition::groupKey)
        .containsExactly(
            "severity_level",
            "urgency",
            "system_test_exclusion_type",
            "delay_cause",
            "customer_issue_closure_status",
            "illegal_type",
            "defect_reason_standard");
  }
}
