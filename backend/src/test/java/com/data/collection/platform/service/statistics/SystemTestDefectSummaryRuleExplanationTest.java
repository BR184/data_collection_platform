package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.labelgroup.LabelGroupDefaultFilterService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
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
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @Test
  void shouldDescribeSystemTestBoardRuleFlowEvenWhenMirrorTablesAreEmpty() {
    when(phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID))
        .thenReturn(List.of());
    when(labelGroupDefaultFilterService.defaultCondition("system-test-defect-summary", "moduleName"))
        .thenReturn(Optional.empty());
    SystemTestDefectSummaryBoardService service = service();

    StatisticBoardRuleExplanationResponse response = service.getRuleExplanation(Map.of());

    assertThat(response.supported()).isTrue();
    assertThat(response.version()).isEqualTo("system-test-defect-summary@2026-09-10-v14");
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

  @Test
  void shouldMatchDelayCauseFromOriginalLabelsWhenFactCauseHasNotBeenRebuilt() {
    IssueFactRecord issue = issueWithLabelOnlyDelayCause();
    when(phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID))
        .thenReturn(List.of("R1"));
    when(phaseScopeResolver.resolvePhases(
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, "R1"))
        .thenReturn(List.of("R1"));
    when(labelGroupDefaultFilterService.defaultCondition("system-test-defect-summary", "moduleName"))
        .thenReturn(Optional.empty());
    when(issueFactRecordRepository.findForFilterOptions(any())).thenReturn(List.of(issue));
    when(runtimeSupport.loadFacts(anyMap(), isNull()))
        .thenReturn(List.of(new StatisticIssueFactSource(issue)));

    StatisticDetailResponse response = service().loadDetail(new StatisticDetailRequest(
        "system-test-defect-summary",
        "工程图",
        "level1_total",
        1,
        10,
        "iid",
        "ascending",
        Map.of("testingPhase", "R1", "delayCause", "技术卡点")));

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.records()).extracting(record -> record.get("title"))
        .containsExactly("标签回退议题");
  }

  private SystemTestDefectSummaryBoardService service() {
    return new SystemTestDefectSummaryBoardService(
        new JsonUtils(new ObjectMapper()),
        runtimeSupport,
        mock(StatisticIssueLinkSupport.class),
        phaseCatalogService,
        phaseScopeResolver,
        labelGroupDefaultFilterService,
        labelGroupExpansionService,
        mock(StatisticBoardSnapshotService.class),
        mock(StatisticBoardSnapshotRequestFactory.class),
        issueFactRecordRepository);
  }

  private IssueFactRecord issueWithLabelOnlyDelayCause() {
    LocalDateTime now = LocalDateTime.of(2026, 7, 31, 10, 0);
    return new IssueFactRecord(
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
        "CrownCAD",
        2688L,
        2688,
        "标签回退议题",
        "opened",
        "R1",
        "R1系统测试",
        "LEVEL1",
        "P1",
        "待处理",
        "缺陷",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R4",
        "张三",
        "李四",
        "",
        List.of("工程图"),
        "",
        List.of("技术卡点"),
        true,
        "",
        "",
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(1),
        now,
        null);
  }
}
