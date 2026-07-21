package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemTestPhaseFilterGroupExpanderTest {

  @Test
  void r3AndR4ParentSelectionsExpandWithTheSameConfiguredChildPhaseRule() {
    SystemTestPhaseCatalogService catalog = mock(SystemTestPhaseCatalogService.class);
    when(catalog.listTestingPhasesByParent(9L, "CC2026R3"))
        .thenReturn(List.of("CC2026R3第一轮系统测试", "CC2026R3第二轮系统测试"));
    when(catalog.listTestingPhasesByParent(9L, "CC2026R4"))
        .thenReturn(List.of("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试"));
    SystemTestPhaseScopeResolver resolver = new SystemTestPhaseScopeResolver(catalog);

    StatisticFilterCondition expandedR3 = expandParent("CC2026R3", resolver);
    StatisticFilterCondition expandedR4 = expandParent("CC2026R4", resolver);

    assertThat(expandedR3.valueType()).isEqualTo("RESOLVED_LITERAL_SET");
    assertThat(expandedR3.values())
        .containsExactly("CC2026R3第一轮系统测试", "CC2026R3第二轮系统测试");
    assertThat(expandedR4.valueType()).isEqualTo("RESOLVED_LITERAL_SET");
    assertThat(expandedR4.values())
        .containsExactly("CC2026R4第一轮系统测试", "CC2026R4第二轮系统测试");
  }

  @Test
  void concretePhaseSelectionKeepsExactPhaseSemantics() {
    SystemTestPhaseCatalogService catalog = mock(SystemTestPhaseCatalogService.class);
    when(catalog.listTestingPhasesByParent(9L, "CC2026R4第一轮系统测试"))
        .thenReturn(List.of());
    when(catalog.isConfiguredTestingPhase(9L, "CC2026R4第一轮系统测试")).thenReturn(true);
    SystemTestPhaseScopeResolver resolver = new SystemTestPhaseScopeResolver(catalog);
    StatisticFilterGroup source =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition(
                    "testingPhase", "eq", "CC2026R4第一轮系统测试", null)));

    StatisticFilterCondition expanded =
        SystemTestPhaseFilterGroupExpander.expand(source, 9L, resolver).conditions().getFirst();

    assertThat(expanded.valueType()).isEqualTo("RESOLVED_LITERAL_SET");
    assertThat(expanded.values()).containsExactly("CC2026R4第一轮系统测试");
  }

  private StatisticFilterCondition expandParent(
      String parent, SystemTestPhaseScopeResolver resolver) {
    StatisticFilterGroup source =
        new StatisticFilterGroup(
            "AND", List.of(new StatisticFilterCondition("testingPhase", "eq", parent, null)));
    return SystemTestPhaseFilterGroupExpander.expand(source, 9L, resolver)
        .conditions()
        .getFirst();
  }
}
