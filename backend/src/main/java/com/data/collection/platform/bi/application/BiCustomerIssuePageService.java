package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.BiCustomerIssueCalculator;
import com.data.collection.platform.bi.domain.BiCustomerIssueCalculator.Calculation;
import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiMetricTrace;
import com.data.collection.platform.bi.domain.model.BiMetricTrace.SourceField;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiPageSection;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData.MemberOption;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueModuleMembers;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import com.data.collection.platform.service.statistics.StatisticBoardReadScopeResolver;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立客户问题 BI 页应用服务；一请求只读一次一致来源快照并生成完整页面数据。 */
public final class BiCustomerIssuePageService {
  public static final String PAGE_KEY = "customer-issues";
  public static final String RULE_VERSION = "customer-issue-bi@2026-09-24-v1";
  private static final long PROJECT_ID = 325L;

  private final CustomerIssueFactQueryService factQueryService;
  private final CustomerIssueMilestoneCatalogService milestoneCatalog;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardReadScopeResolver scopeResolver;
  private final JdbcTemplate jdbcTemplate;
  private final BiCustomerIssueCalculator calculator;

  public BiCustomerIssuePageService(
      CustomerIssueFactQueryService factQueryService,
      CustomerIssueMilestoneCatalogService milestoneCatalog,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardReadScopeResolver scopeResolver,
      JdbcTemplate jdbcTemplate,
      BiCustomerIssueCalculator calculator) {
    this.factQueryService = factQueryService;
    this.milestoneCatalog = milestoneCatalog;
    this.snapshotService = snapshotService;
    this.scopeResolver = scopeResolver;
    this.jdbcTemplate = jdbcTemplate;
    this.calculator = calculator;
  }

  /** 按里程碑和类型化成员选择读取完整范围候选，再在同一事实快照上过滤并计算整页。 */
  public BiPageResponse<BiCustomerIssuePageData> load(Query query) {
    Query effectiveQuery = query == null ? Query.all() : query;
    String milestone = selectedMilestone(effectiveQuery.milestoneBusinessKey());
    if (milestone.isBlank()) {
      return unavailable("项目325尚未配置启用的客户里程碑范围");
    }

    Snapshot snapshot;
    try {
      snapshot = readSnapshot(milestone);
    } catch (BizException qualificationOrScopeError) {
      return incompleteSource(qualificationOrScopeError.getMessage());
    }

    List<MemberOption> customers = options(snapshot.facts(), Dimension.CUSTOMER);
    List<MemberOption> modules = options(snapshot.facts(), Dimension.MODULE);
    List<MemberOption> functions = options(snapshot.facts(), Dimension.FUNCTION);
    requireCandidate(effectiveQuery.customer(), customers, "客户");
    requireCandidate(effectiveQuery.module(), modules, "模块");
    requireCandidate(effectiveQuery.function(), functions, "功能");

    List<CustomerIssueFact> filteredFacts = snapshot.facts().stream()
        .filter(fact -> matches(fact, Dimension.CUSTOMER, effectiveQuery.customer()))
        .filter(fact -> matches(fact, Dimension.MODULE, effectiveQuery.module()))
        .filter(fact -> matches(fact, Dimension.FUNCTION, effectiveQuery.function()))
        .toList();
    Calculation calculation = calculator.calculate(
        filteredFacts,
        snapshot.businessDate(),
        CustomerIssueFactQueryService.CUSTOMER_ISSUE_START_DATE);
    BiCustomerIssuePageData data = withContext(
        calculation.data(), snapshot, customers, modules, functions);
    List<BiPageSection> sections = sections(calculation);
    BiDataStatus status = overallStatus(calculation);
    return BiPageResponse.create(
        PAGE_KEY,
        status,
        snapshot.sourceVersion(),
        snapshot.sourceVersion(),
        RULE_VERSION,
        sections,
        traces(),
        data);
  }

  /**
   * 下载时重新读取同一来源边界，验证版本、业务日、里程碑和所有类型化筛选仍然有效。
   *
   * @return 可写入工作簿元信息的完整统计范围说明
   */
  public String validateDownloadContext(
      Query query, String expectedSourceVersion, LocalDate expectedBusinessDate) {
    Query effectiveQuery = query == null ? Query.all() : query;
    String milestone = selectedMilestone(effectiveQuery.milestoneBusinessKey());
    if (milestone.isBlank()) {
      throw new BizException("没有可用于下载的客户里程碑范围");
    }
    Snapshot snapshot = readSnapshot(milestone);
    if (expectedSourceVersion == null || !expectedSourceVersion.equals(snapshot.sourceVersion())) {
      throw new BizException("客户问题来源已有新版本，请刷新页面后再下载");
    }
    if (expectedBusinessDate == null || !expectedBusinessDate.equals(snapshot.businessDate())) {
      throw new BizException("业务日已变化，请刷新页面后再下载");
    }
    requireCandidate(effectiveQuery.customer(), options(snapshot.facts(), Dimension.CUSTOMER), "客户");
    requireCandidate(effectiveQuery.module(), options(snapshot.facts(), Dimension.MODULE), "模块");
    requireCandidate(effectiveQuery.function(), options(snapshot.facts(), Dimension.FUNCTION), "功能");
    return rangeDescription(snapshot, effectiveQuery);
  }

  private Snapshot readSnapshot(String milestoneBusinessKey) {
    Map<String, String> sourceFilter = Map.of(
        StatisticBoardReadScopeResolver.SOURCE_INSTANCE_PARAM,
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE);
    return snapshotService.withinConsistentCurrentFactRead(
        StatisticBoardSnapshotService.SourceReadPlan.of(
            () -> scopeResolver.resolve(
                sourceFilter, PROJECT_ID, IssueScopeDimension.MILESTONE, milestoneBusinessKey)),
        sourceRead -> {
          List<OptionItemResponse> currentRanges = milestoneCatalog.listOptions();
          OptionItemResponse selected = currentRanges.stream()
              .filter(item -> item.value().equalsIgnoreCase(milestoneBusinessKey))
              .findFirst()
              .orElseThrow(() -> new BizException("客户里程碑范围不存在或已停用：" + milestoneBusinessKey));
          List<String> values = milestoneCatalog.resolveMilestoneValues(milestoneBusinessKey);
          List<CustomerIssueFact> facts = factQueryService.load(new FactScopeRequest(
              GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
              values,
              MemberSelection.all(),
              MemberSelection.all(),
              MemberSelection.all()));
          LocalDate businessDate = jdbcTemplate.queryForObject("select current_date", LocalDate.class);
          if (businessDate == null) {
            throw new IllegalStateException("数据库没有返回客户问题 BI 业务日");
          }
          List<BiCustomerIssuePageData.RangeOption> ranges = currentRanges.stream()
              .map(item -> new BiCustomerIssuePageData.RangeOption(item.value(), item.label()))
              .toList();
          return new Snapshot(sourceRead.sourceVersion(), businessDate, milestoneBusinessKey,
              selected.label(), ranges, facts);
        });
  }

  private String selectedMilestone(String requested) {
    String normalized = TextQuerySupport.trimToNull(requested);
    return normalized == null ? milestoneCatalog.defaultMilestone() : normalized;
  }

  private static List<MemberOption> options(List<CustomerIssueFact> facts, Dimension dimension) {
    Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    boolean missing = false;
    for (CustomerIssueFact fact : facts) {
      List<String> members = members(fact, dimension);
      if (members.isEmpty()) {
        missing = true;
      } else {
        members.forEach(value -> values.putIfAbsent(value, value));
      }
    }
    List<MemberOption> options = new ArrayList<>();
    values.values().forEach(value -> options.add(new MemberOption(
        "VALUE", value, value)));
    if (missing) {
      options.add(new MemberOption("MISSING", null, "未标注" + dimension.label()));
    }
    return List.copyOf(options);
  }

  private static void requireCandidate(MemberSelection selection, List<MemberOption> options, String label) {
    if (selection.kind() == CustomerIssueFactQueryService.SelectionKind.ALL) return;
    boolean present = selection.kind() == CustomerIssueFactQueryService.SelectionKind.MISSING
        ? options.stream().anyMatch(option -> option.kind().equals("MISSING"))
        : options.stream().anyMatch(option -> option.kind().equals("VALUE")
            && option.value().equalsIgnoreCase(selection.value()));
    if (!present) {
      throw new BizException("当前客户里程碑范围中没有所选" + label + "成员；请重新选择候选项");
    }
  }

  private static boolean matches(
      CustomerIssueFact fact, Dimension dimension, MemberSelection selection) {
    if (selection.kind() == CustomerIssueFactQueryService.SelectionKind.ALL) return true;
    List<String> values = members(fact, dimension);
    if (selection.kind() == CustomerIssueFactQueryService.SelectionKind.MISSING) return values.isEmpty();
    return values.stream().anyMatch(value -> value.equalsIgnoreCase(selection.value()));
  }

  private static List<String> members(CustomerIssueFact fact, Dimension dimension) {
    return switch (dimension) {
      case CUSTOMER -> fact.customerNames();
      case MODULE -> fact.moduleNames();
      case FUNCTION -> {
        String value = IssueModuleMembers.normalizeToNull(fact.functionName());
        yield value == null ? List.of() : List.of(value);
      }
    };
  }

  private static BiCustomerIssuePageData withContext(
      BiCustomerIssuePageData value,
      Snapshot snapshot,
      List<MemberOption> customers,
      List<MemberOption> modules,
      List<MemberOption> functions) {
    return new BiCustomerIssuePageData(
        snapshot.milestones(), snapshot.milestoneBusinessKey(), snapshot.milestoneDisplayName(),
        snapshot.businessDate(), customers, modules, functions,
        value.defectMetrics(), value.requirementMetrics(), value.moduleDefects(),
        value.severityDistribution(), value.moduleSeverity(), value.causeDistribution(),
        value.unclassifiedCauseCount(), value.delayAnalysis(), value.assigneeWorkload(),
        value.moduleDemand(), value.dailyTrend(), value.filteredFactCount());
  }

  private static List<BiPageSection> sections(Calculation calculation) {
    boolean noFacts = calculation.filteredFactCount() == 0;
    boolean noDefects = metricValue(calculation.data(), "defect_total") == 0;
    List<BiPageSection> result = new ArrayList<>();
    result.add(section("source-consistency", "来源一致性", BiDataStatus.READY, "来源资格、版本和事实在同一只读一致性事务内读取。"));
    result.add(section("defect-overview", "缺陷指标", noFacts ? BiDataStatus.EMPTY : BiDataStatus.READY,
        noFacts ? "当前筛选范围没有议题。" : "缺陷指标按完整议题身份去重。"));
    result.add(section("today-activity", "今日活动",
        calculation.unknownCreatedTimeCount() > 0 || calculation.unknownFixedTimeCount() > 0
        ? BiDataStatus.INCOMPLETE : noDefects ? BiDataStatus.EMPTY : BiDataStatus.READY,
        calculation.unknownCreatedTimeCount() > 0 || calculation.unknownFixedTimeCount() > 0
            ? "存在缺少创建时间或精确完成事件时间的缺陷；今日新增/解决分区有未知贡献。缺失创建时间："
                + calculation.unknownCreatedTimeCount() + "，缺失完成时间：" + calculation.unknownFixedTimeCount()
            : "今日指标使用本次固定业务日和半开自然日边界。"));
    result.add(chartSection("module-defects", "模块缺陷", noDefects));
    result.add(chartSection("severity-distribution", "缺陷级别分布", noDefects));
    result.add(chartSection("module-severity", "模块与缺陷级别", noDefects));
    result.add(chartSection("cause-distribution", "缺陷原因分布", noDefects));
    result.add(chartSection("delay-analysis", "申请延期分析", noDefects));
    result.add(chartSection("assignee-workload", "指派人负荷", noDefects));
    boolean requirementUnknown = calculation.unknownRequirementIdentityCount() > 0;
    result.add(section("requirement-overview", "需求指标",
        requirementUnknown ? BiDataStatus.INCOMPLETE : noFacts ? BiDataStatus.EMPTY : BiDataStatus.READY,
        requirementUnknown
            ? "需求身份尚未对当前事实完成派生回填，需求数和状态指标未知。未知事实数：" + calculation.unknownRequirementIdentityCount()
            : "需求状态沿客户问题范围内 F/A 规则计算。"));
    result.add(section("module-requirements", "模块需求分布",
        requirementUnknown ? BiDataStatus.INCOMPLETE : calculation.data().moduleDemand().isEmpty()
            ? BiDataStatus.EMPTY : BiDataStatus.READY,
        requirementUnknown ? "需求身份来源未完整回填，当前不绘制部分需求分布。" : "多模块议题分别进入每个关联模块；全局需求数不累加模块柱。"));
    boolean timeUnknown = calculation.unknownCreatedTimeCount() > 0 || calculation.unknownFixedTimeCount() > 0;
    result.add(section("daily-defect-trend", "缺陷日增与日修复",
        noDefects ? BiDataStatus.EMPTY : timeUnknown ? BiDataStatus.INCOMPLETE : BiDataStatus.READY,
        noDefects ? "当前筛选范围没有常规缺陷，不生成全零日期曲线。"
            : timeUnknown ? "存在缺少创建时间或精确完成事件时间的缺陷；受影响的序列整条留空，不按已知日期补点或补零，未知数量见顶部说明。"
            : "按当前事实回看自然日；撤销或重新修复可能改变历史日期结果。"));
    return List.copyOf(result);
  }

  private static BiPageSection chartSection(String key, String label, boolean noDefects) {
    return section(key, label, noDefects ? BiDataStatus.EMPTY : BiDataStatus.READY,
        noDefects ? "当前筛选范围没有常规缺陷。" : "图表使用完整范围数据，显示缩放不裁剪导出数据。");
  }

  private static BiPageSection section(String key, String label, BiDataStatus status, String message) {
    return new BiPageSection(key, label, status, message);
  }

  private static BiDataStatus overallStatus(Calculation calculation) {
    if (calculation.unknownRequirementIdentityCount() > 0
        || calculation.unknownCreatedTimeCount() > 0
        || calculation.unknownFixedTimeCount() > 0) return BiDataStatus.INCOMPLETE;
    return calculation.filteredFactCount() == 0 ? BiDataStatus.EMPTY : BiDataStatus.READY;
  }

  private static long metricValue(BiCustomerIssuePageData data, String key) {
    return data.defectMetrics().stream()
        .filter(metric -> metric.key().equals(key))
        .map(BiCustomerIssuePageData.Metric::value)
        .filter(java.util.Objects::nonNull)
        .findFirst().orElse(0L);
  }

  private static List<BiMetricTrace> traces() {
    return List.of(
        trace(List.of("CI-10", "CI-11", "CI-12", "CI-13", "CI-14", "CI-15", "CI-16", "CI-17"),
            List.of(field("议题身份", "source_system/source_instance/project_id/issue_id"),
                field("严重级别", "severity_level"), field("优先级", "priority_level"), field("状态", "bug_status")),
            "D去重；F、P、A各自沿客户问题规则；比率分母为对应D范围"),
        trace(List.of("CI-18", "CI-19"),
            List.of(field("创建时间", "created_at_source"), field("精确完成加标时间", "fixed_label_time"), field("状态", "bug_status")),
            "固定业务日；日修复需当前精确成员已修复/完成及最近有效加标时间"),
        trace(List.of("CI-20", "CI-21", "CI-22", "CI-23"),
            List.of(field("需求派生身份", "is_customer_requirement"), field("状态", "bug_status")),
            "N沿方案记录值；R1—R4仍待业务终审"),
        trace(List.of("CI-30", "CI-31", "CI-32", "CI-33", "CI-34", "CI-35", "CI-36", "CI-37", "CI-38", "CI-39", "CI-40", "CI-41", "CI-42", "CI-43", "CI-44"),
            List.of(field("模块", "module_names"), field("原因", "reason_category"),
                field("延期原因", "delay_cause/delay_reason"), field("指派人", "assignee_name")),
            "缺陷图按CI-01身份去重；多模块图按各模块展开；申请延期图使用D且A"),
        trace(List.of("CI-50", "CI-51"),
            List.of(field("创建时间", "created_at_source"), field("精确完成时间", "fixed_label_time"), field("当前状态", "bug_status")),
            "自然日轴从2026-01-01到固定业务日；按当前事实回看最近完成日期，不是历史事件流水"));
  }

  private static BiMetricTrace trace(List<String> ids, List<SourceField> fields, String formula) {
    return new BiMetricTrace(ids, "客户问题 ISSUE 事实", fields, formula, "BI CustomerIssueCalculator");
  }

  private static SourceField field(String chineseName, String englishName) {
    return new SourceField(chineseName, englishName);
  }

  private BiPageResponse<BiCustomerIssuePageData> unavailable(String message) {
    return BiPageResponse.create(PAGE_KEY, BiDataStatus.INCOMPLETE, "", "", RULE_VERSION,
        List.of(section("source-consistency", "来源一致性", BiDataStatus.INCOMPLETE, message)),
        traces(), null);
  }

  private BiPageResponse<BiCustomerIssuePageData> incompleteSource(String message) {
    return unavailable(message == null || message.isBlank() ? "客户问题事实来源尚未具备可读资格" : message);
  }

  private static String rangeDescription(Snapshot snapshot, Query query) {
    return "范围：客户问题 / " + snapshot.milestoneDisplayName()
        + "；客户：" + describe(query.customer())
        + "；模块：" + describe(query.module())
        + "；功能：" + describe(query.function())
        + "；业务日：" + snapshot.businessDate()
        + "；来源版本：" + snapshot.sourceVersion();
  }

  private static String describe(MemberSelection selection) {
    return switch (selection.kind()) {
      case ALL -> "全部";
      case MISSING -> "未标注成员";
      case VALUE -> selection.value();
    };
  }

  private enum Dimension {
    CUSTOMER("客户"), MODULE("模块"), FUNCTION("功能");
    private final String label;
    Dimension(String label) { this.label = label; }
    String label() { return label; }
  }

  /** 三类成员各自的稳定 kind/value 条件；空值一律显式 ALL，不接受部分参数。 */
  public record Query(
      String milestoneBusinessKey,
      MemberSelection customer,
      MemberSelection module,
      MemberSelection function) {
    public Query {
      customer = customer == null ? MemberSelection.all() : customer;
      module = module == null ? MemberSelection.all() : module;
      function = function == null ? MemberSelection.all() : function;
    }

    public static Query all() {
      return new Query(null, MemberSelection.all(), MemberSelection.all(), MemberSelection.all());
    }
  }

  private record Snapshot(
      String sourceVersion,
      LocalDate businessDate,
      String milestoneBusinessKey,
      String milestoneDisplayName,
      List<BiCustomerIssuePageData.RangeOption> milestones,
      List<CustomerIssueFact> facts) {}
}
