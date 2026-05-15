package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTestDefectSummaryRuleExplanationTest {
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;

  @Test
  void shouldDescribeSystemTestBoardRuleFlowEvenWhenMirrorTablesAreEmpty() {
    when(runtimeSupport.loadFacts(anyMap(), any()))
        .thenReturn(List.of());
    SystemTestDefectSummaryBoardService service = new SystemTestDefectSummaryBoardService(
        new JsonUtils(new ObjectMapper()),
        runtimeSupport,
        mock(StatisticIssueLinkSupport.class));

    StatisticBoardRuleExplanationResponse response = service.getRuleExplanation(Map.of());

    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isEqualTo("system-test-defect-summary@2026-04-09-v5");
    assertThat(response.flowSteps()).extracting("key")
        .containsExactly("source-load", "scope-filter", "exclude-invalid-issues", "module-expand");
    assertThat(response.flowSteps()).allSatisfy(step -> {
      assertThat(step.title()).isNotBlank();
      assertThat(step.description()).isNotBlank();
      assertThat(step.inputCount()).isZero();
      assertThat(step.outputCount()).isZero();
    });
    assertThat(response.metricDefinitions()).extracting("key")
        .contains("level1", "priority-summary", "summary", "new-issue", "legacy");
  }
}
