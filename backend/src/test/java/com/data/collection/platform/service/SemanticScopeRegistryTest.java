package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticScopeRegistryTest {
  private final SemanticScopeRegistry registry = SemanticScopeRegistry.withDefaults();

  @Test
  void shouldRegisterPhaseOneScopes() {
    assertThat(registry.resolve("system_test_issue_scope").entityType()).isEqualTo("issue");
    assertThat(registry.resolve("customer_issue_base_scope").sqlTemplateKey())
        .isEqualTo("customer_issue_base_scope");
    assertThat(registry.resolve("customer_issue_open_scope").description()).contains("open");
  }

  @Test
  void shouldComposeCompatibleScopeChain() {
    SemanticScopePlan plan =
        registry.compose(
            List.of("customer_issue_base_scope", "customer_issue_open_scope"),
            ScopeCompositionMode.INTERSECT);

    assertThat(plan.entityType()).isEqualTo("issue");
    assertThat(plan.scopeKeys())
        .containsExactly("customer_issue_base_scope", "customer_issue_open_scope");
    assertThat(plan.sqlTemplateKeys())
        .containsExactly("customer_issue_base_scope", "customer_issue_open_scope");
    assertThat(plan.planHash()).hasSize(64);
  }

  @Test
  void shouldRejectUnknownScope() {
    assertThatThrownBy(() -> registry.resolve("missing_scope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown semantic scope");
  }

  @Test
  void shouldRejectEntityTypeMismatch() {
    assertThatThrownBy(
            () ->
                registry.validateCompatibility(
                    "merge_request", List.of("system_test_issue_scope")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not support entity type");
  }
}
