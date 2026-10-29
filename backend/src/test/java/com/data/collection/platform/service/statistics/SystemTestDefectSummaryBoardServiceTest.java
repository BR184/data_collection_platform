package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
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
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 系统测试缺陷汇总主表与下钻一致性：模块总数只包含常规缺陷，建议类仍可独立下钻。
 */
@ExtendWith(MockitoExtension.class)
class SystemTestDefectSummaryBoardServiceTest {

  private static final String BOARD_KEY = "system-test-defect-summary";
  private static final String MODULE = "工程图";
  private static final String PHASE = "R1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final Map<String, String> PHASE_FILTERS = Map.of("testingPhase", PHASE);

  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private SystemTestPhaseCatalogService phaseCatalogService;
  @Mock private SystemTestPhaseScopeResolver phaseScopeResolver;
  @Mock private LabelGroupDefaultFilterService labelGroupDefaultFilterService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  @Mock private IssueFactRecordRepository issueFactRecordRepository;

  @BeforeEach
  void setUp() {
    lenient()
        .when(phaseCatalogService.listParentNames(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID))
        .thenReturn(List.of(PHASE));
    lenient()
        .when(phaseScopeResolver.resolvePhases(SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, PHASE))
        .thenReturn(List.of(PHASE));
    lenient()
        .when(phaseScopeResolver.resolvePhases(
            SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID, List.of(PHASE)))
        .thenReturn(List.of(PHASE));
    lenient()
        .when(labelGroupDefaultFilterService.defaultCondition(BOARD_KEY, "moduleName"))
        .thenReturn(Optional.empty());
    lenient().when(issueFactRecordRepository.findForFilterOptions(any())).thenReturn(List.of());
    lenient().when(runtimeSupport.loadFacts(anyMap(), isNull())).thenReturn(fixtureSources());
    lenient().doAnswer(invocation -> {
          Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse> supplier =
              invocation.getArgument(1);
          return supplier.apply(StatisticBoardTestSnapshotScopes.testSourceRead());
        })
        .when(snapshotService)
        .readOrRefresh(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {MODULE, TOTAL_ROW_KEY})
  void test_moduleTotalDrilldownMatchesBoardOnModuleAndTotalRows(String rowKey) {
    SystemTestDefectSummaryBoardService service = service();

    StatisticBoardResponse board = service.loadBoard(PHASE_FILTERS);
    assertThat(cell(row(board, rowKey), "module_total").numericValue()).isEqualTo(3);
    assertThat(cell(row(board, rowKey), "suggestion_total").numericValue()).isEqualTo(1);

    StatisticDetailResponse detail = service.loadDetail(detailRequest(rowKey, "module_total"));

    assertThat(detail.total()).isEqualTo(3);
    assertThat(detail.records()).extracting(record -> record.get("title"))
        .containsExactlyInAnyOrder("常规缺陷A", "常规缺陷B", "常规缺陷C");
  }

  @Test
  void test_suggestionTotalDrilldownStillListsSuggestionOnly() {
    SystemTestDefectSummaryBoardService service = service();

    StatisticBoardResponse board = service.loadBoard(PHASE_FILTERS);
    assertThat(cell(row(board, TOTAL_ROW_KEY), "suggestion_total").numericValue()).isEqualTo(1);

    StatisticDetailResponse detail = service.loadDetail(detailRequest(MODULE, "suggestion_total"));

    assertThat(detail.total()).isEqualTo(1);
    assertThat(detail.records()).extracting(record -> record.get("title"))
        .containsExactly("界面优化建议");
  }

  private StatisticDetailRequest detailRequest(String rowKey, String columnKey) {
    return new StatisticDetailRequest(
        BOARD_KEY, rowKey, columnKey, 1, 10, "iid", "ascending", PHASE_FILTERS);
  }

  private List<StatisticIssueFactSource> fixtureSources() {
    return List.of(
        new StatisticIssueFactSource(fact(1001, "常规缺陷A", "LEVEL2", "缺陷", false, "")),
        new StatisticIssueFactSource(fact(1002, "常规缺陷B", "LEVEL2", "缺陷", false, "")),
        new StatisticIssueFactSource(fact(1003, "常规缺陷C", "LEVEL3", "缺陷", false, "")),
        new StatisticIssueFactSource(fact(1004, "界面优化建议", "LEVEL3", "建议", false, "")),
        new StatisticIssueFactSource(fact(1005, "已排除议题", "LEVEL2", "缺陷", true, "需求如此")));
  }

  private IssueFactRecord fact(
      int iid, String title, String severityLevel, String category, boolean excluded, String exclusionReason) {
    LocalDateTime now = LocalDateTime.of(2026, 7, 31, 10, 0);
    return new IssueFactRecord(
        SystemTestPhaseCatalogService.LEGACY_CROWN_CAD_PROJECT_ID,
        "CrownCAD",
        (long) iid,
        iid,
        title,
        "opened",
        PHASE,
        PHASE + "系统测试",
        severityLevel,
        "P2",
        "待处理",
        category,
        "",
        excluded,
        exclusionReason,
        false,
        false,
        false,
        false,
        false,
        "CC2026R4",
        "张三",
        "李四",
        "",
        List.of(MODULE),
        "",
        List.of(),
        false,
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

  private SystemTestDefectSummaryBoardService service() {
    return new SystemTestDefectSummaryBoardService(
        new JsonUtils(new ObjectMapper()),
        runtimeSupport,
        mock(StatisticIssueLinkSupport.class),
        phaseCatalogService,
        phaseScopeResolver,
        labelGroupDefaultFilterService,
        labelGroupExpansionService,
        snapshotService,
        snapshotRequestFactory,
        issueFactRecordRepository);
  }

  private StatisticRowData row(StatisticBoardResponse response, String rowKey) {
    return response.rows().stream()
        .filter(row -> rowKey.equals(row.rowKey()))
        .findFirst()
        .orElseThrow();
  }

  private StatisticCellData cell(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow();
  }
}