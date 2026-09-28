package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOption;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOptionGroup;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOptions;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.CustomerIssueFactQueryService.SelectionKind;
import com.data.collection.platform.service.LabelEventHistoryTestSupport;
import com.data.collection.platform.service.IssueFactPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审查项 4（后端侧）与审查项 R5-2：成员候选必须来自同一窄事实在"完整基础范围"上的求解结果，
 * 并且用"类型 + 取值"的无歧义契约表达缺失与精确成员。
 *
 * <p>候选不受当前客户/模块/功能选择影响（筛客户甲后仍可直选客户乙），成员语义与读取侧同源（模块按
 * {@code IssueModuleMembers} 拆分、丢弃空白与"未设定"占位），缺失成员只在确实存在空成员议题时出现。
 * 缺失候选项只携带类型、取值恒为空串，因此真实名等于 {@code __missing__} 或等于缺失文案的成员与缺失
 * 是两个不同候选项，既不会被跳过也不需要"歧义上报"。来源不可判定必须报因而不能返回空列表冒充"没有成员"。
 */
@SpringBootTest
class CustomerIssueCustomerStatisticsControlOptionsTest {
  private static final String PROBE_SOURCE = "q4cand";
  private static final long PROBE_CONFIG_ID = 990_404L;
  private static final long PROJECT_ID = 325L;
  private static final String BUSINESS_KEY = "q4-candidate-group";
  private static final String MILESTONE = "CC2026R9成员候选";
  private static final String OTHER_MILESTONE = "CC2026R9范围外里程碑";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private IssueFactPersistenceService issueFactPersistenceService;
  @Autowired private CustomerIssueCustomerStatisticsBoardService service;
  @Autowired private JsonUtils jsonUtils;

  private Long probeCatalogId;

  @BeforeEach
  void setUp() {
    cleanUp();
    insertProbeGroup();
    upsertPublicationState();
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, PROBE_SOURCE);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void candidatesCoverWholeBaseScopeAndIgnoreCurrentSelections() {
    insertIssue(1L, "客户甲", "模块甲", "功能甲");
    insertIssue(2L, "客户乙", "模块乙,模块丙", "功能乙");
    insertIssue(3L, "客户丙", "未设定模块，模块丁", "功能丙");

    StatisticBoardControlOptions options =
        controlOptions(
            Map.of(
                "customer", "客户甲",
                "customerKind", "VALUE",
                "module", "模块甲",
                "moduleKind", "VALUE"));

    assertThat(options.scopeReadable()).as("来源资格成立时才能给出候选").isTrue();
    assertThat(options.scopeKey()).isEqualTo(BUSINESS_KEY);
    assertThat(options.sourceVersion())
        .as("候选带来源代际，前端据此判断候选是否仍适用于当前主表")
        .isNotBlank();
    assertThat(values(options, "customer"))
        .as("候选是完整基础范围的成员集合，不随当前客户选择收缩")
        .containsExactlyInAnyOrder("客户甲", "客户乙", "客户丙");
    assertThat(values(options, "module"))
        .as("模块按成员拆分且丢弃“未设定”占位，与读取侧同一成员语义")
        .containsExactlyInAnyOrder("模块甲", "模块乙", "模块丙", "模块丁");
    assertThat(values(options, "function"))
        .containsExactlyInAnyOrder("功能甲", "功能乙", "功能丙");
    assertThat(missingOption(options, "customer"))
        .as("每条议题都有客户成员时不得出现缺失候选项")
        .isNull();
  }

  @Test
  void missingMembersAppearOnlyWhenScopeActuallyHasThem() {
    insertIssue(1L, "客户甲", "模块甲", "功能甲");
    insertIssue(2L, null, "  ", "   ");
    insertIssue(3L, "客户乙", "未设定", null);

    StatisticBoardControlOptions options = controlOptions(Map.of());

    assertThat(values(options, "customer")).containsExactlyInAnyOrder("客户甲", "客户乙");
    assertThat(missingLabel(options, "customer"))
        .as("议题 2 没有客户成员，缺失候选项才出现")
        .isEqualTo("未标注客户");
    assertThat(values(options, "module"))
        .as("纯空白与“未设定”占位都不构成真实模块成员")
        .containsExactly("模块甲");
    assertThat(missingLabel(options, "module")).isEqualTo("未标注模块");
    assertThat(values(options, "function")).containsExactly("功能甲");
    assertThat(missingOption(options, "module"))
        .as("范围内确实有未标注模块的议题，缺失候选项才出现，且它只带类型、不带取值")
        .isNotNull();
    assertThat(missingOption(options, "module").value()).isEmpty();
    assertThat(group(options, "module").options())
        .extracting(option -> option.kind() + ":" + option.value())
        .containsExactlyInAnyOrder("VALUE:模块甲", SelectionKind.MISSING.name() + ":");
    assertThat(missingLabel(options, "function")).isEqualTo("未标注功能");
  }

  /**
   * 真实成员名可以是任意文本：与缺失哨兵同形、与缺失文案同形都不影响它作为普通取值被筛选。
   *
   * <p>缺失候选项只带类型不带取值，因此三条议题下每个维度都恰好是"两个真实成员 + 一个缺失"，
   * 且真实成员与缺失在候选里是两个可分辨的条目。
   */
  @Test
  void realMembersNamedLikeTheMissingSentinelOrWordingStayOrdinaryCandidates() {
    insertIssue(1L, "__missing__", "未标注模块", "未标注功能");
    insertIssue(2L, "未标注客户", "模块甲", null);
    insertIssue(3L, null, null, "功能甲");

    StatisticBoardControlOptions options = controlOptions(Map.of());

    assertThat(group(options, "customer").options())
        .extracting(option -> option.kind() + ":" + option.value())
        .as("真实名 __missing__ 与真实名“未标注客户”都是普通取值，缺失另立一项")
        .containsExactlyInAnyOrder(
            "VALUE:__missing__", "VALUE:未标注客户", SelectionKind.MISSING.name() + ":");
    assertThat(group(options, "module").options())
        .extracting(option -> option.kind() + ":" + option.value())
        .containsExactlyInAnyOrder("VALUE:未标注模块", "VALUE:模块甲", SelectionKind.MISSING.name() + ":");
    assertThat(group(options, "function").options())
        .extracting(option -> option.kind() + ":" + option.value())
        .containsExactlyInAnyOrder("VALUE:未标注功能", "VALUE:功能甲", SelectionKind.MISSING.name() + ":");
    assertThat(missingLabel(options, "customer")).isEqualTo("未标注客户");
    assertThat(values(options, "customer"))
        .as("真实成员与缺失文案同形也必须能按精确名筛出来")
        .containsExactlyInAnyOrder("__missing__", "未标注客户");
  }

  @Test
  void illegalMemberTypeIsRejectedInsteadOfSilentlyFallingBack() {
    insertIssue(1L, "客户甲", "模块甲", "功能甲");

    assertThatThrownBy(() -> controlOptions(Map.of("customerKind", "SOMETHING")))
        .as("非法类型必须被拒绝，不能静默降级成不限或精确成员")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法的成员类型参数");
  }

  @Test
  void sourceWithoutPublicationRecordIsReportedAsUnavailableNotEmptyCandidates() {
    insertIssue(1L, "客户甲", "模块甲", "功能甲");
    jdbcTemplate.update(
        "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        """
        insert into fact_projection_generations(source_instance, fact_type, scope_type, scope_key, generation)
        values (?, 'ISSUE', 'PROJECT', ?, 1)
        """,
        PROBE_SOURCE,
        com.data.collection.platform.service.FactProjectionScopeKeyCodec.project(PROJECT_ID));

    StatisticBoardControlOptions options = controlOptions(Map.of());

    assertThat(options.scopeReadable())
        .as("来源资格失败必须显式可见，不得渲染成“该范围没有成员”")
        .isFalse();
    assertThat(options.reason()).contains("无法确认完整性");
    assertThat(options.groups()).isEmpty();
  }

  @Test
  void otherMilestoneFactsStayOutsideCandidates() {
    insertIssue(1L, "客户甲", "模块甲", "功能甲");
    IssueFact outside = baseFact(9L, "客户丁", "模块丁", "功能丁");
    outside.setMilestoneTitle(OTHER_MILESTONE);
    issueFactMapper.upsert(outside);

    StatisticBoardControlOptions options = controlOptions(Map.of());

    assertThat(values(options, "customer"))
        .as("候选范围就是生效里程碑的完整基础范围，不放大到其它里程碑")
        .containsExactly("客户甲");
    assertThat(missingOption(options, "customer")).isNull();
  }

  private StatisticBoardControlOptions controlOptions(Map<String, String> control) {
    java.util.LinkedHashMap<String, String> filters = new java.util.LinkedHashMap<>();
    filters.put("sourceInstance", PROBE_SOURCE);
    filters.put("businessDate", "2026-09-22");
    filters.put("groupBy", "CUSTOMER");
    filters.put("filterGroup", milestoneFilterGroupJson());
    filters.put("customerKind", "ALL");
    filters.put("moduleKind", "ALL");
    filters.put("functionKind", "ALL");
    filters.putAll(control);
    return service.controlOptions(filters);
  }

  private String milestoneFilterGroupJson() {
    return jsonUtils.toJson(
        new StatisticFilterGroup(
            "AND",
            List.of(
                new StatisticFilterCondition(
                    CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD, "eq", BUSINESS_KEY, null))));
  }

  private StatisticBoardControlOptionGroup group(StatisticBoardControlOptions options, String key) {
    return options.groups().stream()
        .filter(item -> key.equals(item.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("缺少候选组: " + key));
  }

  /** 候选组里的真实成员取值；缺失候选项只带类型，不参与取值比较。 */
  private List<String> values(StatisticBoardControlOptions options, String key) {
    return group(options, key).options().stream()
        .filter(option -> SelectionKind.VALUE.name().equals(option.kind()))
        .map(StatisticBoardControlOption::value)
        .toList();
  }

  private StatisticBoardControlOption missingOption(
      StatisticBoardControlOptions options, String key) {
    return group(options, key).options().stream()
        .filter(option -> SelectionKind.MISSING.name().equals(option.kind()))
        .findFirst()
        .orElse(null);
  }

  private String missingLabel(StatisticBoardControlOptions options, String key) {
    StatisticBoardControlOption missing = missingOption(options, key);
    return missing == null ? null : missing.label();
  }

  private void insertIssue(long sequence, String customers, String modules, String function) {
    IssueFact fact = baseFact(sequence, customers, modules, function);
    issueFactMapper.upsert(fact);
    if (customers == null) {
      return;
    }
    fact.setCustomerNames(customers);
    issueFactPersistenceService.upsertIssueFacts(List.of(fact));
  }

  private IssueFact baseFact(long sequence, String customers, String modules, String function) {
    IssueFact fact = new IssueFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance(PROBE_SOURCE);
    fact.setIngestChannel("MIRROR");
    fact.setSourceSummary("成员候选用例");
    fact.setProjectId(PROJECT_ID);
    fact.setProjectName("CC_PRODUCT");
    fact.setIssueId(980_000L + sequence);
    fact.setIssueIid(sequence);
    fact.setTitle("候选议题" + sequence);
    fact.setIssueState("opened");
    fact.setMilestoneTitle(MILESTONE);
    fact.setModuleNames(modules);
    fact.setFunctionName(function);
    fact.setSeverityLevel("LEVEL2");
    fact.setPriorityLevel("P2");
    fact.setBugStatus("处理中");
    fact.setCategory("功能");
    fact.setCreatedAtSource(CREATED_AT);
    fact.setUpdatedAtSource(CREATED_AT);
    fact.setDeleted(false);
    fact.setExcluded(false);
    fact.setFixed(false);
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

  private void insertProbeGroup() {
    long catalogId = ensureProbeCatalog();
    jdbcTemplate.update(
        """
        insert into issue_scope_groups(id, catalog_id, business_key, display_name, sort_order, enabled)
        values (?, ?, ?, '成员候选范围', 9003, true)
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
            values (?, '成员候选目录', 'MILESTONE', true)
            returning id
            """,
            Long.class,
            PROJECT_ID);
    return probeCatalogId;
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
        values (?, 'q4cand-probe', ?, 'DOCKER', 'gl_database', 'gl_user', 'gl_password')
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
    List<Long> existing =
        jdbcTemplate.queryForList(
            "select id from sync_runs where run_id = ?", Long.class, "q4cand-run");
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
          run_id, config_id, source_instance, run_type, trigger_type, status, exclusive_scope)
        values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', 'q4cand')
        returning id
        """,
        Long.class,
        "q4cand-run",
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
    jdbcTemplate.update("delete from issue_scope_catalogs where project_name = '成员候选目录'");
    probeCatalogId = null;
    jdbcTemplate.update(
        "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from sync_runs where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", PROBE_CONFIG_ID);
    jdbcTemplate.update(
        "delete from statistic_board_snapshots where board_key = ?",
        CustomerIssueCustomerStatisticsBoardService.BOARD_KEY);
  }
}
