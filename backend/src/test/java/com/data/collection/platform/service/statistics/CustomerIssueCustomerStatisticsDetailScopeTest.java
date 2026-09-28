package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.IssueFactPersistenceService;
import com.data.collection.platform.service.LabelEventHistoryTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审查项 2：主表与下钻必须消费同一份规范化查询上下文（真实 PostgreSQL，真实窄事实读取）。
 *
 * <p>夹具是"客户甲与客户乙各有议题、且有一议题同时属于两家"：先筛客户甲再点总计，明细的身份集合
 * 必须恰好等于主表总计行所代表的议题集合。行身份只是行定位，不能代替客户/模块/功能选择——
 * 少带任何一个选择，明细都会读回比主表更宽的范围。
 *
 * <p>本测试不打桩 {@code CustomerIssueFactQueryService}：筛选是否真正生效只能由真实 SQL 谓词证明，
 * 打桩会让"参数没传"这类缺陷在测试里静默通过。
 */
@SpringBootTest
class CustomerIssueCustomerStatisticsDetailScopeTest {
  private static final String PROBE_SOURCE = "q2chain";
  private static final long PROBE_CONFIG_ID = 990_403L;
  private static final long PROJECT_ID = 325L;
  private static final String BUSINESS_KEY = "q2-chain-group";
  private static final String MILESTONE = "CC2026R9下钻范围核对";
  private static final String CUSTOMER_A = "客户甲";
  private static final String CUSTOMER_B = "客户乙";
  private static final String CUSTOMER_C = "客户丙";
  private static final String MODULE_A = "模块甲";
  private static final String MODULE_B = "模块乙";
  private static final String FUNCTION_A = "功能甲";
  private static final String FUNCTION_B = "功能乙";
  /** 与旧缺失哨兵、旧哨兵文案同形的真实成员名：必须与缺失选择互不顶替。 */
  private static final String MISSING_SENTINEL = "__missing__";
  private static final String MISSING_CUSTOMER_LABEL = "未标注客户";
  private static final String BUSINESS_DATE = "2026-09-22";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private IssueFactPersistenceService issueFactPersistenceService;
  @Autowired private CustomerIssueCustomerStatisticsBoardService service;
  @Autowired private CustomerIssueFactQueryService factQueryService;
  @Autowired private JsonUtils jsonUtils;

  private Long probeCatalogId;

  @BeforeEach
  void setUp() {
    cleanUp();
    insertProbeGroup();
    upsertPublicationState();
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, PROBE_SOURCE);
    // 甲：1 常规缺陷 + 1 已修复 + 1 与乙共享的多客户议题；乙：2 条。
    insertIssue(1L, CUSTOMER_A, MODULE_A, FUNCTION_A, "处理中");
    insertIssue(2L, CUSTOMER_A, MODULE_B, FUNCTION_B, "已修复");
    insertIssue(3L, CUSTOMER_A + "," + CUSTOMER_B, MODULE_A + "," + MODULE_B, FUNCTION_B, "处理中");
    insertIssue(4L, CUSTOMER_B, MODULE_B, FUNCTION_A, "处理中");
    insertIssue(5L, CUSTOMER_B, MODULE_A, FUNCTION_A, "已修复");
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void unfilteredTotalDetailCoversEveryCustomerInScope() {
    StatisticBoardResponse board = loadBoard(Map.of());

    assertThat(rowLabels(board))
        .as("未筛选时两家客户都展开，总计钉在最后一行")
        .endsWith("总计")
        .containsExactlyInAnyOrder(CUSTOMER_A, CUSTOMER_B, "总计");
    StatisticCellData totalCell = metricCell(totalRow(board), "defect_total");
    assertThat(totalCell.numericValue()).isEqualTo(5L);

    assertThat(identities(loadDetail(board, totalRow(board), "defect_total", "COUNTED")))
        .as("未筛选时总计明细就是完整范围的 5 条议题")
        .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
  }

  @Test
  void filteredCustomerIsCarriedIntoTotalDrilldown() {
    StatisticBoardResponse board = loadBoard(
        "CUSTOMER_MODULE", Map.of("customer", CUSTOMER_A, "customerKind", "VALUE"));

    StatisticCellData totalCell = metricCell(totalRow(board), "defect_total");
    assertThat(totalCell.numericValue())
        .as("筛选客户甲后，总计只覆盖含客户甲成员的 3 条议题")
        .isEqualTo(3L);
    assertThat(totalCell.detailParams())
        .as("下钻必须同时带上成员类型与取值，否则客户甲会被解析成不限")
        .containsEntry("customer", CUSTOMER_A)
        .containsEntry("customerKind", "VALUE");
    assertThat(dataRows(board).stream().map(StatisticRowData::rowLabel))
        .as("筛客户甲后，客户乙不能因共享议题重新展开为行")
        .containsOnly(CUSTOMER_A);

    StatisticDetailResponse detail = loadDetail(board, totalRow(board), "defect_total", "COUNTED");

    assertThat(identities(detail))
        .as("筛选客户甲后点总计，明细身份集合必须与主表总计行同源同集合")
        .containsExactlyInAnyOrder(1L, 2L, 3L);
    assertThat(detail.total()).isEqualTo(3L);
    assertVisibleCollectionsMatchRows(board);
  }

  @Test
  void filteredModuleDoesNotExpandUnselectedModuleRowsAndKeepsTotalAndDrilldownsAligned() {
    StatisticBoardResponse board =
        loadBoard("CUSTOMER_MODULE", Map.of("module", MODULE_A, "moduleKind", "VALUE"));

    List<StatisticRowData> visibleRows = dataRows(board);
    assertThat(visibleRows).isNotEmpty();
    assertThat(visibleRows.stream().map(this::rowDimensionLabel))
        .as("筛模块甲后，组合维度只展示模块甲")
        .containsOnly(MODULE_A);
    assertThat(metricCell(totalRow(board), "defect_total").numericValue())
        .as("总计按模块筛选后的完整议题去重计算，不累加客户展开行")
        .isEqualTo(3L);
    assertThat(identities(loadDetail(board, totalRow(board), "defect_total", "COUNTED")))
        .containsExactlyInAnyOrder(1L, 3L, 5L);
    assertVisibleCollectionsMatchRows(board);
  }

  @Test
  void rejectsInvalidKindsOnBoardAndOnTotalDrilldownForEveryMemberDimension() {
    for (String member : List.of("customer", "module", "function")) {
      String kind = member + "Kind";
      assertThatThrownBy(() -> service.loadBoard(Map.of(kind, "FUTURE")))
          .as("主表拒绝 %s 的未知 kind", member)
          .isInstanceOf(IllegalArgumentException.class);
    }

    StatisticBoardResponse board = loadBoard(Map.of());
    StatisticCellData totalCell = metricCell(totalRow(board), "defect_total");
    for (String member : List.of("customer", "module", "function")) {
      Map<String, String> filters = new java.util.LinkedHashMap<>(detailFilters(totalCell, "COUNTED", true));
      filters.put(member + "Kind", "FUTURE");
      StatisticDetailRequest request =
          new StatisticDetailRequest(
              CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
              totalCell.detailParams().get("rowKey"),
              "defect_total",
              1,
              50,
              "updatedAt",
              "descending",
              filters);

      assertThatThrownBy(() -> service.loadDetail(request))
          .as("總計下鑽拒絕 %s 的未知 kind", member)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void moduleAndFunctionSelectionsAlsoNarrowTheDrilldown() {
    StatisticBoardResponse moduleBoard = loadBoard(Map.of("module", MODULE_A, "moduleKind", "VALUE"));
    assertThat(metricCell(totalRow(moduleBoard), "defect_total").numericValue()).isEqualTo(3L);
    StatisticCellData moduleCell = metricCell(totalRow(moduleBoard), "defect_total");
    assertThat(moduleCell.detailParams())
        .containsEntry("module", MODULE_A)
        .containsEntry("moduleKind", "VALUE");
    assertThat(identities(loadDetail(moduleBoard, totalRow(moduleBoard), "defect_total", "COUNTED")))
        .as("模块甲成员命中 1、3、5；多模块议题按成员整体匹配")
        .containsExactlyInAnyOrder(1L, 3L, 5L);

    StatisticBoardResponse functionBoard = loadBoard(Map.of("function", FUNCTION_B, "functionKind", "VALUE"));
    assertThat(identities(loadDetail(functionBoard, totalRow(functionBoard), "defect_total", "COUNTED")))
        .as("功能选择同样必须下传到明细")
        .containsExactlyInAnyOrder(2L, 3L);
  }

  @Test
  void missingCustomerSelectionIsCarriedIntoTotalDrilldown() {
    // 议题 6 没有任何客户成员：它只属于"未标注客户"行与筛选后的总计。
    insertIssue(6L, null, MODULE_B, FUNCTION_B, "处理中");

    StatisticBoardResponse board = loadBoard(Map.of("customerKind", "MISSING"));

    assertThat(rowLabels(board)).containsExactly("未标注客户", "总计");
    StatisticCellData totalCell = metricCell(totalRow(board), "defect_total");
    assertThat(totalCell.detailParams())
        .as("缺失选择下传的是类型参数，而不是一个可能被当成真实成员名的取值")
        .containsEntry("customerKind", "MISSING")
        .doesNotContainKey("customer");
    assertThat(identities(loadDetail(board, totalRow(board), "defect_total", "COUNTED")))
        .containsExactly(6L);
  }

  @Test
  void missingModuleAndFunctionSelectionsAreCarriedIntoTotalDrilldown() {
    // 议题 6 没有模块也没有功能：按客户分组时它只被"缺失模块/缺失功能"筛选收进来。
    insertIssue(6L, CUSTOMER_A, null, null, "处理中");

    StatisticBoardResponse moduleBoard = loadBoard(Map.of("moduleKind", "MISSING"));
    StatisticCellData moduleTotal = metricCell(totalRow(moduleBoard), "defect_total");
    assertThat(moduleTotal.numericValue())
        .as("缺失模块选择必须真正生效：范围内只有议题 6 没有模块")
        .isEqualTo(1L);
    assertThat(moduleTotal.detailParams()).containsEntry("moduleKind", "MISSING");
    assertThat(identities(loadDetail(moduleBoard, totalRow(moduleBoard), "defect_total", "COUNTED")))
        .as("缺失模块选择必须下传到明细，否则总计会读回全部模块的议题")
        .containsExactly(6L);

    StatisticBoardResponse functionBoard = loadBoard(Map.of("functionKind", "MISSING"));
    StatisticCellData functionTotal = metricCell(totalRow(functionBoard), "defect_total");
    assertThat(functionTotal.numericValue())
        .as("缺失功能选择必须真正生效：范围内只有议题 6 没有功能")
        .isEqualTo(1L);
    assertThat(functionTotal.detailParams()).containsEntry("functionKind", "MISSING");
    assertThat(identities(loadDetail(functionBoard, totalRow(functionBoard), "defect_total", "COUNTED")))
        .as("缺失功能选择同样必须下传到明细")
        .containsExactly(6L);
  }

  /**
   * 真实成员名与缺失哨兵/缺失文案同形时，筛选与下钻必须按真实成员走。
   *
   * <p>议题 6 真实客户名就是 {@code __missing__}（旧契约会把它跳过），议题 7 真实客户名就是页面缺失
   * 文案"未标注客户"；两者都必须能按精确名筛出来，并且与真正的缺失选择互不覆盖。
   */
  @Test
  void realMembersNamedLikeTheMissingSentinelOrWordingStaySelectableAndDistinct() {
    insertIssue(6L, MISSING_SENTINEL, MODULE_B, FUNCTION_B, "处理中");
    insertIssue(7L, MISSING_CUSTOMER_LABEL, MODULE_A, FUNCTION_A, "处理中");
    insertIssue(8L, null, MODULE_A, FUNCTION_A, "处理中");

    StatisticBoardResponse sentinelBoard =
        loadBoard(Map.of("customer", MISSING_SENTINEL, "customerKind", "VALUE"));
    assertThat(rowLabels(sentinelBoard)).containsExactly(MISSING_SENTINEL, "总计");
    assertThat(identities(loadDetail(sentinelBoard, totalRow(sentinelBoard), "defect_total", "COUNTED")))
        .as("真实名等于哨兵的成员必须能按精确名筛选，而不是被折算成缺失或跳过")
        .containsExactly(6L);

    StatisticBoardResponse wordingBoard =
        loadBoard(Map.of("customer", MISSING_CUSTOMER_LABEL, "customerKind", "VALUE"));
    assertThat(rowLabels(wordingBoard)).containsExactly(MISSING_CUSTOMER_LABEL, "总计");
    assertThat(identities(loadDetail(wordingBoard, totalRow(wordingBoard), "defect_total", "COUNTED")))
        .as("真实名等于缺失文案的成员与缺失是两个不同选择，各自命中自己的议题集合")
        .containsExactly(7L);

    StatisticBoardResponse missingBoard = loadBoard(Map.of("customerKind", "MISSING"));
    assertThat(rowLabels(missingBoard)).containsExactly("未标注客户", "总计");
    assertThat(identities(loadDetail(missingBoard, totalRow(missingBoard), "defect_total", "COUNTED")))
        .as("真正的缺失选择只覆盖没有客户成员的议题，两个同形真实成员都不得进入")
        .containsExactly(8L);
  }

  @Test
  void drilldownWithoutMainTableRowIdentityIsRejected() {
    StatisticBoardResponse board = loadBoard(Map.of("customer", CUSTOMER_A, "customerKind", "VALUE"));
    StatisticCellData cell = metricCell(totalRow(board), "defect_total");

    StatisticDetailRequest request =
        new StatisticDetailRequest(
            CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
            cell.detailParams().get("rowKey"),
            "defect_total",
            1,
            10,
            "updatedAt",
            "descending",
            detailFilters(cell, "COUNTED", false));

    assertThatThrownBy(() -> service.loadDetail(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("缺少主表来源版本");
  }

  @Test
  void tamperedCustomerOutsideScopeIsRejectedForFilteredBoard() {
    insertIssue(7L, CUSTOMER_C, MODULE_A, FUNCTION_A, "处理中");
    StatisticBoardResponse board = loadBoard(Map.of("customer", CUSTOMER_A, "customerKind", "VALUE"));
    StatisticCellData cell = metricCell(totalRow(board), "defect_total");
    Map<String, String> filters = new java.util.LinkedHashMap<>(detailFilters(cell, "COUNTED", true));
    // 客户丙在范围内一条议题都没有：伪造客户行身份必须被范围校验拒绝，而不是返回空集合冒充"没有议题"。
    filters.put(
        "rowKey",
        "{\"groupBy\":\"CUSTOMER\",\"customer\":{\"kind\":\"VALUE\",\"value\":\""
            + CUSTOMER_C
            + "\"},\"dimension\":null,\"total\":false}");

    assertThatThrownBy(
            () ->
                service.loadDetail(
                    new StatisticDetailRequest(
                        CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
                        filters.get("rowKey"),
                        "defect_total",
                        1,
                        10,
                        "updatedAt",
                        "descending",
                        filters)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("下钻行客户不在当前统计范围内");
  }

  /** 同一批事实的窄事实读取必须与页面口径一致：这是"候选来自同一事实源"的地基。 */
  @Test
  void narrowFactReadAgreesWithBoardScope() {
    List<CustomerIssueFact> all = factQueryService.load(baseRequest("ALL", null, "ALL", null, "ALL", null));
    assertThat(all).hasSize(5);

    List<CustomerIssueFact> onlyA =
        factQueryService.load(baseRequest("VALUE", CUSTOMER_A, "ALL", null, "ALL", null));
    assertThat(onlyA).extracting(CustomerIssueFact::issueIid).containsExactlyInAnyOrder(1L, 2L, 3L);

    List<CustomerIssueFact> missingCustomer =
        factQueryService.load(baseRequest("MISSING", null, "ALL", null, "ALL", null));
    assertThat(missingCustomer).as("本夹具每条议题都有客户成员").isEmpty();

    List<CustomerIssueFact> moduleB =
        factQueryService.load(baseRequest("ALL", null, "VALUE", MODULE_B, "ALL", null));
    assertThat(moduleB).extracting(CustomerIssueFact::issueIid).containsExactlyInAnyOrder(2L, 3L, 4L);
  }

  private List<String> rowLabels(StatisticBoardResponse board) {
    return board.rows().stream().map(StatisticRowData::rowLabel).toList();
  }

  private List<StatisticRowData> dataRows(StatisticBoardResponse board) {
    return board.rows().stream().filter(row -> !"总计".equals(row.rowLabel())).toList();
  }

  private String rowDimensionLabel(StatisticRowData row) {
    return row.cells().get(0).displayValue();
  }

  private void assertVisibleCollectionsMatchRows(StatisticBoardResponse board) {
    for (StatisticRowData row : java.util.stream.Stream.concat(
        dataRows(board).stream(), java.util.stream.Stream.of(totalRow(board))).toList()) {
      StatisticCellData countCell = metricCell(row, "defect_total");
      StatisticDetailResponse counted = loadDetail(board, row, "defect_total", "COUNTED");
      assertThat(counted.total()).isEqualTo(countCell.numericValue());

      StatisticDetailResponse denominator = loadDetail(board, row, "defect_fix_rate", "DENOMINATOR");
      assertThat(identities(denominator))
          .as("修复率分母集合与缺陷数量集合相同")
          .containsExactlyInAnyOrderElementsOf(identities(counted));
      StatisticDetailResponse numerator = loadDetail(board, row, "defect_fix_rate", "NUMERATOR");
      assertThat(numerator.total()).isEqualTo(metricCell(row, "defect_fixed").numericValue());

      StatisticCellData cycleCell = metricCell(row, "resolution_cycle_days");
      StatisticDetailResponse samples = loadDetail(board, row, "resolution_cycle_days", "SAMPLE");
      assertThat(samples.total()).isEqualTo(Long.parseLong(cycleCell.detailParams().get("sampleCount")));
    }
  }

  private StatisticRowData rowByLabel(StatisticBoardResponse board, String label) {
    return board.rows().stream()
        .filter(row -> label.equals(row.rowLabel()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到行: " + label));
  }

  private StatisticRowData totalRow(StatisticBoardResponse board) {
    return board.rows().get(board.rows().size() - 1);
  }

  private Set<Long> identities(StatisticDetailResponse detail) {
    Set<Long> ids = new LinkedHashSet<>();
    detail.records().forEach(record -> ids.add(((Number) record.get("issueIid")).longValue()));
    return ids;
  }

  private StatisticBoardResponse loadBoard(Map<String, String> control) {
    return loadBoard("CUSTOMER", control);
  }

  private StatisticBoardResponse loadBoard(String groupBy, Map<String, String> control) {
    java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
    filters.put("businessDate", BUSINESS_DATE);
    filters.put("groupBy", groupBy);
    filters.put("sourceInstance", PROBE_SOURCE);
    filters.put("filterGroup", milestoneFilterGroupJson());
    filters.putAll(control);
    return service.loadBoard(filters);
  }

  private StatisticDetailResponse loadDetail(
      StatisticBoardResponse board, StatisticRowData row, String columnKey, String population) {
    StatisticCellData cell = metricCell(row, columnKey);
    return service.loadDetail(
        new StatisticDetailRequest(
            CustomerIssueCustomerStatisticsBoardService.BOARD_KEY,
            cell.detailParams().get("rowKey"),
            columnKey,
            1,
            50,
            "updatedAt",
            "descending",
            detailFilters(cell, population, true)));
  }

  private Map<String, String> detailFilters(
      StatisticCellData cell, String population, boolean withSourceVersion) {
    java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>(cell.detailParams());
    if (!withSourceVersion) {
      filters.remove("sourceVersion");
    }
    filters.put("population", population);
    filters.put("sourceInstance", PROBE_SOURCE);
    filters.put("filterGroup", milestoneFilterGroupJson());
    return filters;
  }

  private String milestoneFilterGroupJson() {
    StatisticFilterGroup group =
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition(
                    CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD, "eq", BUSINESS_KEY, null)));
    return jsonUtils.toJson(group);
  }

  private static StatisticCellData metricCell(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到指标单元格: " + columnKey));
  }

  private CustomerIssueFactQueryService.FactScopeRequest baseRequest(
      String customerKind,
      String customerValue,
      String moduleKind,
      String moduleValue,
      String functionKind,
      String functionValue) {
    return new CustomerIssueFactQueryService.FactScopeRequest(
        PROBE_SOURCE,
        List.of(MILESTONE),
        selection(customerKind, customerValue),
        selection(moduleKind, moduleValue),
        selection(functionKind, functionValue));
  }

  private static CustomerIssueFactQueryService.MemberSelection selection(String kind, String value) {
    return switch (kind) {
      case "ALL" -> CustomerIssueFactQueryService.MemberSelection.all();
      case "MISSING" -> CustomerIssueFactQueryService.MemberSelection.missing();
      default -> CustomerIssueFactQueryService.MemberSelection.of(value);
    };
  }

  private void insertProbeGroup() {
    long catalogId = ensureProbeCatalog();
    jdbcTemplate.update(
        """
        insert into issue_scope_groups(id, catalog_id, business_key, display_name, sort_order, enabled)
        values (?, ?, ?, '下钻范围核对', 9002, true)
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        catalogId,
        BUSINESS_KEY);
    jdbcTemplate.update(
        """
        insert into issue_scope_members(catalog_id, group_id, source_value, display_name, sort_order, enabled)
        values (?, ?, ?, ?, 1, true)
        """,
        catalogId,
        PROBE_CONFIG_ID,
        MILESTONE,
        MILESTONE);
  }

  private Long ensureProbeCatalog() {
    if (probeCatalogId != null) {
      return probeCatalogId;
    }
    List<Long> existing =
        jdbcTemplate.queryForList(
            """
            select id from issue_scope_catalogs
             where project_id = ? and dimension = 'MILESTONE'
             order by id
             limit 1
            """,
            Long.class,
            PROJECT_ID);
    if (!existing.isEmpty()) {
      probeCatalogId = existing.get(0);
      return probeCatalogId;
    }
    probeCatalogId =
        jdbcTemplate.queryForObject(
            """
            insert into issue_scope_catalogs(project_id, project_name, dimension, enabled)
            values (?, '下钻范围核对目录', 'MILESTONE', true)
            returning id
            """,
            Long.class,
            PROJECT_ID);
    return probeCatalogId;
  }

  /** 走 {@code IssueFactMapper.upsert} 写入事实与批量入口写入客户成员，两条生产写路径都被覆盖。 */
  private void insertIssue(long sequence, String customers, String modules, String function, String bugStatus) {
    IssueFact fact = baseFact(sequence, modules, function, bugStatus);
    fact.setResearchTemplateTime(CREATED_AT.plusHours(2));
    if ("已修复".equals(bugStatus)) {
      fact.setFixedLabelTime(CREATED_AT.plusDays(sequence));
    }
    issueFactMapper.upsert(fact);
    if (customers == null) {
      return;
    }
    fact.setCustomerNames(customers);
    issueFactPersistenceService.upsertIssueFacts(List.of(fact));
  }

  private IssueFact baseFact(long sequence, String modules, String function, String bugStatus) {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance(PROBE_SOURCE);
    fact.setIngestChannel("MIRROR");
    fact.setSourceSummary("下钻范围核对");
    fact.setProjectId(PROJECT_ID);
    fact.setProjectName("CC_PRODUCT");
    fact.setIssueId(970_000L + sequence);
    fact.setIssueIid(sequence);
    fact.setTitle("议题" + sequence);
    fact.setIssueState("opened");
    fact.setMilestoneTitle(MILESTONE);
    fact.setAuthorName("提交人");
    fact.setAssigneeName("处理人");
    fact.setModuleNames(modules);
    fact.setFunctionName(function);
    fact.setSeverityLevel("LEVEL2");
    fact.setPriorityLevel("P2");
    fact.setBugStatus(bugStatus);
    fact.setCategory("功能");
    fact.setCreatedAtSource(CREATED_AT);
    fact.setUpdatedAtSource(CREATED_AT);
    fact.setDeleted(false);
    fact.setExcluded(false);
    fact.setFixed("已修复".equals(bugStatus));
    fact.setDelayIssue(false);
    fact.setResponseDelayed(false);
    fact.setResolveDelayed(false);
    fact.setRegression(false);
    fact.setCrash(false);
    fact.setLevel1Other(false);
    fact.setIllegal(false);
    fact.setHasResponse(false);
    fact.setResponseOverdue(false);
    fact.setResolveSlaDays(0);
    fact.setLegacy(false);
    return fact;
  }

  private void upsertPublicationState() {
    long runId = ensureProbeRun();
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
          config_id, source_instance, fact_type, latest_mirror_run_id,
          readiness_status, full_publication_requested, error_message, updated_at)
        values (?, ?, 'ISSUE', ?, 'READY', false, null, current_timestamp)
        on conflict (config_id, source_instance, fact_type) do update
           set latest_mirror_run_id = excluded.latest_mirror_run_id,
               readiness_status = 'READY',
               full_publication_requested = false,
               error_message = null,
               updated_at = current_timestamp
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE,
        runId);
  }

  private long ensureProbeRun() {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(
          id, name, source_instance, source_mode, db_name, db_username, db_password)
        values (?, 'q2chain-probe', ?, 'DOCKER', 'gl_database', 'gl_user', 'gl_password')
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
    List<Long> existing =
        jdbcTemplate.queryForList(
            "select id from sync_runs where run_id = ?", Long.class, "q2chain-run");
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
          run_id, config_id, source_instance, run_type, trigger_type, status, exclusive_scope)
        values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', 'q2chain')
        returning id
        """,
        Long.class,
        "q2chain-run",
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from fact_projection_generations where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from issue_fact where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from issue_fact_customer_members where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from issue_scope_members where group_id = ?", PROBE_CONFIG_ID);
    jdbcTemplate.update("delete from issue_scope_groups where id = ?", PROBE_CONFIG_ID);
    if (probeCatalogId != null && probeCatalogCreated()) {
      jdbcTemplate.update("delete from issue_scope_catalogs where id = ?", probeCatalogId);
    }
    probeCatalogId = null;
    jdbcTemplate.update(
        "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from sync_runs where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", PROBE_CONFIG_ID);
    jdbcTemplate.update(
        "delete from statistic_board_snapshots where board_key = ?",
        CustomerIssueCustomerStatisticsBoardService.BOARD_KEY);
  }

  private boolean probeCatalogCreated() {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_catalogs where id = ? and project_name = ?",
            Long.class,
            probeCatalogId,
            "下钻范围核对目录");
    return count != null && count > 0L;
  }

}
