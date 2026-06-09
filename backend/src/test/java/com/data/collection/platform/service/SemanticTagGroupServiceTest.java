package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

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
            "defect_reason_standard",
            "ratio_empty_value_policy");
  }

  @Test
  void shouldKeepSeverityAndUrgencyAsSeparateBusinessMeanings() {
    SemanticTagGroupCatalog catalog = service.listStaticGroups("issue");

    SemanticTagGroupDefinition severity = catalog.requireGroup("severity_level");
    SemanticTagGroupDefinition urgency = catalog.requireGroup("urgency");

    assertThat(severity.values())
        .extracting(SemanticTagValueDefinition::valueKey)
        .containsExactly("LEVEL1", "LEVEL2", "LEVEL3", "SUGGESTION");
    assertThat(urgency.values())
        .extracting(SemanticTagValueDefinition::valueKey)
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
    assertThat(catalog.requireGroup("ratio_empty_value_policy").selectionMode()).isEqualTo("SINGLE");
  }
}
