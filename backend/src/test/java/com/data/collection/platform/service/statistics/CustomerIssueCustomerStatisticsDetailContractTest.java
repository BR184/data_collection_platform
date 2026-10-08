package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.FactProjectionScopeKeyCodec;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.GitlabResourceLinkService;
import com.data.collection.platform.service.IssueProjectionScopeResolver;
import com.data.collection.platform.service.SortSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * S06 公共下钻契约：客户统计板明细必须与主表同源、同集合、同身份。
 *
 * <p>覆盖方案用例 T11（0 数量、0%、零分母、零样本与真实 0 周期可辨）、T19（旧来源版本下钻拒绝、
 * 业务日随下钻重建）、T21（分页身份并集等于指标贡献集合、真实数值顺序与总计钉底）、
 * T26（非法指标/集合/行维度与范围外行身份被拒绝）。
 *
 * <p>用例直接从 {@code loadBoard} 产出的单元格 {@code detailParams} 取行身份与来源版本，
 * 再驱动 {@code loadDetail}，避免测试自造与生产不一致的请求参数。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueCustomerStatisticsDetailContractTest {
  private static final long CUSTOMER_PROJECT_ID = 325L;
  private static final String SOURCE_VERSION = "fact-projection:v1:abc123";
  private static final String BUSINESS_DATE = "2026-09-22";

  @Mock private CustomerIssueFactQueryService factQueryService;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private FactProjectionVersionService projectionVersionService;
  @Mock private IssueProjectionScopeResolver scopeResolver;
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private GitlabResourceLinkService issueLinkService;
  @Mock private JdbcTemplate jdbcTemplate;

  private CustomerIssueCustomerStatisticsBoardService service;

  @BeforeEach
  void setUp() {
    lenient().when(milestoneCatalogService.defaultMilestone()).thenReturn("CC2026R3");
    lenient().when(milestoneCatalogService.resolveMilestoneValues(anyString())).thenReturn(List.of("CC2026 R3"));
    lenient().when(milestoneCatalogService.matches(anyString(), any())).thenReturn(true);
    lenient().when(projectionVersionService.knownSourceInstances(FactType.ISSUE)).thenReturn(Set.of("default"));
    lenient()
        .when(scopeResolver.resolve(anyString(), any(), anyLong(), any(), any()))
        .thenAnswer(
            invocation ->
                Set.of(
                    new FactProjectionScope(
                        invocation.getArgument(0),
                        FactType.ISSUE,
                        ProjectionScopeType.PROJECT,
                        FactProjectionScopeKeyCodec.project(invocation.getArgument(2)))));
    lenient().when(snapshotService.issueFactSourceVersion(any())).thenReturn(SOURCE_VERSION);
    lenient()
        .when(issueLinkService.issueUrl(any(), any(), any()))
        .thenAnswer(
            invocation -> "http://gitlab.example.com/325/-/issues/" + invocation.getArgument(2));
    lenient()
        .doReturn(Map.of())
        .when(jdbcTemplate)
        .query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class));
    StatisticBoardSnapshotService.SourceRead testRead =
        new StatisticBoardSnapshotService.SourceRead(
            StatisticBoardTestSnapshotScopes.defaultProjectScopeSet(), SOURCE_VERSION, 0L, true, null);
    doAnswer(
            invocation ->
                ((Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse>)
                        invocation.getArgument(1))
                    .apply(testRead))
        .when(snapshotService)
        .readOrRefresh(any(), any());
    // 明细链路走一致性边界：桩以同一测试版本执行边界内动作，生产端的版本判定仍然生效。
    lenient()
        .doAnswer(
            invocation ->
                ((Function<StatisticBoardSnapshotService.SourceRead, ?>) invocation.getArgument(1))
                    .apply(testRead))
        .when(snapshotService)
        .withinConsistentCurrentFactRead(any(), any());
    service =
        new CustomerIssueCustomerStatisticsBoardService(
            new JsonUtils(new ObjectMapper()),
            factQueryService,
            milestoneCatalogService,
            snapshotService,
            new StatisticBoardSnapshotRequestFactory(
                new StatisticBoardReadScopeResolver(projectionVersionService, scopeResolver)),
            new StatisticBoardReadScopeResolver(projectionVersionService, scopeResolver),
            runtimeSupport,
            new StatisticIssueLinkSupport(issueLinkService, jdbcTemplate));
  }

  @Test
  void boardCellCarriesRowIdentityBusinessDateAndSourceVersion() {
    stubFacts(fixed("客户甲", 1L));

    StatisticCellData cell = cellOf(loadBoard(BUSINESS_DATE), "客户甲", "defect_total");

    assertThat(cell.detailParams())
        .as("下钻必须能重建主表的业务日与来源代际")
        .containsEntry("businessDate", BUSINESS_DATE)
        .containsEntry("sourceVersion", SOURCE_VERSION)
        .containsEntry("groupBy", "CUSTOMER")
        .containsEntry("total", "false");
    assertThat(cell.detailParams().get("rowKey")).contains("客户甲");
    assertThat(cell.drilldown()).isTrue();
  }

  @Test
  void otherBusinessDateKeepsItsOwnBusinessDayForDrilldown() {
    stubFacts(fixed("客户甲", 1L));

    StatisticCellData cell = cellOf(loadBoard("2026-09-23"), "客户甲", "defect_total");

    assertThat(cell.detailParams())
        .as("跨自然日后下钻按请求当日的业务日重建，不沿用上一次请求")
        .containsEntry("businessDate", "2026-09-23");
  }

  @Test
  void zeroNumeratorRatioOpensNumeratorCollectionWithExplanation() {
    stubFacts(open("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    StatisticCellData cell = cellOf(board, "客户甲", "defect_fix_rate");
    assertThat(cell.displayValue()).isEqualTo("0.00%");
    assertThat(cell.numericValue()).as("分母非零时 0% 是真实值，不是无数据").isEqualTo(0L);

    StatisticDetailResponse numerator = loadDetail(board, "客户甲", "defect_fix_rate", "NUMERATOR");

    assertThat(numerator.collection()).isEqualTo("NUMERATOR");
    assertThat(numerator.records()).isEmpty();
    assertThat(numerator.total()).isZero();
    assertThat(numerator.collections())
        .as("比率的分子与分母集合都要列出，空集合同样要能解释")
        .extracting(com.data.collection.platform.entity.statistics.StatisticDetailCollection::key)
        .containsExactly("DENOMINATOR", "NUMERATOR");
    assertThat(numerator.description()).contains("分子");
  }

  @Test
  void zeroDenominatorRatioKeepsNullCellAndOpensEmptyCollection() {
    stubFacts(open("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    StatisticCellData cell = cellOf(board, "客户甲", "p3_fix_rate");
    assertThat(cell.numericValue()).as("零分母不可计算，必须为 null 而不是 0").isNull();
    assertThat(cell.drilldown()).as("零分母仍然可以打开，由明细解释空集合").isTrue();

    StatisticDetailResponse denominator = loadDetail(board, "客户甲", "p3_fix_rate", "DENOMINATOR");

    assertThat(denominator.records()).isEmpty();
    assertThat(denominator.total()).isZero();
    assertThat(denominator.description()).contains("分母");
  }

  @Test
  void noCycleSampleAndRealZeroHourSampleAreDistinguishable() {
    stubFacts(open("客户甲", 1L), zeroHourResponseSample("客户乙", 2L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    StatisticCellData noSample = cellOf(board, "客户甲", "response_cycle_hours");
    StatisticCellData zeroHour = cellOf(board, "客户乙", "response_cycle_hours");

    assertThat(noSample.displayValue()).isEqualTo("0");
    assertThat(noSample.detailParams()).containsEntry("sampleCount", "0");
    assertThat(zeroHour.displayValue()).isEqualTo("0");
    assertThat(zeroHour.detailParams())
        .as("真实 0 小时样本与无样本显示同为 0，必须靠有效样本数区分")
        .containsEntry("sampleCount", "1");

    StatisticDetailResponse noSampleDetail =
        loadDetail(board, "客户甲", "response_cycle_hours", "SAMPLE");
    StatisticDetailResponse zeroHourDetail =
        loadDetail(board, "客户乙", "response_cycle_hours", "SAMPLE");

    assertThat(noSampleDetail.records()).isEmpty();
    assertThat(noSampleDetail.collections())
        .extracting(com.data.collection.platform.entity.statistics.StatisticDetailCollection::key)
        .containsExactly("SAMPLE");
    assertThat(noSampleDetail.description()).contains("无样本");
    assertThat(zeroHourDetail.total()).isEqualTo(1);
  }

  @Test
  void detailPaginationUnionEqualsMetricContributionSet() {
    stubFacts(
        fixed("客户甲", 1L),
        open("客户甲", 2L),
        fixed("客户甲", 3L),
        fixed("客户乙", 4L),
        open("客户乙", 5L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    StatisticCellData cell = cellOf(board, "客户甲", "defect_total");
    assertThat(cell.numericValue()).as("主表计数与下钻集合必须同源同规模").isEqualTo(3L);

    Set<String> union = new LinkedHashSet<>();
    long total = -1;
    for (int page = 1; page <= 3; page++) {
      StatisticDetailResponse response =
          loadDetail(board, "客户甲", "defect_total", "COUNTED", page, 1);
      total = response.total();
      response.records().forEach(record -> union.add(identityOf(record)));
    }

    assertThat(total).isEqualTo(3);
    assertThat(union)
        .as("逐页取完明细后的身份并集必须恰好等于主表单元格贡献集合（这里以客户甲的 3 条议题为期望）")
        .containsExactlyInAnyOrder(identity("default", 325L, 1L), identity("default", 325L, 2L), identity("default", 325L, 3L))
        .hasSize(3);
  }

  @Test
  void numeratorCollectionIsSubsetOfDenominatorCollection() {
    stubFacts(fixed("客户甲", 1L), open("客户甲", 2L), open("客户甲", 3L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    Set<String> numerator = identities(loadDetail(board, "客户甲", "defect_fix_rate", "NUMERATOR", 1, 50));
    Set<String> denominator = identities(loadDetail(board, "客户甲", "defect_fix_rate", "DENOMINATOR", 1, 50));

    assertThat(denominator).hasSize(3);
    assertThat(numerator).hasSize(1);
    assertThat(denominator).containsAll(numerator);
    assertThat(cellOf(board, "客户甲", "defect_fix_rate").numericValue())
        .as("比率排序值按基点表示 1/3，不是字符串顺序")
        .isEqualTo(3333L);
  }

  @Test
  void countCellsCarryNumericValuesAndTotalRowIsPinnedLast() {
    List<CustomerIssueFact> facts = new ArrayList<>();
    for (long id = 1; id <= 10; id++) {
      facts.add(fixed("客户甲", id));
    }
    for (long id = 11; id <= 15; id++) {
      facts.add(fixed("客户乙", id));
    }
    facts.add(fixed("客户丙", 16L));
    facts.add(fixed("客户丙", 17L));
    stubFacts(facts.toArray(CustomerIssueFact[]::new));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);

    assertThat(board.rows()).hasSize(4);
    StatisticRowData last = board.rows().get(3);
    assertThat(last.rowKey()).as("总计行以固定标记结尾，页面据此钉底").isEqualTo("{\"total\":true}");
    Map<String, Long> counts = new LinkedHashMap<>();
    for (int index = 0; index < 3; index++) {
      StatisticRowData row = board.rows().get(index);
      counts.put(row.rowLabel(), cellOf(row, "defect_total").numericValue());
    }
    assertThat(counts)
        .as("数值排序按真实数量，字符串顺序会把 10 排到 2 之前")
        .containsExactlyInAnyOrderEntriesOf(Map.of("客户甲", 10L, "客户乙", 5L, "客户丙", 2L));
  }

  @Test
  void staleSourceVersionIsRejectedInsteadOfMixingGenerations() {
    stubFacts(fixed("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    Map<String, String> filters = detailFilters(board, "客户甲", "defect_total", "COUNTED");
    filters.put("sourceVersion", "fact-projection:v0:stale");

    assertThatThrownBy(() -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, filters)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("主表来源已发生变化");
  }

  @Test
  void missingSourceVersionIsRejected() {
    stubFacts(fixed("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    Map<String, String> filters = detailFilters(board, "客户甲", "defect_total", "COUNTED");
    filters.remove("sourceVersion");

    assertThatThrownBy(() -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, filters)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("缺少主表来源版本");
  }

  @Test
  void unsupportedPopulationForMetricIsRejected() {
    stubFacts(fixed("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    Map<String, String> numeratorOnCount = detailFilters(board, "客户甲", "defect_total", "NUMERATOR");

    assertThatThrownBy(
            () -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, numeratorOnCount)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持集合类型");

    Map<String, String> unknownPopulation = detailFilters(board, "客户甲", "defect_total", "BOGUS");

    assertThatThrownBy(
            () -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, unknownPopulation)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的集合类型");
  }

  @Test
  void tamperedRowIdentityIsRejected() {
    stubFacts(fixed("客户甲", 1L), fixed("客户乙", 2L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);

    Map<String, String> wrongDimension = detailFilters(board, "客户甲", "defect_total", "COUNTED");
    wrongDimension.put("groupBy", "CUSTOMER_MODULE");
    assertThatThrownBy(
            () -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, wrongDimension)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("下钻行维度与当前分组不一致");

    Map<String, String> outsiderCustomer = detailFilters(board, "客户甲", "defect_total", "COUNTED");
    outsiderCustomer.put(
        "rowKey",
        "{\"groupBy\":\"CUSTOMER\",\"customer\":{\"kind\":\"VALUE\",\"value\":\"客户丙\"},"
            + "\"dimension\":null,\"total\":false}");
    assertThatThrownBy(
            () -> service.loadDetail(detailRequest(board, "客户甲", "defect_total", 1, 10, outsiderCustomer)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("下钻行客户不在当前统计范围内");

    StatisticBoardResponse moduleBoard = loadBoard(BUSINESS_DATE, "CUSTOMER_MODULE");
    Map<String, String> outsiderDimension = detailFilters(moduleBoard, "客户甲", "defect_total", "COUNTED");
    outsiderDimension.put(
        "rowKey",
        "{\"groupBy\":\"CUSTOMER_MODULE\",\"customer\":{\"kind\":\"VALUE\",\"value\":\"客户甲\"},"
            + "\"dimension\":{\"kind\":\"VALUE\",\"value\":\"模块Z\"},\"total\":false}");
    assertThatThrownBy(
            () ->
                service.loadDetail(
                    detailRequest(moduleBoard, "客户甲", "defect_total", 1, 10, outsiderDimension)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("下钻行模块/功能不在当前统计范围内");
  }

  @Test
  void unknownMetricColumnIsRejected() {
    stubFacts(fixed("客户甲", 1L));

    StatisticBoardResponse board = loadBoard(BUSINESS_DATE);
    Map<String, String> filters = detailFilters(board, "客户甲", "defect_total", "COUNTED");

    assertThatThrownBy(
            () ->
                service.loadDetail(
                    new StatisticDetailRequest(
                        CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
                        filters.get("rowKey"),
                        "defect_total_v2",
                        1,
                        10,
                        "updatedAt",
                        "descending",
                        filters)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知的客户问题统计指标列");
  }

  private void stubFacts(CustomerIssueFact... facts) {
    lenient().when(factQueryService.load(any())).thenReturn(List.of(facts));
  }

  private StatisticBoardResponse loadBoard(String businessDate) {
    return loadBoard(businessDate, "CUSTOMER");
  }

  private StatisticBoardResponse loadBoard(String businessDate, String groupBy) {
    return service.loadBoard(
        Map.of("businessDate", businessDate, "groupBy", groupBy, "sortField", "defect_total"));
  }

  private StatisticDetailResponse loadDetail(
      StatisticBoardResponse board, String rowLabel, String columnKey, String population) {
    return loadDetail(board, rowLabel, columnKey, population, 1, 10);
  }

  private StatisticDetailResponse loadDetail(
      StatisticBoardResponse board,
      String rowLabel,
      String columnKey,
      String population,
      int page,
      int size) {
    return service.loadDetail(
        detailRequest(board, rowLabel, columnKey, page, size, detailFilters(board, rowLabel, columnKey, population)));
  }

  private StatisticDetailRequest detailRequest(
      StatisticBoardResponse board,
      String rowLabel,
      String columnKey,
      int page,
      int size,
      Map<String, String> filters) {
    return new StatisticDetailRequest(
        CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
        filters.get("rowKey"),
        columnKey,
        page,
        size,
        "updatedAt",
        "descending",
        filters);
  }

  private Map<String, String> detailFilters(
      StatisticBoardResponse board, String rowLabel, String columnKey, String population) {
    StatisticCellData cell = cellOf(board, rowLabel, columnKey);
    Map<String, String> filters = new LinkedHashMap<>(cell.detailParams());
    filters.put("population", population);
    return filters;
  }

  private StatisticCellData cellOf(StatisticBoardResponse board, String rowLabel, String columnKey) {
    return cellOf(rowOf(board, rowLabel), columnKey);
  }

  private static StatisticCellData cellOf(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到单元格: " + row.rowLabel() + " / " + columnKey));
  }

  private static StatisticRowData rowOf(StatisticBoardResponse board, String rowLabel) {
    return board.rows().stream()
        .filter(row -> rowLabel.equals(row.rowLabel()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到下钻行: " + rowLabel));
  }

  private static Set<String> identities(StatisticDetailResponse response) {
    Set<String> result = new LinkedHashSet<>();
    response.records().forEach(record -> result.add(identityOf(record)));
    return result;
  }

  /** 明细记录的可见身份：来源实例 + 项目 + 议题 IID（夹具中与 issueId 一一对应）。 */
  private static String identityOf(Map<String, Object> record) {
    return identity(
        String.valueOf(record.get("sourceInstance")),
        ((Number) record.get("projectId")).longValue(),
        ((Number) record.get("issueIid")).longValue());
  }

  private static String identity(String sourceInstance, long projectId, long issueIid) {
    return sourceInstance + "|" + projectId + "|" + issueIid;
  }

  private static CustomerIssueFact fixed(String customer, long issueIid) {
    return fact(customer, issueIid, "已修复", "LEVEL2", "P2").build();
  }

  private static CustomerIssueFact open(String customer, long issueIid) {
    return fact(customer, issueIid, "处理中", "LEVEL2", "P2").build();
  }

  private static CustomerIssueFact zeroHourResponseSample(String customer, long issueIid) {
    LocalDateTime baseline = LocalDateTime.of(2026, 9, 1, 10, 0);
    return fact(customer, issueIid, "处理中", "LEVEL2", "P2")
        .createdAt(baseline)
        .researchedAt(baseline)
        .build();
  }

  private static FactBuilder fact(
      String customer, long issueIid, String bugStatus, String severity, String priority) {
    return new FactBuilder(customer, issueIid, bugStatus, severity, priority);
  }

  /** 测试事实构造器：只覆盖下钻契约需要的字段，不引入生产默认值。 */
  private static final class FactBuilder {
    private final String customer;
    private final long issueIid;
    private final String bugStatus;
    private final String severity;
    private final String priority;
    private String module = "模块A";
    private String function = "功能A";
    private LocalDateTime createdAt;
    private LocalDateTime researchTemplateTime;
    private LocalDateTime fixedLabelTime;

    private FactBuilder(
        String customer, long issueIid, String bugStatus, String severity, String priority) {
      this.customer = customer;
      this.issueIid = issueIid;
      this.bugStatus = bugStatus;
      this.severity = severity;
      this.priority = priority;
    }

    FactBuilder module(String value) {
      this.module = value;
      return this;
    }

    FactBuilder function(String value) {
      this.function = value;
      return this;
    }

    FactBuilder createdAt(LocalDateTime value) {
      this.createdAt = value;
      return this;
    }

    FactBuilder researchedAt(LocalDateTime value) {
      this.researchTemplateTime = value;
      return this;
    }

    FactBuilder fixedLabelTime(LocalDateTime value) {
      this.fixedLabelTime = value;
      return this;
    }

    CustomerIssueFact build() {
      return new CustomerIssueFact(
          "GITLAB",
          "default",
          325L,
          issueIid,
          issueIid,
          "项目",
          "标题" + issueIid,
          "opened",
          "CC2026 R3",
          "作者",
          "处理人",
          severity,
          priority,
          bugStatus,
          "功能",
          null,
          List.of(module),
          function,
          null,
          null,
          "",
          "",
          "",
          null,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          createdAt,
          createdAt,
          null,
          researchTemplateTime,
          fixedLabelTime,
          null,
          List.of(customer));
    }
  }
}
