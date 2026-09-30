package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOption;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOptionGroup;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOptions;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticDetailCollection;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import com.data.collection.platform.service.CustomerIssueFactQueryService.SelectionKind;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.statistics.CustomerIssueStatisticsCalculator.FactFlags;
import com.data.collection.platform.service.statistics.engine.StatisticFieldDescriptor;
import com.data.collection.platform.service.statistics.engine.StatisticFilterEngine;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 客户问题客户维度统计板。
 *
 * <p>一次请求内冻结来源、里程碑成员与客户/模块/功能选择，读取一次窄事实结果，随后全部指标在内存中
 * 从同一批事实计算：按客户、客户×模块、客户×功能三种行维度，总计行在未展开的去重集合上计算。
 *
 * <p>本板不复制记录页分页查询，也不把原始议题加载到浏览器；下钻在主表同一规格的集合上切片。
 */
@Service
public class CustomerIssueCustomerStatisticsBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport,
        RuleExplainableStatisticBoardSupport,
        StatisticBoardSnapshotRefresher {

  static final String BOARD_KEY = "customer-issue-customer-statistics";
  static final String RULE_VERSION = "customer-issue-customer-statistics@2026-09-28-v2";
  static final String GROUP_BY_PARAM = "groupBy";
  static final String CUSTOMER_PARAM = "customer";
  static final String MODULE_PARAM = "module";
  static final String FUNCTION_PARAM = "function";
  /** 成员类型参数：VALUE / MISSING；两者都不写表示不限（ALL）。 */
  static final String CUSTOMER_KIND_PARAM = "customerKind";
  static final String MODULE_KIND_PARAM = "moduleKind";
  static final String FUNCTION_KIND_PARAM = "functionKind";
  static final String BUSINESS_DATE_PARAM = "businessDate";
  static final String SOURCE_INSTANCE_PARAM = StatisticBoardReadScopeResolver.SOURCE_INSTANCE_PARAM;
  static final String SOURCE_VERSION_PARAM = "sourceVersion";
  static final String POPULATION_PARAM = "population";

  static final String MISSING_CUSTOMER_LABEL = "未标注客户";
  static final String MISSING_MODULE_LABEL = "未标注模块";
  static final String MISSING_FUNCTION_LABEL = "未标注功能";

  private static final String TOTAL_ROW_KEY = "{\"total\":true}";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final long CUSTOMER_PROJECT_ID = CustomerIssueFactQueryService.CC_PRODUCT_PROJECT_ID;

  private final JsonUtils jsonUtils;
  private final CustomerIssueFactQueryService factQueryService;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;
  private final StatisticBoardReadScopeResolver readScopeResolver;
  private final IssueFactBoardRuntimeSupport runtimeSupport;
  private final StatisticIssueLinkSupport issueLinkSupport;

  public CustomerIssueCustomerStatisticsBoardService(
      JsonUtils jsonUtils,
      CustomerIssueFactQueryService factQueryService,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory,
      StatisticBoardReadScopeResolver readScopeResolver,
      IssueFactBoardRuntimeSupport runtimeSupport,
      StatisticIssueLinkSupport issueLinkSupport) {
    super(jsonUtils);
    this.jsonUtils = jsonUtils;
    this.factQueryService = factQueryService;
    this.milestoneCatalogService = milestoneCatalogService;
    this.snapshotService = snapshotService;
    this.snapshotRequestFactory = snapshotRequestFactory;
    this.readScopeResolver = readScopeResolver;
    this.runtimeSupport = runtimeSupport;
    this.issueLinkSupport = issueLinkSupport;
  }

  /** 行维度。 */
  enum GroupBy {
    CUSTOMER,
    CUSTOMER_MODULE,
    CUSTOMER_FUNCTION;

    static GroupBy parse(String raw) {
      String normalized = trimOrNull(raw);
      if (normalized == null) {
        return CUSTOMER;
      }
      try {
        return valueOf(normalized.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("不支持的客户问题统计行维度: " + raw);
      }
    }

    String dimensionColumnKey() {
      return switch (this) {
        case CUSTOMER -> null;
        case CUSTOMER_MODULE -> CustomerIssueStatisticMetricCatalog.DIMENSION_COLUMN_MODULE;
        case CUSTOMER_FUNCTION -> CustomerIssueStatisticMetricCatalog.DIMENSION_COLUMN_FUNCTION;
      };
    }

    String dimensionLabel() {
      return switch (this) {
        case CUSTOMER -> null;
        case CUSTOMER_MODULE -> "模块";
        case CUSTOMER_FUNCTION -> "功能";
      };
    }

    String missingLabel() {
      return switch (this) {
        case CUSTOMER -> MISSING_CUSTOMER_LABEL;
        case CUSTOMER_MODULE -> MISSING_MODULE_LABEL;
        case CUSTOMER_FUNCTION -> MISSING_FUNCTION_LABEL;
      };
    }
  }

  /** 行身份：指标之外的结构化维度选择，不按显示名拼接。 */
  record RowKeySpec(GroupBy groupBy, RowMember customer, RowMember dimension, boolean total) {}

  /** 行成员：MISSING 或 VALUE（行内不出现 ALL）。 */
  record RowMember(String kind, String value) {
    static RowMember missing() {
      return new RowMember(SelectionKind.MISSING.name(), null);
    }

    static RowMember value(String value) {
      return new RowMember(SelectionKind.VALUE.name(), value);
    }

    boolean isMissing() {
      return SelectionKind.MISSING.name().equals(kind);
    }
  }

  /** 一次请求的控制参数，与业务筛选条件分离。 */
  record ControlParams(
      GroupBy groupBy,
      MemberSelection customer,
      MemberSelection module,
      MemberSelection function,
      LocalDate businessDate,
      String sourceInstance) {}

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return buildDefinition(GroupBy.CUSTOMER);
  }

  private StatisticBoardDefinition buildDefinition(GroupBy groupBy) {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题统计",
        "按客户汇总客户问题范围内的缺陷、建议类、优先级、延期与响应效率指标。",
        "",
        "",
        "客户名称",
        filterFields(),
        columnGroups(groupBy),
        detailColumns(),
        20,
        "未找到可用的客户问题里程碑范围时请先维护里程碑目录；当前筛选范围内也可能没有数据。");
  }

  private List<StatisticColumnGroup> columnGroups(GroupBy groupBy) {
    List<StatisticColumnGroup> groups = new ArrayList<>();
    if (groupBy.dimensionColumnKey() != null) {
      groups.add(
          new StatisticColumnGroup(
              "row-dimension",
              groupBy.dimensionLabel(),
              List.of(new StatisticColumnLeaf(
                  groupBy.dimensionColumnKey(), groupBy.dimensionLabel(), false, "text"))));
    }
    groups.addAll(List.of(
        new StatisticColumnGroup("overall", "整体", List.of(
            leaf("defect_total", "缺陷数", CustomerIssueStatisticMetricCatalog.ALWAYS_BASE_TOOLTIP),
            leaf("defect_fixed", "已修复", null),
            leaf("defect_unfixed", "未修复", "未修复 = 缺陷数 − 已修复，不等于 GitLab 未关闭数。"),
            leaf("defect_applied_delay", "申请延期",
                "申请延期是独立状态计数，不是与已修复、未修复互斥的第三桶。"),
            leaf("defect_fix_rate", "整体修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP),
            leaf("defect_delay_ratio", "延期缺陷占比", CustomerIssueStatisticMetricCatalog.DELAY_RATIO_TOOLTIP))),
        new StatisticColumnGroup("level1", "一级缺陷", List.of(
            leaf("level1_back", "回退", null),
            leaf("level1_hang", "挂机", null),
            leaf("level1_other", "其他", "一级回退、挂机、其他可同时命中，三者不互斥。"),
            leaf("level1_total", "缺陷总数", null),
            leaf("level1_fixed", "已修复", null),
            leaf("level1_unfixed", "未修复", null),
            leaf("level1_applied_delay", "申请延期", null),
            leaf("level1_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("level2", "二级缺陷", List.of(
            leaf("level2_total", "缺陷数", null),
            leaf("level2_fixed", "已修复", null),
            leaf("level2_unfixed", "未修复", null),
            leaf("level2_applied_delay", "申请延期", null),
            leaf("level2_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("level3", "三级缺陷", List.of(
            leaf("level3_total", "缺陷数", null),
            leaf("level3_fixed", "已修复", null),
            leaf("level3_unfixed", "未修复", null),
            leaf("level3_applied_delay", "申请延期", null),
            leaf("level3_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("suggestion", "建议类", List.of(
            leaf("suggestion_total", "建议类数量", CustomerIssueStatisticMetricCatalog.SUGGESTION_TOOLTIP))),
        new StatisticColumnGroup("p1", "P1", List.of(
            leaf("p1_total", "缺陷数", "缺失优先级不归入 P1/P2/P3。"),
            leaf("p1_fixed", "已修复", null),
            leaf("p1_unfixed", "未修复", null),
            leaf("p1_applied_delay", "申请延期", null),
            leaf("p1_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("p2", "P2", List.of(
            leaf("p2_total", "缺陷数", null),
            leaf("p2_fixed", "已修复", null),
            leaf("p2_unfixed", "未修复", null),
            leaf("p2_applied_delay", "申请延期", null),
            leaf("p2_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("p3", "P3", List.of(
            leaf("p3_total", "缺陷数", null),
            leaf("p3_fixed", "已修复", null),
            leaf("p3_unfixed", "未修复", null),
            leaf("p3_applied_delay", "申请延期", null),
            leaf("p3_fix_rate", "缺陷修复率", CustomerIssueStatisticMetricCatalog.RATIO_TOOLTIP))),
        new StatisticColumnGroup("resp_delay", "延期响应缺陷数", List.of(
            leaf("resp_delay_p1", "P1", CustomerIssueStatisticMetricCatalog.DELAY_TOOLTIP),
            leaf("resp_delay_p2", "P2", null),
            leaf("resp_delay_p3", "P3", null),
            leaf("resp_delay_total", "总计",
                "总计为命中任一合法优先级桶的议题数；响应延期与解决延期可同时发生，两个总计不能相加当作唯一延期议题数。"))),
        new StatisticColumnGroup("fix_delay", "解决延期缺陷数", List.of(
            leaf("fix_delay_p1", "P1", null),
            leaf("fix_delay_p2", "P2", null),
            leaf("fix_delay_p3", "P3", null),
            leaf("fix_delay_total", "总计", null))),
        new StatisticColumnGroup("efficiency", "效率", List.of(
            leaf("response_cycle_hours", "响应周期(小时)", CustomerIssueStatisticMetricCatalog.EFFICIENCY_TOOLTIP),
            leaf("resolution_cycle_days", "解决周期(天)", CustomerIssueStatisticMetricCatalog.EFFICIENCY_TOOLTIP)))));
    return List.copyOf(groups);
  }

  private StatisticColumnLeaf leaf(String key, String label, String tooltip) {
    CustomerIssueStatisticMetricCatalog.MetricSpec spec =
        CustomerIssueStatisticMetricCatalog.require(key);
    return new StatisticColumnLeaf(spec.key(), label, true, metricType(spec), tooltip);
  }

  private static String metricType(CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    return switch (spec.type()) {
      case COUNT -> "count";
      case RATIO -> "ratio";
      case DURATION_HOURS, DURATION_DAYS -> "duration";
      case TEXT -> "text";
    };
  }

  private List<com.data.collection.platform.entity.statistics.StatisticDetailColumn> detailColumns() {
    return StatisticIssueDetailColumns.customerIssue(
        "议题标题",
        "模块",
        List.of(
            StatisticIssueDetailColumns.state("议题状态"),
            StatisticIssueDetailColumns.severity("severityLevel", "严重程度", 140),
            new com.data.collection.platform.entity.statistics.StatisticDetailColumn(
                "priorityLevel", "优先级", 120, 120, true, "tag"),
            StatisticIssueDetailColumns.bugStatus(),
            StatisticIssueDetailColumns.delayCause()),
        List.of(
            StatisticIssueDetailColumns.author("议题提交人"),
            StatisticIssueDetailColumns.assignee("议题处理人", 160)),
        List.of(StatisticIssueDetailColumns.milestone("里程碑")));
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService);
  }

  private static String trimOrNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private List<com.data.collection.platform.entity.statistics.StatisticFilterField> filterFields() {
    return List.of(
        StatisticFilterFieldFactory.text(
            CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD, "里程碑", 180),
        StatisticFilterFieldFactory.select(
            "severityLevel",
            "严重程度",
            160,
            IssueDisplayValueSupport.severityFilterOptions(true)),
        StatisticFilterFieldFactory.select(
            "priorityLevel",
            "优先级",
            140,
            List.of(
                new StatisticFilterOption("P1", "P1"),
                new StatisticFilterOption("P2", "P2"),
                new StatisticFilterOption("P3", "P3"))),
        StatisticFilterFieldFactory.text("bugStatus", "测试状态", 180),
        StatisticFilterFieldFactory.text("category", "议题类别", 160),
        StatisticFilterFieldFactory.text("authorName", "议题提交人", 160),
        StatisticFilterFieldFactory.text("assigneeName", "议题处理人", 160),
        StatisticFilterFieldFactory.select(
            "issueState",
            "议题状态",
            140,
            List.of(
                new StatisticFilterOption("未关闭", "open"),
                new StatisticFilterOption("已关闭", "closed"))));
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    ControlParams control = parseControlParams(filters);
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    StatisticBoardDefinition definition = buildDefinition(control.groupBy());
    Map<String, String> snapshotPayload = snapshotPayload(filters, effectiveFilterGroup, control);
    StatisticBoardSnapshotService.SnapshotRequest request =
        snapshotRequest(snapshotPayload, effectiveFilterGroup, definition);
    return snapshotService.readOrRefresh(
        request,
        sourceRead ->
            buildBoardResponse(
                filters, effectiveFilterGroup, control, definition, sourceRead.sourceVersion()));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      ControlParams control,
      StatisticBoardDefinition definition,
      String sourceVersion) {
    long startedAt = System.currentTimeMillis();
    List<FactFlags> flags = loadFlags(effectiveFilterGroup, control);
    // 下钻必须携带主表构建时的来源版本：明细请求据此校验主表与下钻看到的是同一来源代际。
    List<StatisticRowData> rows = buildRows(flags, control.groupBy(), control, sourceVersion);
    return new StatisticBoardResponse(
        definition,
        withoutReservedFilters(filters),
        effectiveFilterGroup,
        rows,
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum(),
            definition.columnGroups().stream()
                .flatMap(group -> group.leafColumns().stream())
                .mapToInt(column -> column.drilldown() ? 1 : 0)
                .sum()),
        null,
        null);
  }

  /** 读取限定范围内的分支事实并编译为每议题一次的资格与状态。 */
  private List<FactFlags> loadFlags(
      StatisticFilterGroup effectiveFilterGroup, ControlParams control) {
    return loadFacts(effectiveFilterGroup, control.sourceInstance(), control)
        .stream()
        .map(fact -> CustomerIssueStatisticsCalculator.toFlags(fact, control.businessDate()))
        .toList();
  }

  /**
   * 按里程碑与用户条件读取范围内事实。
   *
   * <p>成员选择（客户/模块/功能）由 {@code selection} 决定：主表与明细传入当前选择，成员候选传
   * {@link MemberSelection#all()} 以获得同一范围内的完整基础集合。
   */
  private List<CustomerIssueFact> loadFacts(
      StatisticFilterGroup effectiveFilterGroup,
      String sourceInstance,
      ControlParams selection) {
    String milestoneKey = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    List<String> milestoneValues =
        StringUtils.hasText(milestoneKey)
            ? milestoneCatalogService.resolveMilestoneValues(milestoneKey)
            : List.of();
    List<CustomerIssueFact> facts =
        factQueryService.load(
            new FactScopeRequest(
                sourceInstance,
                milestoneValues,
                selection.customer(),
                selection.module(),
                selection.function()));
    Predicate<CustomerIssueFact> filterPredicate =
        StatisticFilterEngine.compile(effectiveFilterGroup, factFilterFields());
    return facts.stream().filter(filterPredicate).toList();
  }

  /**
   * 成员控制条的候选：在同一生效里程碑与来源的完整基础范围内求解。
   *
   * <p>候选只由里程碑、来源与用户条件决定，不受当前客户/模块/功能选择影响；来源不可判定时明确报因，
   * 不返回空列表冒充"确实没有成员"。
   */
  @Override
  public StatisticBoardControlOptions controlOptions(Map<String, String> filters) {
    Map<String, String> safeFilters = filters == null ? Map.of() : filters;
    ControlParams control = parseControlParams(safeFilters);
    StatisticFilterGroup effectiveFilterGroup =
        applyDefaultMilestone(parseFilterGroup(safeFilters, buildDefinition(control.groupBy())));
    String selectedMilestone =
        CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    if (!StringUtils.hasText(selectedMilestone)) {
      return StatisticBoardControlOptions.unavailable(
          "", "当前没有启用的里程碑范围，成员候选暂不可用");
    }
    try {
      return snapshotService.withinConsistentSourceRead(
          readScopePlan(safeFilters, effectiveFilterGroup),
          sourceRead ->
              baseScopeControlOptions(
                  sourceRead.sourceVersion(),
                  selectedMilestone,
                  control,
                  effectiveFilterGroup));
    } catch (BizException error) {
      // 资格失败与"范围内确实没有成员"是两件事，必须区分上报。
      return StatisticBoardControlOptions.unavailable(selectedMilestone, error.getMessage());
    }
  }

  private StatisticBoardControlOptions baseScopeControlOptions(
      String sourceVersion,
      String selectedMilestone,
      ControlParams control,
      StatisticFilterGroup effectiveFilterGroup) {
    List<CustomerIssueFact> facts =
        loadFacts(
            effectiveFilterGroup,
            control.sourceInstance(),
            new ControlParams(
                control.groupBy(),
                MemberSelection.all(),
                MemberSelection.all(),
                MemberSelection.all(),
                control.businessDate(),
                control.sourceInstance()));
    return new StatisticBoardControlOptions(
        selectedMilestone,
        sourceVersion,
        true,
        "",
        List.of(
            optionGroup(CUSTOMER_PARAM, customerOptions(facts), MISSING_CUSTOMER_LABEL),
            optionGroup(MODULE_PARAM, moduleOptions(facts), MISSING_MODULE_LABEL),
            optionGroup(FUNCTION_PARAM, functionOptions(facts), MISSING_FUNCTION_LABEL)));
  }

  private MemberCandidates customerOptions(List<CustomerIssueFact> facts) {
    Set<String> values = new LinkedHashSet<>();
    boolean hasMissing = false;
    for (CustomerIssueFact fact : facts) {
      if (fact.customerNames().isEmpty()) {
        hasMissing = true;
      }
      values.addAll(fact.customerNames());
    }
    return new MemberCandidates(sortedOptions(values), hasMissing);
  }

  private MemberCandidates moduleOptions(List<CustomerIssueFact> facts) {
    Set<String> values = new LinkedHashSet<>();
    boolean hasMissing = false;
    for (CustomerIssueFact fact : facts) {
      if (fact.moduleNames().isEmpty()) {
        hasMissing = true;
      }
      values.addAll(fact.moduleNames());
    }
    return new MemberCandidates(sortedOptions(values), hasMissing);
  }

  private MemberCandidates functionOptions(List<CustomerIssueFact> facts) {
    Set<String> values = new LinkedHashSet<>();
    boolean hasMissing = false;
    for (CustomerIssueFact fact : facts) {
      String function = trimOrNull(fact.functionName());
      if (function == null) {
        hasMissing = true;
      } else {
        values.add(function);
      }
    }
    return new MemberCandidates(sortedOptions(values), hasMissing);
  }

  private static List<String> sortedOptions(Set<String> values) {
    return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
  }

  /** 一个成员维度在完整基础范围内的候选：去重排序的取值，以及是否存在该维度缺失的议题。 */
  private record MemberCandidates(List<String> values, boolean hasMissingMembers) {}

  /**
   * 组装一个维度的候选：每个候选都带类型，缺失成员与真实同名成员因此不会互相顶替。
   *
   * <p>缺失成员只有在基础范围内确实存在该维度为空的议题时才进候选；真实成员一律按取值下发并可按
   * 精确名筛选，候选顺序保持按标签排序。
   */
  private StatisticBoardControlOptionGroup optionGroup(
      String key, MemberCandidates candidates, String missingLabel) {
    List<StatisticBoardControlOption> options = new ArrayList<>();
    for (String value : candidates.values()) {
      options.add(new StatisticBoardControlOption(SelectionKind.VALUE.name(), value, value));
    }
    if (candidates.hasMissingMembers()) {
      options.add(new StatisticBoardControlOption(SelectionKind.MISSING.name(), "", missingLabel));
    }
    return new StatisticBoardControlOptionGroup(key, options);
  }

  private Map<String, StatisticFieldDescriptor<CustomerIssueFact>> factFilterFields() {
    return Map.ofEntries(
        Map.entry(
            CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD,
            StatisticFieldDescriptor.<CustomerIssueFact>multiValueWithOverride(
                CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD,
                fact -> List.of(Objects.toString(fact.milestoneTitle(), "")),
                (fact, condition) -> CustomerIssueMilestoneFilterSupport.matchesCondition(
                    fact.milestoneTitle(), condition, milestoneCatalogService))),
        Map.entry(
            "severityLevel",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "severityLevel", fact -> List.of(Objects.toString(fact.severityLevel(), "")))),
        Map.entry(
            "priorityLevel",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "priorityLevel", fact -> List.of(Objects.toString(fact.priorityLevel(), "")))),
        Map.entry(
            "bugStatus",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "bugStatus", fact -> List.of(Objects.toString(fact.bugStatus(), "")))),
        Map.entry(
            "category",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "category", fact -> List.of(Objects.toString(fact.category(), "")))),
        Map.entry(
            "authorName",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "authorName", fact -> List.of(Objects.toString(fact.authorName(), "")))),
        Map.entry(
            "assigneeName",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "assigneeName", fact -> List.of(Objects.toString(fact.assigneeName(), "")))),
        Map.entry(
            "issueState",
            StatisticFieldDescriptor.<CustomerIssueFact>multiValue(
                "issueState", fact -> List.of(fact.closed() ? "closed" : "open"))));
  }

  private List<StatisticRowData> buildRows(
      List<FactFlags> flags, GroupBy groupBy, ControlParams control, String sourceVersion) {
    if (groupBy == GroupBy.CUSTOMER) {
      return customerRows(flags, groupBy, control, sourceVersion);
    }
    return customerDimensionRows(flags, groupBy, control, sourceVersion);
  }

  private List<StatisticRowData> customerRows(
      List<FactFlags> flags, GroupBy groupBy, ControlParams control, String sourceVersion) {
    Map<RowMember, List<FactFlags>> buckets = new LinkedHashMap<>();
    for (FactFlags item : flags) {
      for (RowMember member : selectedMembers(customerMembers(item.fact()), control.customer())) {
        buckets.computeIfAbsent(member, key -> new ArrayList<>()).add(item);
      }
    }
    List<StatisticRowData> rows = new ArrayList<>();
    buckets.entrySet().stream()
        .sorted(Comparator.comparing(
            entry -> rowMemberLabel(entry.getKey(), MISSING_CUSTOMER_LABEL),
            String.CASE_INSENSITIVE_ORDER))
        .forEach(
            entry ->
                rows.add(
                    buildRow(
                        groupBy, entry.getKey(), null, entry.getValue(), false, control, sourceVersion)));
    rows.add(buildRow(groupBy, null, null, flags, true, control, sourceVersion));
    return List.copyOf(rows);
  }

  private List<StatisticRowData> customerDimensionRows(
      List<FactFlags> flags, GroupBy groupBy, ControlParams control, String sourceVersion) {
    Map<List<RowMember>, List<FactFlags>> buckets = new LinkedHashMap<>();
    for (FactFlags item : flags) {
      List<RowMember> customers =
          selectedMembers(customerMembers(item.fact()), control.customer());
      MemberSelection dimensionSelection =
          groupBy == GroupBy.CUSTOMER_MODULE ? control.module() : control.function();
      List<RowMember> dimensions =
          selectedMembers(dimensionMembers(item.fact(), groupBy), dimensionSelection);
      for (RowMember customer : customers) {
        for (RowMember dimension : dimensions) {
          buckets
              .computeIfAbsent(List.of(customer, dimension), key -> new ArrayList<>())
              .add(item);
        }
      }
    }
    List<StatisticRowData> rows = new ArrayList<>();
    buckets.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<List<RowMember>, List<FactFlags>>, String>comparing(
                    entry -> rowMemberLabel(entry.getKey().get(0), MISSING_CUSTOMER_LABEL),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(
                    entry -> rowMemberLabel(entry.getKey().get(1), groupBy.missingLabel()),
                    String.CASE_INSENSITIVE_ORDER))
        .forEach(
            entry ->
                rows.add(
                    buildRow(
                        groupBy,
                        entry.getKey().get(0),
                        entry.getKey().get(1),
                        entry.getValue(),
                        false,
                        control,
                        sourceVersion)));
    rows.add(buildRow(groupBy, null, null, flags, true, control, sourceVersion));
    return List.copyOf(rows);
  }

  private StatisticRowData buildRow(
      GroupBy groupBy,
      RowMember customer,
      RowMember dimension,
      List<FactFlags> facts,
      boolean total,
      ControlParams control,
      String sourceVersion) {
    List<StatisticCellData> cells = new ArrayList<>();
    if (groupBy.dimensionColumnKey() != null && !total) {
      cells.add(
          new StatisticCellData(
              groupBy.dimensionColumnKey(),
              0L,
              rowMemberLabel(dimension, groupBy.missingLabel()),
              false,
              null,
              Map.of()));
    } else if (groupBy.dimensionColumnKey() != null) {
      cells.add(new StatisticCellData(groupBy.dimensionColumnKey(), 0L, "", false, null, Map.of()));
    }
    Map<String, StatisticCellData> metricCells = CustomerIssueStatisticsCalculator.computeCells(facts);
    // 每个指标单元格补上行身份与默认集合，下钻沿用同一规格。
    for (CustomerIssueStatisticMetricCatalog.MetricSpec spec : CustomerIssueStatisticMetricCatalog.METRICS) {
      StatisticCellData cell = metricCells.get(spec.key());
      cells.add(
          new StatisticCellData(
              cell.columnKey(),
              cell.numericValue(),
              cell.displayValue(),
              cell.drilldown(),
              cell.detailViewKey(),
              detailParams(cell, groupBy, customer, dimension, total, control, sourceVersion)));
    }
    if (total) {
      return new StatisticRowData(TOTAL_ROW_KEY, TOTAL_ROW_LABEL, cells);
    }
    return new StatisticRowData(
        jsonUtils.toJson(rowKeySpec(groupBy, customer, dimension)),
        rowMemberLabel(customer, MISSING_CUSTOMER_LABEL),
        cells);
  }

  private Map<String, String> detailParams(
      StatisticCellData cell,
      GroupBy groupBy,
      RowMember customer,
      RowMember dimension,
      boolean total,
      ControlParams control,
      String sourceVersion) {
    Map<String, String> params = new LinkedHashMap<>(cell.detailParams());
    params.put(CustomerIssueStatisticsCalculator.DETAIL_PARAM_ROW_KEY,
        jsonUtils.toJson(rowKeySpec(groupBy, customer, dimension)));
    params.put(GROUP_BY_PARAM, groupBy.name());
    params.put("total", String.valueOf(total));
    // 行身份只定位行，不代替查询条件：客户/模块/功能选择必须一并下传，
    // 否则筛完客户甲再点总计明细会读回全部客户。
    putSelection(params, CUSTOMER_PARAM, CUSTOMER_KIND_PARAM, control.customer());
    putSelection(params, MODULE_PARAM, MODULE_KIND_PARAM, control.module());
    putSelection(params, FUNCTION_PARAM, FUNCTION_KIND_PARAM, control.function());
    // 业务日、来源选择与来源版本一并下传，明细据此重建主表的同一计算上下文。
    params.put(BUSINESS_DATE_PARAM, control.businessDate().toString());
    if (StringUtils.hasText(control.sourceInstance())) {
      params.put(SOURCE_INSTANCE_PARAM, control.sourceInstance());
    }
    params.put(SOURCE_VERSION_PARAM, sourceVersion);
    return params;
  }

  private RowKeySpec rowKeySpec(GroupBy groupBy, RowMember customer, RowMember dimension) {
    return new RowKeySpec(groupBy, customer, dimension, customer == null);
  }

  private List<RowMember> customerMembers(CustomerIssueFact fact) {
    List<RowMember> members = new ArrayList<>();
    for (String name : fact.customerNames()) {
      members.add(RowMember.value(name));
    }
    return members.isEmpty() ? List.of(RowMember.missing()) : members;
  }

  private List<RowMember> dimensionMembers(CustomerIssueFact fact, GroupBy groupBy) {
    List<String> values;
    if (groupBy == GroupBy.CUSTOMER_MODULE) {
      values = fact.moduleNames();
    } else {
      String function = trimOrNull(fact.functionName());
      values = function == null ? List.of() : List.of(function);
    }
    List<RowMember> members = new ArrayList<>();
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        members.add(RowMember.value(value));
      }
    }
    return members.isEmpty() ? List.of(RowMember.missing()) : members;
  }

  /** 只限制行展开成员；事实集合、候选范围与总计仍由各自完整业务规则计算。 */
  private List<RowMember> selectedMembers(
      List<RowMember> members, MemberSelection selection) {
    return members.stream().filter(member -> matchesSelection(member, selection)).toList();
  }

  private boolean matchesSelection(RowMember member, MemberSelection selection) {
    return switch (selection.kind()) {
      case ALL -> true;
      case MISSING -> member.isMissing();
      case VALUE ->
          !member.isMissing()
              && member.value() != null
              && member.value().equalsIgnoreCase(selection.value());
    };
  }

  private static String rowMemberLabel(RowMember member, String missingLabel) {
    if (member == null) {
      return "";
    }
    return member.isMissing() ? missingLabel : Objects.toString(member.value(), "");
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    if (!CustomerIssueStatisticMetricCatalog.isKnown(request.columnKey())) {
      throw new IllegalArgumentException("未知的客户问题统计指标列: " + request.columnKey());
    }
    CustomerIssueStatisticMetricCatalog.MetricSpec spec =
        CustomerIssueStatisticMetricCatalog.require(request.columnKey());
    ControlParams control = parseControlParams(request.filters());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    String declaredSourceVersion =
        trimOrNull(request.filters() == null ? null : request.filters().get(SOURCE_VERSION_PARAM));
    if (declaredSourceVersion == null) {
      throw new BizException("缺少主表来源版本，请从主表单元格打开明细");
    }
    RowKeySpec rowKey = decodeRowKey(request.rowKey(), control.groupBy());
    CustomerIssueStatisticMetricCatalog.Population population =
        parsePopulation(request.filters(), spec);
    // 范围解析、发布资格、来源版本与事实读取共用同一个一致性边界：边界外先查版本再单独读事实，
    // 一旦检查之后发生发布，就会把两代数据混进同一份明细。
    return snapshotService.withinConsistentSourceRead(
        readScopePlan(request.filters(), effectiveFilterGroup),
        sourceRead -> {
          requireSameSourceGeneration(declaredSourceVersion, sourceRead.sourceVersion());
          return buildDetailResponse(request, spec, population, rowKey, control, effectiveFilterGroup);
        });
  }

  private StatisticDetailResponse buildDetailResponse(
      StatisticDetailRequest request,
      CustomerIssueStatisticMetricCatalog.MetricSpec spec,
      CustomerIssueStatisticMetricCatalog.Population population,
      RowKeySpec rowKey,
      ControlParams control,
      StatisticFilterGroup effectiveFilterGroup) {
    List<FactFlags> scopeFacts = loadFlags(effectiveFilterGroup, control);
    // 行成员校验必须发生在集合筛选之前：0 数量、零分母、零样本的合法空集合不能等同于“该行不存在”。
    requireRowMembersInScope(rowKey, scopeFacts);
    List<FactFlags> scoped =
        scopeFacts.stream()
            .filter(flags -> matchesRow(flags, rowKey))
            .filter(flags -> matchesPopulation(flags, spec, population))
            .toList();
    List<FactFlags> sorted = new ArrayList<>(scoped);
    sorted.sort(buildDetailComparator(request.sortField(), request.sortOrder()));
    DetailRecordPage pageSlice =
        sliceDetailRecords(request, sorted, flags -> toDetailRecord(flags.fact()));
    return new StatisticDetailResponse(
        "客户问题统计明细",
        populationDescription(spec, population),
        detailCollections(spec),
        population.name(),
        detailColumns(),
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending",
        pageSlice.quickFilterOptions());
  }

  /**
   * 与主表共用同一份规范化查询上下文时的读取范围计划。
   *
   * <p>解析动作交给一致性边界内执行，目录与来源集合的变化因此一定落在同一数据库视图里。
   *
   * @param filters 明细请求的真实筛选，含来源选择
   * @param effectiveFilterGroup 与主表同一生效筛选组
   */
  private StatisticBoardSnapshotService.SourceReadPlan readScopePlan(
      Map<String, String> filters, StatisticFilterGroup effectiveFilterGroup) {
    String selectedMilestone =
        CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    return StatisticBoardSnapshotService.SourceReadPlan.of(
        () ->
            readScopeResolver.resolve(
                filters, CUSTOMER_PROJECT_ID, IssueScopeDimension.MILESTONE, selectedMilestone));
  }

  /**
   * 确认明细所在来源代际与主表一致。
   *
   * <p>主表与下钻必须落在同一来源代际：来源在打开明细前后发生变化时，继续用新事实回答旧单元格语义
   * 会把两次读取混在一起。此处明确拒绝，由前端保留旧主表并提示整表刷新。
   *
   * @param declaredVersion 主表单元格下传的来源版本
   * @param currentVersion 当前一致性视图内解析出的来源版本
   */
  private void requireSameSourceGeneration(String declaredVersion, String currentVersion) {
    if (!declaredVersion.equals(currentVersion)) {
      throw new BizException("主表来源已发生变化，请刷新整表后再查看明细");
    }
  }

  /**
   * 校验下钻行身份成员确实存在于当前范围。
   *
   * <p>行键由主表生成，成员必然来自当前范围；出现范围外成员说明行维度被篡改或该行已随来源变化消失，
   * 此时拒绝而不是返回空列表冒充"该客户没有议题"。
   *
   * @param rowKey 已解码的下钻行身份；总计行天然通过
   * @param scope 当前范围的完整事实列表（未经行与集合筛选）
   */
  private void requireRowMembersInScope(RowKeySpec rowKey, List<FactFlags> scope) {
    if (rowKey == null || rowKey.total()) {
      return;
    }
    boolean customerPresent =
        scope.stream().anyMatch(flags -> containsMember(customerMembers(flags.fact()), rowKey.customer()));
    if (!customerPresent) {
      throw new BizException("下钻行客户不在当前统计范围内，请刷新整表后重试");
    }
    if (rowKey.groupBy() == GroupBy.CUSTOMER) {
      return;
    }
    // 组合维度按同一议题整体校验：成员各自存在但组合不存在仍是伪造行。
    boolean rowPresent = scope.stream().anyMatch(flags -> matchesRow(flags, rowKey));
    if (!rowPresent) {
      throw new BizException("下钻行模块/功能不在当前统计范围内，请刷新整表后重试");
    }
  }

  /** 当前指标允许的集合列表；数量、比率与周期各自固定，空集合仍由响应解释。 */
  private List<StatisticDetailCollection> detailCollections(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    List<CustomerIssueStatisticMetricCatalog.Population> populations =
        allowedPopulations(spec).stream()
            .sorted(java.util.Comparator.comparing(Enum::name))
            .toList();
    return populations.stream()
        .map(
            population ->
                new StatisticDetailCollection(
                    population.name(),
                    collectionLabel(population),
                    populationDescription(spec, population)))
        .toList();
  }

  private static String collectionLabel(CustomerIssueStatisticMetricCatalog.Population population) {
    return switch (population) {
      case COUNTED -> "计数集合";
      case NUMERATOR -> "分子集合";
      case DENOMINATOR -> "分母集合";
      case SAMPLE -> "有效样本";
    };
  }

  private String populationDescription(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec,
      CustomerIssueStatisticMetricCatalog.Population population) {
    if (spec.type() == CustomerIssueStatisticMetricCatalog.MetricType.DURATION_HOURS
        || spec.type() == CustomerIssueStatisticMetricCatalog.MetricType.DURATION_DAYS) {
      return "展示当前单元格的有效周期样本（每条议题的创建时间、完成时间与单条周期），无样本时为空集合。";
    }
    return switch (population) {
      case NUMERATOR -> "展示该比率指标的分子议题集合。";
      case DENOMINATOR -> "展示该比率指标的分母议题集合。";
      default -> "展示该数量指标实际贡献的议题集合。";
    };
  }

  private boolean matchesRow(FactFlags flags, RowKeySpec rowKey) {
    if (rowKey == null || rowKey.total()) {
      return true;
    }
    if (!containsMember(customerMembers(flags.fact()), rowKey.customer())) {
      return false;
    }
    if (rowKey.groupBy() == GroupBy.CUSTOMER) {
      return true;
    }
    return containsMember(dimensionMembers(flags.fact(), rowKey.groupBy()), rowKey.dimension());
  }

  private static boolean containsMember(List<RowMember> members, RowMember expected) {
    if (expected == null) {
      return true;
    }
    return members.stream().anyMatch(member -> member.equals(expected));
  }

  private boolean matchesPopulation(
      FactFlags flags,
      CustomerIssueStatisticMetricCatalog.MetricSpec spec,
      CustomerIssueStatisticMetricCatalog.Population population) {
    if (!inBaseSet(spec, flags)) {
      return false;
    }
    if (!spec.denominator().test(flags)) {
      return false;
    }
    return switch (population) {
      case COUNTED, DENOMINATOR -> true;
      case NUMERATOR -> spec.numerator() != null && spec.numerator().test(flags);
      case SAMPLE -> sampleEligible(flags, spec);
    };
  }

  private static boolean sampleEligible(
      FactFlags flags, CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    return switch (spec.type()) {
      case DURATION_HOURS -> flags.responseSample().eligible();
      case DURATION_DAYS -> flags.resolutionSample().eligible();
      default -> true;
    };
  }

  private static boolean inBaseSet(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec, FactFlags flags) {
    return switch (spec.baseSet()) {
      case D -> flags.regular();
      case S -> flags.suggestion();
      case N -> flags.requirement();
      case L -> flags.delayEligible();
      case E -> flags.efficiencyScope();
    };
  }

  private CustomerIssueStatisticMetricCatalog.Population parsePopulation(
      Map<String, String> filters,
      CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    String raw = filters == null ? null : trimOrNull(filters.get(POPULATION_PARAM));
    CustomerIssueStatisticMetricCatalog.Population population =
        raw == null
            ? spec.population()
            : parsePopulationValue(raw);
    if (!allowedPopulations(spec).contains(population)) {
      throw new IllegalArgumentException(
          "指标 " + spec.key() + " 不支持集合类型: " + population);
    }
    return population;
  }

  private static CustomerIssueStatisticMetricCatalog.Population parsePopulationValue(String raw) {
    try {
      return CustomerIssueStatisticMetricCatalog.Population.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("不支持的集合类型: " + raw);
    }
  }

  private static Set<CustomerIssueStatisticMetricCatalog.Population> allowedPopulations(
      CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    return switch (spec.type()) {
      case COUNT -> Set.of(CustomerIssueStatisticMetricCatalog.Population.COUNTED);
      case RATIO ->
          Set.of(
              CustomerIssueStatisticMetricCatalog.Population.NUMERATOR,
              CustomerIssueStatisticMetricCatalog.Population.DENOMINATOR);
      case DURATION_HOURS, DURATION_DAYS ->
          Set.of(CustomerIssueStatisticMetricCatalog.Population.SAMPLE);
      case TEXT -> Set.of();
    };
  }

  private RowKeySpec decodeRowKey(String rawRowKey, GroupBy requestedGroupBy) {
    String normalized = trimOrNull(rawRowKey);
    if (normalized == null) {
      throw new IllegalArgumentException("缺少下钻行身份");
    }
    RowKeySpec spec = jsonUtils.fromJson(normalized, RowKeySpec.class);
    if (spec == null) {
      throw new IllegalArgumentException("下钻行身份无法解析");
    }
    if (spec.total()) {
      return spec;
    }
    GroupBy groupBy = spec.groupBy() == null ? requestedGroupBy : spec.groupBy();
    if (groupBy != requestedGroupBy) {
      throw new IllegalArgumentException("下钻行维度与当前分组不一致");
    }
    if (spec.customer() == null
        || (!spec.customer().isMissing() && trimOrNull(spec.customer().value()) == null)) {
      throw new IllegalArgumentException("下钻行身份缺少客户成员");
    }
    if (groupBy != GroupBy.CUSTOMER
        && (spec.dimension() == null
            || !spec.dimension().isMissing() && trimOrNull(spec.dimension().value()) == null)) {
      throw new IllegalArgumentException("下钻行身份缺少模块/功能成员");
    }
    return new RowKeySpec(groupBy, spec.customer(), spec.dimension(), false);
  }

  private Comparator<FactFlags> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<FactFlags> comparator =
        switch (trimOrNull(sortField) == null ? "updatedAt" : sortField.trim()) {
          case "iid" -> SortSupport.nullableComparable(flags -> flags.fact().issueIid());
          case "title" -> SortSupport.nullableString(flags -> flags.fact().title());
          case "state" -> SortSupport.nullableString(flags -> flags.fact().issueState());
          case "severityLevel" -> SortSupport.nullableString(flags -> flags.fact().severityLevel());
          case "priorityLevel" -> SortSupport.nullableString(flags -> flags.fact().priorityLevel());
          case "bugStatus" -> SortSupport.nullableString(flags -> flags.fact().bugStatus());
          case "milestoneTitle" -> SortSupport.nullableString(flags -> flags.fact().milestoneTitle());
          case "createdAt" -> SortSupport.nullableComparable(flags -> flags.fact().createdAtSource());
          case "authorName" -> SortSupport.nullableString(flags -> flags.fact().authorName());
          case "assigneeName" -> SortSupport.nullableString(flags -> flags.fact().assigneeName());
          default -> SortSupport.nullableComparable(flags -> flags.fact().updatedAtSource());
        };
    comparator = comparator.thenComparing(flags -> flags.fact().identityKey());
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(CustomerIssueFact fact) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record,
        fact.sourceInstance(),
        (int) fact.issueIid(),
        fact.projectId(),
        fact.projectName(),
        fact.issueId(),
        splitLabels(fact.labelNames()));
    record.put("moduleNames", String.join("、", fact.moduleNames()));
    record.put("title", fact.title());
    record.put("state", fact.issueState());
    record.put("severityLevel", fact.severityLevel());
    record.put("priorityLevel", fact.priorityLevel());
    record.put("bugStatus", fact.bugStatus());
    record.put("milestoneTitle", fact.milestoneTitle());
    record.put("createdAt", format(fact.createdAtSource()));
    record.put("updatedAt", format(fact.updatedAtSource()));
    record.put("authorName", fact.authorName());
    record.put("assigneeName", fact.assigneeName());
    record.put("delayCause", fact.delayCause());
    return record;
  }

  private static String format(LocalDateTime time) {
    return time == null ? "" : time.toString().replace('T', ' ');
  }

  private static List<String> splitLabels(String rawValue) {
    if (!StringUtils.hasText(rawValue)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : rawValue.split(",")) {
      String trimmed = trimOrNull(part);
      if (trimmed != null) {
        values.add(trimmed);
      }
    }
    return List.copyOf(values);
  }

  @Override
  public void refreshSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    Set<String> affectedMilestones = new LinkedHashSet<>(milestoneCatalogService.listMilestones(context));
    if (affectedMilestones.isEmpty()) {
      return;
    }
    List<StatisticFilterOption> milestoneOptions =
        milestoneCatalogService.listOptions().stream()
            .filter(option -> affectedMilestones.contains(option.value()))
            .map(option -> new StatisticFilterOption(option.label(), option.value()))
            .toList();
    for (StatisticFilterGroup milestoneGroup :
        CustomerIssueSqlScopeSupport.milestoneFilterGroups(milestoneOptions, 3)) {
      StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(milestoneGroup);
      ControlParams control = defaultControlParams();
      Map<String, String> payload = snapshotPayload(Map.of(), effectiveFilterGroup, control);
      StatisticBoardDefinition definition = buildDefinition(control.groupBy());
      StatisticBoardSnapshotService.SnapshotRequest request =
          snapshotRequest(payload, effectiveFilterGroup, definition);
      // 预热写入解析一次范围即可：单元格携带的版本与快照键必须来自同一份解析结果。
      String sourceVersion =
          snapshotService.issueFactSourceVersion(request.readScopes().resolve());
      snapshotService.save(
          request,
          sourceVersion,
          buildBoardResponse(Map.of(), effectiveFilterGroup, control, definition, sourceVersion));
    }
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return runtimeSupport.getRealtimeStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus(Map<String, String> filters) {
    return runtimeSupport.getRealtimeStatus(BOARD_KEY, filters);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return runtimeSupport.requestRealtimeRefresh(BOARD_KEY);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(parseFilterGroup(filters, buildDefinition()));
    ControlParams control = parseControlParams(filters);
    List<FactFlags> flags = loadFlags(effectiveFilterGroup, control);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题统计规则说明",
        RULE_VERSION,
        "限定项目 325 已启用里程碑范围内的客户问题，按客户或客户×模块/功能展开；全部指标在去重议题身份上计算。",
        "多客户议题在各客户行各计一次，总计行按完整议题身份去重；缺陷与需求可能交叉，不展示“总问题数＋总需求数”。",
        List.of(
            step("source-load", "加载范围内事实", "读取项目 325 当前创建范围内的议题事实与规范客户成员。", flags, flags),
            step(
                "regular-filter",
                "常规缺陷范围",
                "沿客户缺陷汇总排除建议类与关闭排除项，形成常规缺陷集合 D。",
                flags,
                flags.stream().filter(FactFlags::regular).toList()),
            step(
                "level-priority",
                "级别与优先级",
                "按归一化严重级别与优先级分桶；缺失值不归入三级或 P3。",
                flags,
                flags.stream().filter(FactFlags::regular).toList()),
            step(
                "fix-status",
                "修复状态",
                "整体修复与优先级修复使用各自现行判据；申请延期是独立状态计数。",
                flags,
                flags.stream().filter(FactFlags::regular).toList()),
            step(
                "delay-scope",
                "延期范围",
                "常规缺陷且无 GitLab 接口异常、open、命中响应或解决延期，形成集合 L。",
                flags,
                flags.stream().filter(FactFlags::delayEligible).toList()),
            step(
                "requirement-scope",
                "客户需求范围",
                "在客户公共关闭排除之上按标签身份取“需求”或“类别：建议”，不应用建议类排除。",
                flags,
                flags.stream().filter(FactFlags::requirement).toList())),
        metricDefinitions(),
        null);
  }

  private static StatisticRuleFlowStep step(
      String key,
      String title,
      String description,
      List<FactFlags> input,
      List<FactFlags> output) {
    return new StatisticRuleFlowStep(
        key,
        title,
        description,
        input.size(),
        output.size(),
        output.stream()
            .limit(5)
            .map(flags -> new com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample(
                "#" + flags.fact().issueIid() + " " + flags.fact().projectName(),
                flags.fact().title()
                    + " | 模块: "
                    + String.join("、", flags.fact().moduleNames())
                    + " | 严重程度: "
                    + flags.fact().severityLevel()
                    + " | 优先级: "
                    + flags.fact().priorityLevel()))
            .toList());
  }

  private List<StatisticRuleMetricDefinition> metricDefinitions() {
    return CustomerIssueStatisticMetricCatalog.METRICS.stream()
        .map(
            spec ->
                new StatisticRuleMetricDefinition(
                    spec.key(),
                    spec.label(),
                    spec.baseSet().description(),
                    formula(spec),
                    spec.baseSet().name()))
        .toList();
  }

  private static String formula(CustomerIssueStatisticMetricCatalog.MetricSpec spec) {
    return switch (spec.type()) {
      case COUNT -> "count(" + spec.baseSet().name() + " 且命中该指标成员条件)";
      case RATIO -> "分子 ÷ 分母 × 100，零分母不可计算";
      case DURATION_HOURS -> "对全部有效响应样本求平均并取整为完整小时";
      case DURATION_DAYS -> "对全部有效解决样本求平均并保留 1 位小数";
      case TEXT -> "-";
    };
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> payload,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    String selectedMilestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;milestone="
            + (StringUtils.hasText(selectedMilestone) ? selectedMilestone : "none"),
        CUSTOMER_PROJECT_ID,
        com.data.collection.platform.service.IssueScopeDimension.MILESTONE,
        selectedMilestone,
        payload,
        definition,
        effectiveFilterGroup);
  }

  /** 快照键必须包含全部控制参数与业务日，跨日后“今日”指标自然失效。 */
  private Map<String, String> snapshotPayload(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      ControlParams control) {
    LinkedHashMap<String, String> payload =
        new LinkedHashMap<>(withoutReservedFilters(filters == null ? Map.of() : filters));
    payload.put("projectId", String.valueOf(CUSTOMER_PROJECT_ID));
    String selectedMilestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    if (StringUtils.hasText(selectedMilestone)) {
      payload.put(CustomerIssueMilestoneFilterSupport.MILESTONE_FIELD, selectedMilestone);
    }
    payload.put(GROUP_BY_PARAM, control.groupBy().name());
    putSelection(payload, CUSTOMER_PARAM, CUSTOMER_KIND_PARAM, control.customer());
    putSelection(payload, MODULE_PARAM, MODULE_KIND_PARAM, control.module());
    putSelection(payload, FUNCTION_PARAM, FUNCTION_KIND_PARAM, control.function());
    payload.put(BUSINESS_DATE_PARAM, control.businessDate().toString());
    if (StringUtils.hasText(control.sourceInstance())) {
      payload.put(SOURCE_INSTANCE_PARAM, control.sourceInstance());
    }
    return payload;
  }

  /**
   * 把成员选择写成控制参数，与 {@code parseSelection} 互逆，供明细重建同一查询上下文。
   *
   * <p>取值与类型分开写：缺失只写类型参数，精确成员同时写成员名与类型。成员名因此可以是任意文本，
   * 包括 {@code __missing__} 或"未标注客户"这类与缺失文案同形的真实名称。
   *
   * @param target 目标参数表
   * @param valueParam 成员取值参数名
   * @param kindParam 成员类型参数名
   * @param selection 主表使用的成员选择；{@code ALL} 不写任何参数
   */
  private static void putSelection(
      Map<String, String> target, String valueParam, String kindParam, MemberSelection selection) {
    switch (selection.kind()) {
      case ALL -> {
        // 不限：两个参数都不写
      }
      case MISSING -> target.put(kindParam, SelectionKind.MISSING.name());
      case VALUE -> {
        target.put(valueParam, selection.value());
        target.put(kindParam, SelectionKind.VALUE.name());
      }
    }
  }

  /** 解析控制参数：groupBy、客户/模块/功能精确成员、来源实例与业务日。 */
  ControlParams parseControlParams(Map<String, String> filters) {
    Map<String, String> safe = filters == null ? Map.of() : filters;
    return new ControlParams(
        GroupBy.parse(safe.get(GROUP_BY_PARAM)),
        parseSelection(safe, CUSTOMER_PARAM, CUSTOMER_KIND_PARAM),
        parseSelection(safe, MODULE_PARAM, MODULE_KIND_PARAM),
        parseSelection(safe, FUNCTION_PARAM, FUNCTION_KIND_PARAM),
        parseBusinessDate(safe.get(BUSINESS_DATE_PARAM)),
        trimOrNull(safe.get(SOURCE_INSTANCE_PARAM)));
  }

  private ControlParams defaultControlParams() {
    return new ControlParams(
        GroupBy.CUSTOMER, MemberSelection.all(), MemberSelection.all(), MemberSelection.all(), LocalDate.now(), null);
  }

  /**
   * 解析一个成员选择：ALL/MISSING 不带成员值，VALUE 必须带非空成员值；只有两个参数都缺省才表示不限。
   *
   * @param filters 请求参数；通过键是否存在区分“缺省”与显式空值
   * @param valueParam 成员名参数
   * @param kindParam 成员类型参数
   */
  private static MemberSelection parseSelection(
      Map<String, String> filters, String valueParam, String kindParam) {
    boolean valuePresent = filters.containsKey(valueParam);
    boolean kindPresent = filters.containsKey(kindParam);
    String rawValue = filters.get(valueParam);
    String rawKind = filters.get(kindParam);
    String kind = trimOrNull(rawKind);
    if (!kindPresent && !valuePresent) {
      return MemberSelection.all();
    }
    if (!kindPresent || kind == null) {
      throw new IllegalArgumentException("成员类型与取值参数不完整: " + kindParam + "/" + valueParam);
    }
    SelectionKind parsed;
    try {
      parsed = SelectionKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("非法的成员类型参数: " + rawKind);
    }
    if (parsed == SelectionKind.MISSING) {
      if (valuePresent) {
        throw new IllegalArgumentException("缺失成员类型不能携带成员取值: " + valueParam);
      }
      return MemberSelection.missing();
    }
    if (parsed == SelectionKind.ALL) {
      if (valuePresent) {
        throw new IllegalArgumentException("不限成员类型不能携带成员取值: " + valueParam);
      }
      return MemberSelection.all();
    }
    String normalized = trimOrNull(rawValue);
    if (!valuePresent || normalized == null) {
      throw new IllegalArgumentException("VALUE 成员类型必须携带非空成员取值: " + valueParam);
    }
    return MemberSelection.of(normalized);
  }

  private static LocalDate parseBusinessDate(String raw) {
    String normalized = trimOrNull(raw);
    if (normalized == null) {
      return LocalDate.now();
    }
    try {
      return LocalDate.parse(normalized);
    } catch (java.time.format.DateTimeParseException error) {
      throw new IllegalArgumentException("非法的业务日参数: " + raw);
    }
  }

}
