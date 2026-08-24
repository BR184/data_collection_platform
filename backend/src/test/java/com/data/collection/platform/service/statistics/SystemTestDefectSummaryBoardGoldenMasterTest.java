package com.data.collection.platform.service.statistics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.labelgroup.LabelGroupDefaultFilterService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 系统测试缺陷汇总看板金标测试：重构安全网（docs/plans/statistics-board-framework-refactor.md 第9节）。 */
@ExtendWith(MockitoExtension.class)
class SystemTestDefectSummaryBoardGoldenMasterTest
    extends AbstractStatisticBoardGoldenMasterTest {

  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private SystemTestPhaseCatalogService phaseCatalogService;
  @Mock private SystemTestPhaseScopeResolver phaseScopeResolver;
  @Mock private LabelGroupDefaultFilterService labelGroupDefaultFilterService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @BeforeEach
  void setUp() {
    lenient().when(phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID))
        .thenReturn(List.of("R1"));
    lenient().when(phaseScopeResolver.resolvePhases(
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, "R1"))
        .thenReturn(List.of("R1"));
    lenient().when(labelGroupDefaultFilterService.defaultCondition("system-test-defect-summary", "moduleName"))
        .thenReturn(java.util.Optional.empty());
    lenient().when(labelGroupExpansionService.expand(any(), any(), anyString(), anyString(), any()))
        .thenAnswer(inv -> {
          String fieldKey = inv.getArgument(2);
          List<String> values = switch (fieldKey) {
            case "moduleName" -> List.of("工程图");
            case "bugStatus" -> List.of("待处理");
            default -> List.of();
          };
          return new com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse(
              (Long) inv.getArgument(0), "组", (String) inv.getArgument(1), values, List.of());
        });
    lenient().when(runtimeSupport.loadFacts(anyMap(), isNull()))
        .thenReturn(List.of(
            new StatisticIssueFactSource(issue(2688, "工程图", "R1", "LEVEL1", "P1", "待处理", true)),
            new StatisticIssueFactSource(issue(2689, "平台", "R1", "LEVEL2", "P3", "已修复/完成", false))));
    lenient().when(issueFactRecordRepository.findForFilterOptions(any()))
        .thenReturn(List.of(issue(2688, "工程图", "R1", "LEVEL1", "P1", "待处理", true)));
  }

  @Test
  void test_golden_master_locks_board_response_across_filter_matrix() throws Exception {
    runGoldenMaster();
  }

  @Override
  protected Path goldenDir() {
    return Path.of("src", "test", "resources", "golden", "system-test-defect-summary");
  }

  @Override
  protected Map<String, String> filterMatrix() {
    return Map.ofEntries(
        Map.entry("empty-group", "{}"),
        Map.entry("project-name-eq-hit",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"CrownCAD\"}]}"),
        Map.entry("project-name-eq-miss",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"projectName\",\"operator\":\"eq\",\"value\":\"Other\"}]}"),
        Map.entry("module-intersects",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"模块\",\"operator\":\"intersects\",\"values\":[\"工程图\"]}]}"),
        Map.entry("severity-text-eq",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"severityLevel\",\"operator\":\"eq\",\"value\":\"一级缺陷\"}]}"),
        Map.entry("testing-phase-eq",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"testingPhase\",\"operator\":\"eq\",\"value\":\"R1\"}]}"),
        Map.entry("created-between",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"createdAt\",\"operator\":\"between\",\"value\":\"2026-07-01\",\"secondaryValue\":\"2026-07-31\"}]}"),
        Map.entry("state-eq-open",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"state\",\"operator\":\"eq\",\"value\":\"open\"}]}"),
        Map.entry("bug-status-label-group",
            "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"bugStatus\",\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":2,\"labelGroupName\":\"状态组\",\"operator\":\"intersects\",\"values\":[\"待处理\"]}]}"),
        Map.entry("or-combo",
            "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"priorityLevel\",\"operator\":\"eq\",\"value\":\"P1\"},{\"fieldKey\":\"priorityLevel\",\"operator\":\"eq\",\"value\":\"P3\"}]}"));
  }

  @Override
  protected AbstractStatisticBoardService service() {
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

  private IssueFactRecord issue(
      int iid, String module, String phase, String severity, String priority,
      String bugStatus, boolean closed) {
    LocalDateTime now = LocalDateTime.of(2026, 7, 31, 10, 0);
    return new IssueFactRecord(
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
        "CrownCAD",
        (long) iid,
        iid,
        "issue " + iid,
        closed ? "closed" : "opened",
        phase,
        phase + "系统测试",
        severity,
        priority,
        bugStatus,
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
        List.of(module),
        "",
        List.of(),
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
        closed ? now : null);
  }
}
