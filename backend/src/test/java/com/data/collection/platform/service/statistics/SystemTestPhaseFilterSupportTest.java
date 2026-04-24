package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemTestPhaseFilterSupportTest {

  @Test
  void shouldMatchEmptyFilterGroup() {
    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R1"), null)).isTrue();
    assertThat(
            SystemTestPhaseFilterSupport.matches(
                new TestIssue("R1"), new StatisticFilterGroup("AND", List.of())))
        .isTrue();
  }

  @Test
  void shouldMatchTestingPhaseConditionWithAndLogic() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition("testingPhase", "eq", "R1", null),
                new StatisticFilterCondition("testingPhase", "isNotEmpty", null, null)));

    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R1"), filterGroup)).isTrue();
    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R2"), filterGroup)).isFalse();
  }

  @Test
  void shouldMatchTestingPhaseConditionWithOrLogic() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "OR",
            List.of(
                new StatisticFilterCondition("testingPhase", "eq", "R1", null),
                new StatisticFilterCondition("testingPhase", "eq", "R2", null)));

    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R2"), filterGroup)).isTrue();
    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R3"), filterGroup)).isFalse();
  }

  @Test
  void shouldIgnoreUnsupportedConditionForSystemTestPhaseFilter() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND", List.of(new StatisticFilterCondition("moduleName", "eq", "模块A", null)));

    assertThat(SystemTestPhaseFilterSupport.matches(new TestIssue("R1"), filterGroup)).isTrue();
  }

  @Test
  void shouldExtractSelectedTestingPhase() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(new StatisticFilterCondition("testingPhase", "eq", "  R1  ", null)));

    assertThat(SystemTestPhaseFilterSupport.selectedTestingPhase(filterGroup)).isEqualTo("R1");
  }

  private record TestIssue(String phaseFilterValue) implements SystemTestPhaseFilterSource {}
}
