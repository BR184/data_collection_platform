package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.labelgroup.LabelGroupDefaultFilterService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTestDefectSummaryRuleExplanationTest {
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private SystemTestPhaseCatalogService phaseCatalogService;
  @Mock private SystemTestPhaseScopeResolver phaseScopeResolver;
  @Mock private LabelGroupDefaultFilterService labelGroupDefaultFilterService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;

  @Test
  void shouldDescribeSystemTestBoardRuleFlowEvenWhenMirrorTablesAreEmpty() {
    when(runtimeSupport.loadFacts(anyMap(), any()))
        .thenReturn(List.of());
    when(phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID))
        .thenReturn(List.of());
    when(labelGroupDefaultFilterService.defaultCondition("system-test-defect-summary", "moduleName"))
        .thenReturn(Optional.empty());
    SystemTestDefectSummaryBoardService service = new SystemTestDefectSummaryBoardService(
        new JsonUtils(new ObjectMapper()),
        runtimeSupport,
        mock(StatisticIssueLinkSupport.class),
        phaseCatalogService,
        phaseScopeResolver,
        labelGroupDefaultFilterService,
        labelGroupExpansionService);

    StatisticBoardRuleExplanationResponse response = service.getRuleExplanation(Map.of());

    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isEqualTo("system-test-defect-summary@2026-04-09-v6");
    assertThat(response.flowSteps()).extracting("key")
        .containsExactly("source-load", "scope-filter", "exclude-invalid-issues", "apply-filter-group", "module-expand");
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
