package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
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
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.SortSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueResponseEfficiencyBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport {
  private static final String BOARD_KEY = "customer-issue-response-efficiency";
  private static final String RULE_VERSION = "customer-issue-response-efficiency@2026-04-22-v1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final String EMPTY_MODULE_LABEL = "未标记模块";
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String FACT_SQL =
      """
      select project_id,
             coalesce(project_name, '') as project_name,
             issue_id,
             issue_iid,
             coalesce(title, '') as title,
             coalesce(issue_state, 'opened') as issue_state,
             coalesce(testing_phase, '') as testing_phase,
             coalesce(system_test_label, '') as system_test_label,
             coalesce(severity_level, '') as severity_level,
             coalesce(priority_level, '') as priority_level,
             coalesce(bug_status, '') as bug_status,
             coalesce(category, '') as category,
             coalesce(reason_category, '') as reason_category,
             coalesce(milestone_title, '') as milestone_title,
             coalesce(author_name, '') as author_name,
             coalesce(assignee_name, '') as assignee_name,
             coalesce(module_names, '') as module_names,
             coalesce(label_names, '') as label_names,
             coalesce(has_response, false) as has_response,
             coalesce(response_overdue, false) as response_overdue,
             coalesce(is_response_delayed, false) as is_response_delayed,
             coalesce(is_resolve_delayed, false) as is_resolve_delayed,
             coalesce(resolve_sla_days, 0) as resolve_sla_days,
             resolve_deadline_at,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
       where deleted = false
      """;

  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      List.of(
          new StatisticDetailColumn("iid", "议题编号", 120, 120, true),
          new StatisticDetailColumn("title", "标题", null, 260, true),
          new StatisticDetailColumn("moduleNames", "模块", null, 180, true),
          new StatisticDetailColumn("projectName", "所属项目", null, 160, true),
          new StatisticDetailColumn("responseStatus", "响应状态", 140, 140, true),
          new StatisticDetailColumn("resolveStatus", "解决状态", 140, 140, true),
          new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true),
          new StatisticDetailColumn("authorName", "创建人", 140, 140, true),
          new StatisticDetailColumn("updatedAt", "更新时间", 180, 180, true));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;

  public CustomerIssueResponseEfficiencyBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题缺陷响应效率",
        "基于 issue_fact 的客户问题响应、延期与解决 SLA 统计。",
        "",
        "",
        "模块",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text("moduleName", "模块名称", 180),
            StatisticFilterFieldFactory.select(
                "severityLevel",
                "严重程度",
                180,
                List.of(
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("一级缺陷", "LEVEL1"),
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("二级缺陷", "LEVEL2"),
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("三级缺陷", "LEVEL3"),
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("建议类", "SUGGESTION"))),
            StatisticFilterFieldFactory.select(
                "priorityLevel",
                "优先级",
                160,
                List.of(
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("P1", "P1"),
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("P2", "P2"),
                    new com.data.collection.platform.entity.statistics.StatisticFilterOption("P3", "P3")))),
        List.of(
            new StatisticColumnGroup(
                "response",
                "响应情况",
                List.of(
                    leaf("total", "缺陷总数", true, "count"),
                    leaf("responded", "已响应", true, "count"),
                    leaf("unresponded", "未响应", true, "count"),
                    leaf("response_overdue", "响应超期", true, "count"),
                    leaf("response_delayed", "响应延期", true, "count"),
                    leaf("response_rate", "响应率", false, "ratio"))),
            new StatisticColumnGroup(
                "resolve",
                "解决 SLA",
                List.of(
                    leaf("resolve_delayed", "解决延期", true, "count"),
                    leaf("resolve_on_time", "解决未延期", true, "count"),
                    leaf("resolve_delay_rate", "解决延期率", false, "ratio")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题响应效率数据。");
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters));
    StatisticBoardDefinition definition = buildDefinition();
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.finalSources()) {
      for (String moduleName : issue.displayModuleNames()) {
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
    return new StatisticBoardResponse(
        definition,
        withoutReservedFilters(filters),
        filterGroup,
        rows,
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount));
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters())).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题响应效率明细",
        "展示当前模块与响应效率指标命中的 issue_fact 明细。",
        DETAIL_COLUMNS,
        pageSlice.records().stream().map(this::toDetailRecord).toList(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        StringUtils.hasText(request.sortField()) ? request.sortField() : "updatedAt",
        "ascending".equalsIgnoreCase(request.sortOrder()) ? "ascending" : "descending");
  }

  @Override
  public StatisticBoardRuleExplanationResponse getRuleExplanation(Map<String, String> filters) {
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters));
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题缺陷响应效率规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact，先使用 CustomerIssueScopeProfile 限定客户问题范围，再按模块展开响应与 SLA 指标。",
        "同一条议题如果关联多个模块，会分别计入模块行；总计行按议题本身统计。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "response_rate", "响应率", "已响应数量占当前范围缺陷总数的比例。", "响应率 = 已响应 / 缺陷总数", null),
            new StatisticRuleMetricDefinition(
                "response_delayed", "响应延期", "统计 issue_fact.is_response_delayed = true 的议题。", "响应延期数 = count(is_response_delayed)", null),
            new StatisticRuleMetricDefinition(
                "resolve_delay_rate", "解决延期率", "解决延期数量占当前范围缺陷总数的比例。", "解决延期率 = 解决延期 / 缺陷总数", null)),
        null);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    return new RuleFlowSnapshot(
        scoped,
        List.of(
            step("source-load", "加载议题事实", "从 issue_fact 读取已归一化的议题事实。", initial, initial.size()),
            step("scope-filter", "限定客户问题范围", "复用 CustomerIssueScopeProfile 收口客户问题范围。", scoped, initial.size()),
            new StatisticRuleFlowStep(
                "module-expand",
                "按模块展开",
                "同一条议题可归属多个模块；未标记模块的议题会进入“未标记模块”行。",
                scoped.size(),
                scoped.stream().mapToLong(issue -> issue.displayModuleNames().size()).sum(),
                sample(scoped))));
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
                    issue.title()
                        + " | 响应: "
                        + (issue.hasResponse() ? "已响应" : "未响应")
                        + (issue.responseDelayed() ? " | 响应延期" : "")))
        .toList();
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    try {
      return issueFactQueryService.query(FACT_SQL, queryFilters, this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue response efficiency facts", error);
      return List.of();
    }
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        rs.getLong("project_id"),
        StatisticSourceValueSupport.text(rs.getString("project_name")),
        rs.getLong("issue_id"),
        rs.getInt("issue_iid"),
        StatisticSourceValueSupport.text(rs.getString("title")),
        StatisticSourceValueSupport.text(rs.getString("issue_state")),
        StatisticSourceValueSupport.text(rs.getString("testing_phase")),
        StatisticSourceValueSupport.text(rs.getString("system_test_label")),
        StatisticSourceValueSupport.text(rs.getString("severity_level")),
        StatisticSourceValueSupport.text(rs.getString("priority_level")),
        StatisticSourceValueSupport.text(rs.getString("bug_status")),
        StatisticSourceValueSupport.text(rs.getString("category")),
        StatisticSourceValueSupport.text(rs.getString("reason_category")),
        StatisticSourceValueSupport.text(rs.getString("milestone_title")),
        StatisticSourceValueSupport.text(rs.getString("author_name")),
        StatisticSourceValueSupport.text(rs.getString("assignee_name")),
        StatisticSourceValueSupport.split(rs.getString("module_names")),
        StatisticSourceValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("has_response"),
        rs.getBoolean("response_overdue"),
        rs.getBoolean("is_response_delayed"),
        rs.getBoolean("is_resolve_delayed"),
        rs.getInt("resolve_sla_days"),
        StatisticSourceValueSupport.time(rs.getTimestamp("resolve_deadline_at")),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at_source")));
  }

  private boolean matchesRow(IssueSource issue, String rowKey) {
    return !StringUtils.hasText(rowKey)
        || TOTAL_ROW_KEY.equals(rowKey)
        || issue.displayModuleNames().contains(rowKey);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "responded" -> IssueSource::hasResponse;
      case "unresponded" -> issue -> !issue.hasResponse();
      case "response_overdue" -> IssueSource::responseOverdue;
      case "response_delayed" -> IssueSource::responseDelayed;
      case "resolve_delayed" -> IssueSource::resolveDelayed;
      case "resolve_on_time" -> issue -> !issue.resolveDelayed();
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.displayModuleNames()));
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "responseStatus" -> SortSupport.nullableString(IssueSource::responseStatus);
          case "resolveStatus" -> SortSupport.nullableString(IssueSource::resolveStatus);
          case "reasonCategory" -> SortSupport.nullableString(IssueSource::reasonCategory);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("iid", issue.iid());
    record.put("title", issue.title());
    record.put("moduleNames", String.join("、", issue.displayModuleNames()));
    record.put("projectName", issue.projectName());
    record.put("responseStatus", issue.responseStatus());
    record.put("resolveStatus", issue.resolveStatus());
    record.put("reasonCategory", StringUtils.hasText(issue.reasonCategory()) ? issue.reasonCategory() : "未归因");
    record.put("authorName", issue.authorName());
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private static String rate(long numerator, long denominator) {
    return StatisticMetricCalculator.rate(numerator, denominator);
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
      long total = issues.size();
      long responded = issues.stream().filter(IssueSource::hasResponse).count();
      long unresponded = total - responded;
      long responseOverdue = issues.stream().filter(IssueSource::responseOverdue).count();
      long responseDelayed = issues.stream().filter(IssueSource::responseDelayed).count();
      long resolveDelayed = issues.stream().filter(IssueSource::resolveDelayed).count();
      long resolveOnTime = total - resolveDelayed;
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              countCell("total", total),
              countCell("responded", responded),
              countCell("unresponded", unresponded),
              countCell("response_overdue", responseOverdue),
              countCell("response_delayed", responseDelayed),
              rateCell("response_rate", responded, total),
              countCell("resolve_delayed", resolveDelayed),
              countCell("resolve_on_time", resolveOnTime),
              rateCell("resolve_delay_rate", resolveDelayed, total)));
    }

    private StatisticCellData countCell(String key, long numericValue) {
      return new StatisticCellData(
          key, numericValue, count(numericValue), true, "issue-list", Map.of("rowKey", rowKey));
    }

    private StatisticCellData rateCell(String key, long numerator, long denominator) {
      return new StatisticCellData(
          key, numerator, rate(numerator, denominator), false, null, Map.of("rowKey", rowKey));
    }
  }

  private record IssueSource(
      Long projectId,
      String projectName,
      Long id,
      Integer iid,
      String title,
      String issueState,
      String testingPhase,
      String systemTestLabel,
      String severityLevel,
      String priorityLevel,
      String bugStatus,
      String category,
      String reasonCategory,
      String milestoneTitle,
      String authorName,
      String assigneeName,
      List<String> moduleNames,
      List<String> labels,
      boolean hasResponse,
      boolean responseOverdue,
      boolean responseDelayed,
      boolean resolveDelayed,
      int resolveSlaDays,
      LocalDateTime resolveDeadlineAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      LocalDateTime closedAt) {
    IssueScopeContext scopeContext() {
      return new IssueScopeContext(
          projectId, projectName, milestoneTitle, testingPhase, systemTestLabel, createdAt, labels);
    }

    List<String> displayModuleNames() {
      return moduleNames.isEmpty() ? List.of(EMPTY_MODULE_LABEL) : moduleNames;
    }

    String responseStatus() {
      if (!hasResponse) {
        return responseDelayed || responseOverdue ? "未响应/已延期" : "未响应";
      }
      return responseDelayed || responseOverdue ? "已响应/曾超期" : "已响应";
    }

    String resolveStatus() {
      return resolveDelayed ? "解决延期" : "解决未延期";
    }
  }

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
