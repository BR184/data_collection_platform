package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticColumnGroup;
import com.data.collection.platform.entity.statistics.StatisticColumnLeaf;
import com.data.collection.platform.entity.statistics.StatisticDetailColumn;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.SortSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueDefectCauseBoardService extends AbstractStatisticBoardService
    implements RealtimeStatisticBoardSupport, RuleExplainableStatisticBoardSupport {
  private static final String BOARD_KEY = "customer-issue-defect-cause";
  private static final String RULE_VERSION = "customer-issue-defect-cause@2026-04-22-v1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "共计";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of("issues", "projects", "users", "label_links", "labels", "notes");
  private static final String FACT_SQL = """
      select issue_id as id, issue_iid as iid, title, project_id, project_name,
             coalesce(author_name,'') as author_name, updated_at_source as updated_at,
             closed_at_source as closed_at, coalesce(issue_state,'opened') as issue_state,
             coalesce(testing_phase,'') as testing_phase,
             coalesce(system_test_label,'') as system_test_label,
             coalesce(reason_category,'') as reason_category,
             coalesce(module_names,'') as module_names,
             coalesce(label_names,'') as label_names
        from issue_fact
       where deleted = false
      """;
  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      List.of(
          new StatisticDetailColumn("iid", "议题编号", 120, 120, true),
          new StatisticDetailColumn("title", "标题", null, 260, true),
          new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true),
          new StatisticDetailColumn("moduleNames", "模块", null, 180, true),
          new StatisticDetailColumn("projectName", "所属项目", null, 160, true),
          new StatisticDetailColumn("authorName", "创建人", 140, 140, true),
          new StatisticDetailColumn("state", "状态", 120, 120, true),
          new StatisticDetailColumn("updatedAt", "更新时间", 180, 180, true));
  private static final List<CauseMetricDefinition> CAUSE_METRICS =
      List.of(
          new CauseMetricDefinition("requirement_understanding", "需求理解偏差", "需求问题", "需求理解偏差"),
          new CauseMetricDefinition("new_requirement", "新增需求", "需求问题", "新增需求"),
          new CauseMetricDefinition("implementation_logic", "编码逻辑错误", "实现问题", "编码逻辑错误"),
          new CauseMetricDefinition("environment_deployment", "环境部署问题", "环境与部署", "环境部署问题"),
          new CauseMetricDefinition("algorithm_mechanism", "算法机制不支持", "环境与部署", "算法机制不支持"),
          new CauseMetricDefinition("other_reason", "其他原因", "环境与部署", null));

  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final FactBuildService factBuildService;
  private final IssueFactQueryService issueFactQueryService;

  public CustomerIssueDefectCauseBoardService(
      JsonUtils jsonUtils,
      GitlabMirrorSyncService gitlabMirrorSyncService,
      RealtimeWorkspaceService realtimeWorkspaceService,
      FactBuildService factBuildService,
      IssueFactQueryService issueFactQueryService) {
    super(jsonUtils);
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.factBuildService = factBuildService;
    this.issueFactQueryService = issueFactQueryService;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题缺陷原因分析",
        "基于 issue_fact 的模块维度客户问题缺陷原因分析。",
        "",
        "",
        "模块",
        List.of(),
        List.of(
            StatisticColumnGroup.withChildren(
                "requirement-problem",
                "需求问题",
                List.of(group("requirement-basic", "需求归类", List.of("requirement_understanding", "new_requirement")))),
            StatisticColumnGroup.withChildren(
                "implementation-problem",
                "实现问题",
                List.of(group("implementation-basic", "实现归类", List.of("implementation_logic")))),
            StatisticColumnGroup.withChildren(
                "environment-problem",
                "环境与部署",
                List.of(group("environment-basic", "环境归类", List.of("environment_deployment", "algorithm_mechanism", "other_reason")))),
            new StatisticColumnGroup("summary", "汇总", List.of(leaf("total", "总计", true, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题缺陷原因分析结果。");
  }

  private StatisticColumnGroup group(String key, String label, List<String> metricKeys) {
    List<StatisticColumnLeaf> columns = metricKeys.stream().map(this::leafByMetricKey).toList();
    return new StatisticColumnGroup(key, label, columns);
  }

  private StatisticColumnLeaf leafByMetricKey(String metricKey) {
    CauseMetricDefinition metric = metric(metricKey);
    return leaf(metric.key(), metric.label(), true, "count");
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters));
    StatisticBoardDefinition definition = buildDefinition();

    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.finalSources()) {
      for (String moduleName : issue.moduleNames()) {
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }

    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
            .map(AggregateBucket::toRowData)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (!snapshot.finalSources().isEmpty()) {
      rows.add(new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY).acceptAll(snapshot.finalSources()).toRowData());
    }

    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount =
        definition.columnGroups().stream()
            .flatMap(group -> group.leafColumns().stream())
            .mapToInt(column -> column.drilldown() ? 1 : 0)
            .sum();
    StatisticBoardMeta meta =
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount);
    return new StatisticBoardResponse(definition, withoutReservedFilters(filters), filterGroup, rows, meta);
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters())).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题缺陷原因分析明细",
        "展示当前模块与缺陷原因命中的 issue_fact 明细。",
        DETAIL_COLUMNS,
        pageSlice.records().stream().map(this::toDetailRecord).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending");
  }

  @Override
  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return realtimeWorkspaceService.getStatus(BOARD_KEY);
  }

  @Override
  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return realtimeWorkspaceService.requestRefresh(BOARD_KEY, this::refreshMirrorForRealtimeView);
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters));
    long moduleCount =
        snapshot.finalSources().stream().flatMap(issue -> issue.moduleNames().stream()).distinct().count();
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题缺陷原因分析规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact 的归一化事实字段，先排除系统测试或回归测试范围，再保留已经识别出缺陷原因的议题。",
        "同一条议题如果关联多个模块，会分别计入对应模块；共计行仍按议题本身去重统计。",
        List.of(
            snapshot.flowSteps().get(0),
            snapshot.flowSteps().get(1),
            snapshot.flowSteps().get(2),
            new StatisticRuleFlowStep(
                "group-by-module",
                "按模块聚合",
                "将保留下来的客户问题议题按 module_names 展开到各模块行，再按缺陷原因归类聚合。",
                snapshot.finalSources().size(),
                moduleCount,
                sample(snapshot.finalSources()))),
        List.of(
            new StatisticRuleMetricDefinition("requirement_understanding", "需求理解偏差", "按 issue_fact.reason_category = 需求理解偏差 统计。", "需求理解偏差数 = 当前模块内 reason_category 为需求理解偏差的议题数", null),
            new StatisticRuleMetricDefinition("new_requirement", "新增需求", "按 issue_fact.reason_category = 新增需求 统计。", "新增需求数 = 当前模块内 reason_category 为新增需求的议题数", null),
            new StatisticRuleMetricDefinition("implementation_logic", "编码逻辑错误", "按 issue_fact.reason_category = 编码逻辑错误 统计。", "编码逻辑错误数 = 当前模块内 reason_category 为编码逻辑错误的议题数", null),
            new StatisticRuleMetricDefinition("environment_deployment", "环境部署问题", "按 issue_fact.reason_category = 环境部署问题 统计。", "环境部署问题数 = 当前模块内 reason_category 为环境部署问题的议题数", null),
            new StatisticRuleMetricDefinition("algorithm_mechanism", "算法机制不支持", "按 issue_fact.reason_category = 算法机制不支持 统计。", "算法机制不支持数 = 当前模块内 reason_category 为算法机制不支持的议题数", null),
            new StatisticRuleMetricDefinition("other_reason", "其他原因", "统计当前稳定映射之外、但已识别出 reason_category 的其他原因。", "其他原因数 = 当前模块内 reason_category 非空且未命中标准映射的议题数", null),
            new StatisticRuleMetricDefinition("total", "总计", "统计当前模块命中缺陷原因分析范围的全部议题。", "总计 = 当前模块内 reason_category 非空的议题数", null)),
        null);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped = initial.stream().filter(IssueSource::inCustomerIssueScope).toList();
    List<IssueSource> withReason = scoped.stream().filter(IssueSource::hasReasonCategory).toList();
    return new RuleFlowSnapshot(
        withReason,
        List.of(
            step("source-load", "加载议题事实", "从 issue_fact 读取已经归一化的议题事实。", initial, initial.size()),
            step("scope-filter", "限定客户问题范围", "先排除命中系统测试或回归测试口径的议题，保留剩余 issue_fact 作为当前客户问题统计范围。", scoped, initial.size()),
            step("reason-category-filter", "保留已识别原因", "只保留 issue_fact.reason_category 非空的议题，避免把未归因数据混入原因分析。", withReason, scoped.size())));
  }

  private StatisticRuleFlowStep step(
      String key, String title, String description, List<IssueSource> output, long inputCount) {
    return new StatisticRuleFlowStep(key, title, description, inputCount, output.size(), sample(output));
  }

  private List<StatisticRuleFlowStepSample> sample(List<IssueSource> issues) {
    return issues.stream()
        .limit(3)
        .map(
            issue ->
                new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 原因: " + issue.reasonCategory() + " | 模块: " + String.join("、", issue.moduleNames())))
        .toList();
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey) || TOTAL_ROW_KEY.equals(rowKey) || issue.moduleNames().contains(rowKey);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "requirement_understanding" -> issue -> "需求理解偏差".equals(issue.reasonCategory());
      case "new_requirement" -> issue -> "新增需求".equals(issue.reasonCategory());
      case "implementation_logic" -> issue -> "编码逻辑错误".equals(issue.reasonCategory());
      case "environment_deployment" -> issue -> "环境部署问题".equals(issue.reasonCategory());
      case "algorithm_mechanism" -> issue -> "算法机制不支持".equals(issue.reasonCategory());
      case "other_reason" -> IssueSource::isOtherReason;
      case "total" -> issue -> true;
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "reasonCategory" -> SortSupport.nullableString(IssueSource::reasonCategory);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.moduleNames()));
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "state" -> SortSupport.nullableComparable(issue -> issue.isClosed() ? 1 : 0);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("iid", issue.iid());
    record.put("title", issue.title());
    record.put("reasonCategory", issue.reasonCategory());
    record.put("moduleNames", String.join("、", issue.moduleNames()));
    record.put("projectName", issue.projectName());
    record.put("authorName", issue.authorName());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    Long projectId = parseLong(queryFilters.get("projectId"));
    try {
      List<IssueSource> facts = ensureFactsReady(projectId, queryFilters);
      return facts.isEmpty() ? List.of() : facts;
    } catch (DataAccessException e) {
      log.warn("Failed to load issue facts", e);
      return List.of();
    }
  }

  private void refreshMirrorForRealtimeView() {
    try {
      gitlabMirrorSyncService.refreshTablesOnDemand(REALTIME_REFRESH_TABLES, BOARD_KEY);
      factBuildService.rebuildIssueFacts(false);
    } catch (Exception e) {
      log.warn("On-demand mirror refresh for {} failed", BOARD_KEY, e);
    }
  }

  private List<IssueSource> ensureFactsReady(Long projectId, Map<String, String> filters) {
    List<IssueSource> facts = loadSourcesFromFact(projectId, filters);
    if (!facts.isEmpty()) {
      return facts;
    }
    factBuildService.rebuildIssueFacts(true);
    return loadSourcesFromFact(projectId, filters);
  }

  private List<IssueSource> loadSourcesFromFact(Long projectId, Map<String, String> filters) {
    Map<String, String> mergedFilters = new LinkedHashMap<>();
    if (filters != null) {
      mergedFilters.putAll(filters);
    }
    if (projectId != null) {
      mergedFilters.put("projectId", String.valueOf(projectId));
    }
    return issueFactQueryService.query(FACT_SQL, mergedFilters, this::mapIssueFact);
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        rs.getLong("id"),
        rs.getInt("iid"),
        text(rs.getString("title"), ""),
        text(rs.getString("project_name"), "未命名项目"),
        text(rs.getString("author_name"), ""),
        time(rs.getTimestamp("updated_at")),
        time(rs.getTimestamp("closed_at")),
        text(rs.getString("issue_state"), "opened"),
        text(rs.getString("testing_phase"), ""),
        text(rs.getString("system_test_label"), ""),
        text(rs.getString("reason_category"), ""),
        split(rs.getString("module_names")),
        split(rs.getString("label_names")));
  }

  private CauseMetricDefinition metric(String key) {
    return CAUSE_METRICS.stream()
        .filter(metric -> metric.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown metric key: " + key));
  }

  private Long parseLong(String value) {
    try {
      return StringUtils.hasText(value) ? Long.parseLong(value.trim()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String text(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private LocalDateTime time(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private List<String> split(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String value : raw.split(",")) {
      String trimmed = value == null ? "" : value.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return List.copyOf(values);
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private record AggregateBucket(String rowLabel, String rowKey, List<IssueSource> issues) {
    AggregateBucket(String rowLabel) {
      this(rowLabel, rowLabel, new ArrayList<>());
    }

    AggregateBucket(String rowLabel, String rowKey) {
      this(rowLabel, rowKey, new ArrayList<>());
    }

    AggregateBucket acceptAll(List<IssueSource> sourceIssues) {
      issues.addAll(sourceIssues);
      return this;
    }

    void accept(IssueSource issue) {
      issues.add(issue);
    }

    StatisticRowData toRowData() {
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              cell("requirement_understanding", countByReason("需求理解偏差"), true),
              cell("new_requirement", countByReason("新增需求"), true),
              cell("implementation_logic", countByReason("编码逻辑错误"), true),
              cell("environment_deployment", countByReason("环境部署问题"), true),
              cell("algorithm_mechanism", countByReason("算法机制不支持"), true),
              cell("other_reason", issues.stream().filter(IssueSource::isOtherReason).count(), true),
              cell("total", issues.size(), true)));
    }

    private long countByReason(String reasonCategory) {
      return issues.stream().filter(issue -> reasonCategory.equals(issue.reasonCategory())).count();
    }

    private StatisticCellData cell(String key, long numericValue, boolean drilldown) {
      return new StatisticCellData(
          key,
          numericValue,
          count(numericValue),
          drilldown,
          drilldown ? "issue-list" : null,
          Map.of("rowKey", rowKey));
    }
  }

  private record IssueSource(
      Long id,
      Integer iid,
      String title,
      String projectName,
      String authorName,
      LocalDateTime updatedAt,
      LocalDateTime closedAt,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String reasonCategory,
      List<String> moduleNames,
      List<String> labels) {
    boolean inCustomerIssueScope() {
      return !inSystemTestScope();
    }

    boolean inSystemTestScope() {
      return hasScope(testingPhase) || hasScope(systemTestLabel) || labels.stream().anyMatch(this::hasScope);
    }

    boolean hasReasonCategory() {
      return StringUtils.hasText(reasonCategory);
    }

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isOtherReason() {
      if (!hasReasonCategory()) {
        return false;
      }
      return CAUSE_METRICS.stream()
          .filter(metric -> metric.reasonCategory() != null)
          .noneMatch(metric -> metric.reasonCategory().equals(reasonCategory));
    }

    private boolean hasScope(String value) {
      return StringUtils.hasText(value) && (value.contains("系统测试") || value.contains("回归测试"));
    }
  }

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}

  private record CauseMetricDefinition(String key, String label, String groupLabel, String reasonCategory) {}
}
