package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SegmentComputeServiceTest {
  private final SegmentCostEstimator costEstimator = new SegmentCostEstimator(10_000, 30_000);
  private final SemanticQueryExecutor queryExecutor = new TemplateOnlySemanticQueryExecutor();
  private final SegmentSchemaCompatibilityChecker compatibilityChecker =
      new SegmentSchemaCompatibilityChecker();
  private final SegmentComputeService service =
      new SegmentComputeService(
          SemanticScopeRegistry.withDefaults(),
          costEstimator,
          queryExecutor,
          compatibilityChecker);

  @Test
  void shouldCreateDynamicSegmentDefinitionWithEmptyPreviewMembers() {
    CreateSegmentRequest request =
        new CreateSegmentRequest(
            "Open customer issues",
            SegmentType.DYNAMIC,
            "issue",
            "customer_issue",
            List.of("customer_issue_base_scope", "customer_issue_open_scope"),
            ScopeCompositionMode.INTERSECT,
            "{\"templateName\":\"customer_issue_open_scope\"}",
            SegmentMemberSource.RULE,
            "schema-v1",
            "pm");

    SegmentDefinition definition = service.createDefinition(request);

    assertThat(definition.id()).isPositive();
    assertThat(definition.name()).isEqualTo("Open customer issues");
    assertThat(definition.scopePlan().scopeKeys())
        .containsExactly("customer_issue_base_scope", "customer_issue_open_scope");
    assertThat(definition.previewMembers()).isEmpty();
    assertThat(definition.status()).isEqualTo(SegmentDefinitionStatus.ENABLED);
    assertThat(definition.executionPlan().estimatedMembers()).isZero();
  }

  @Test
  void shouldCreateStaticSnapshotShellAndAuditRecord() {
    SegmentDefinition definition =
        service.createStaticSnapshot(
            new CreateSegmentSnapshotRequest(
                "Manual review sample",
                "issue",
                "system_test",
                "system_test_issue_scope",
                "sampling before approval",
                "admin"));

    assertThat(definition.segmentType()).isEqualTo(SegmentType.STATIC);
    assertThat(definition.memberSource()).isEqualTo(SegmentMemberSource.SNAPSHOT);
    assertThat(definition.auditEntries())
        .extracting(SegmentMemberAuditEntry::action)
        .containsExactly("SNAPSHOT_CREATE");
  }

  @Test
  void shouldDisableExistingSegmentDefinition() {
    SegmentDefinition definition =
        service.createStaticSnapshot(
            new CreateSegmentSnapshotRequest(
                "Temporary segment",
                "issue",
                "system_test",
                "system_test_issue_scope",
                "no longer needed",
                "admin"));

    SegmentDefinition disabled = service.disable(definition.id(), "admin");

    assertThat(disabled.status()).isEqualTo(SegmentDefinitionStatus.DISABLED);
    assertThat(disabled.auditEntries())
        .extracting(SegmentMemberAuditEntry::action)
        .contains("DISABLE");
  }

  @Test
  void shouldListCreatedSegmentDefinitionsInCreationOrder() {
    SegmentDefinition first =
        service.createStaticSnapshot(
            new CreateSegmentSnapshotRequest(
                "First segment",
                "issue",
                "system_test",
                "system_test_issue_scope",
                "first",
                "admin"));
    SegmentDefinition second =
        service.createStaticSnapshot(
            new CreateSegmentSnapshotRequest(
                "Second segment",
                "issue",
                "system_test",
                "system_test_issue_scope",
                "second",
                "admin"));

    assertThat(service.listDefinitions())
        .extracting(SegmentDefinition::id)
        .containsExactly(first.id(), second.id());
  }

  @Test
  void shouldRejectDynamicSegmentWhenEstimatedMembersExceedSoftLimit() {
    SegmentCostEstimator strictEstimator = new SegmentCostEstimator(0, 30_000);
    SemanticQueryExecutor oneMemberExecutor =
        new TemplateOnlySemanticQueryExecutor() {
          @Override
          public SegmentExecutionPlan explain(SegmentRuleDsl dsl, SegmentExecutionContext context) {
            return new SegmentExecutionPlan("customer_issue_base_scope", 1, 1, 30_000, 50_000);
          }
        };
    SegmentComputeService strictService =
        new SegmentComputeService(
            SemanticScopeRegistry.withDefaults(),
            strictEstimator,
            oneMemberExecutor,
            compatibilityChecker);

    CreateSegmentRequest request =
        new CreateSegmentRequest(
            "Too large",
            SegmentType.DYNAMIC,
            "issue",
            "customer_issue",
            List.of("customer_issue_base_scope"),
            ScopeCompositionMode.INTERSECT,
            "{\"templateName\":\"customer_issue_base_scope\"}",
            SegmentMemberSource.RULE,
            "schema-v1",
            "pm");

    assertThatThrownBy(() -> strictService.createDefinition(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("estimated members exceed");
  }
}
