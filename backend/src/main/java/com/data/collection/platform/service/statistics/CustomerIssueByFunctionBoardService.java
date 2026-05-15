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
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
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
public class CustomerIssueByFunctionBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport {
  private static final String BOARD_KEY = "customer-issue-by-function";
  private static final String RULE_VERSION = "customer-issue-by-function@2026-04-22-v1";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final String EMPTY_MODULE_LABEL = "未标记模块";
  private static final String ROW_KEY_SEPARATOR = "||";
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
             coalesce(function_name, '') as function_name,
             coalesce(label_names, '') as label_names,
             coalesce(is_fixed, false) as is_fixed,
             coalesce(delay_issue, false) as delay_issue,
             coalesce(is_response_delayed, false) as is_response_delayed,
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
          new StatisticDetailColumn("functionName", "功能", 180, 180, true),
          new StatisticDetailColumn("projectName", "所属项目", null, 160, true),
          new StatisticDetailColumn("severityLevel", "严重程度", 140, 140, true),
          new StatisticDetailColumn("priorityLevel", "优先级", 120, 120, true),
          new StatisticDetailColumn("state", "状态", 120, 120, true),
          new StatisticDetailColumn("reasonCategory", "缺陷原因", 160, 160, true),
          new StatisticDetailColumn("updatedAt", "更新时间", 180, 180, true));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;

  public CustomerIssueByFunctionBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.issueLinkSupport = issueLinkSupport;
  }

  @Override
  public String boardKey() {
    return BOARD_KEY;
  }

  @Override
  protected StatisticBoardDefinition buildDefinition() {
    return new StatisticBoardDefinition(
        BOARD_KEY,
        "客户问题按功能展示缺陷数量",
        "基于 issue_fact.function_name 的客户问题模块/功能维度缺陷数量统计。",
        "",
        "",
        "模块 / 功能",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text("moduleName", "模块名称", 180),
            StatisticFilterFieldFactory.text("functionName", "功能名称", 180),
            StatisticFilterFieldFactory.text("milestoneTitle", "里程碑", 180),
            StatisticFilterFieldFactory.select(
                "severityLevel",
                "严重程度",
                180,
                List.of(
                    new StatisticFilterOption("一级缺陷", "LEVEL1"),
                    new StatisticFilterOption("二级缺陷", "LEVEL2"),
                    new StatisticFilterOption("三级缺陷", "LEVEL3"),
                    new StatisticFilterOption("建议类", "SUGGESTION")))),
        List.of(
            new StatisticColumnGroup(
                "quantity",
                "缺陷数量",
                List.of(
                    leaf("total", "问题数量", true, "count"),
                    leaf("fixed", "已修复/关闭", true, "count"),
                    leaf("open", "未关闭", true, "count"),
                    leaf("delay", "申请延期", true, "count"),
                    leaf("response_delayed", "响应延期", true, "count"),
                    leaf("function_ratio", "功能占比", false, "ratio"))),
            new StatisticColumnGroup(
                "severity",
                "严重程度",
                List.of(
                    leaf("level1", "一级缺陷", true, "count"),
                    leaf("level2", "二级缺陷", true, "count"),
                    leaf("level3", "三级缺陷", true, "count"),
                    leaf("suggestion", "建议类", true, "count")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题功能缺陷数量数据。");
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
        String rowKey = rowKey(moduleName, issue.functionName());
        String rowLabel = moduleName + " / " + issue.functionName();
        buckets.computeIfAbsent(rowKey, key -> new AggregateBucket(rowLabel, key)).accept(issue);
      }
    }
    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(
                Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(bucket -> bucket.issues().size(), Comparator.reverseOrder()))
            .map(bucket -> bucket.toRowData(snapshot.finalSources().size()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (!snapshot.finalSources().isEmpty()) {
      rows.add(
          new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY)
              .acceptAll(snapshot.finalSources())
              .toRowData(snapshot.finalSources().size()));
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
        "客户问题功能缺陷明细",
        "展示当前模块/功能与指标命中的 issue_fact 明细。",
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
        "客户问题按功能展示缺陷数量规则说明",
        RULE_VERSION,
        "当前统计基于 issue_fact，先使用 CustomerIssueScopeProfile 限定客户问题范围，再保留已识别出功能名的议题。",
        "功能名来自 issue 标题开头的全角书名号片段，例如【草图约束】；同一条议题如属于多个模块，会分别计入对应模块/功能行，总计行按议题本身统计。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "total", "问题数量", "统计当前模块/功能下的客户问题缺陷数量。", "问题数量 = count(issue_fact where customer scope and function_name is not empty)", null),
            new StatisticRuleMetricDefinition(
                "function_ratio", "功能占比", "当前功能缺陷数量占客户问题功能缺陷总数的比例。", "功能占比 = 当前模块/功能问题数量 / 功能缺陷总数", null),
            new StatisticRuleMetricDefinition(
                "severity", "严重程度", "按 issue_fact.severity_level 拆分一级、二级、三级与建议类。", "各严重程度数量 = count(severity_level)", null)),
        null);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> withFunction = scoped.stream().filter(issue -> StringUtils.hasText(issue.functionName())).toList();
    return new RuleFlowSnapshot(
        withFunction,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题事实",
                "从 issue_fact 读取已归一化的议题事实。",
                initial.size(),
                initial,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "复用 CustomerIssueScopeProfile 收口客户问题范围。",
                initial.size(),
                scoped,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "function-filter",
                "保留已识别功能",
                "只保留 issue_fact.function_name 非空的议题。",
                scoped.size(),
                withFunction,
                this::toRuleFlowSample
            ),
            StatisticRuleFlowSupport.step(
                "module-function-expand",
                "按模块/功能展开",
                "将客户问题议题展开到 module_names + function_name 组合；未标记模块的议题归入“未标记模块”。",
                withFunction.size(),
                withFunction.stream().mapToLong(issue -> issue.displayModuleNames().size()).sum(),
                withFunction,
                this::toRuleFlowSample
            )));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
                    "#" + issue.iid() + " " + issue.projectName(),
                    issue.title() + " | 功能: " + issue.functionName());
  }
  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    try {
      return issueFactQueryService.query(FACT_SQL, queryFilters, this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue by function facts", error);
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
        StatisticSourceValueSupport.text(rs.getString("function_name")),
        StatisticSourceValueSupport.split(rs.getString("label_names")),
        rs.getBoolean("is_fixed"),
        rs.getBoolean("delay_issue"),
        rs.getBoolean("is_response_delayed"),
        StatisticSourceValueSupport.time(rs.getTimestamp("created_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("updated_at_source")),
        StatisticSourceValueSupport.time(rs.getTimestamp("closed_at_source")));
  }

  private boolean matchesRow(IssueSource issue, String requestedRowKey) {
    if (!StringUtils.hasText(requestedRowKey) || TOTAL_ROW_KEY.equals(requestedRowKey)) {
      return true;
    }
    return issue.displayModuleNames().stream()
        .map(moduleName -> rowKey(moduleName, issue.functionName()))
        .anyMatch(requestedRowKey::equals);
  }

  private Predicate<IssueSource> matchesMetric(String columnKey) {
    return switch (columnKey) {
      case "fixed" -> IssueSource::isSolvedLike;
      case "open" -> issue -> !issue.isClosed();
      case "delay" -> IssueSource::delayIssue;
      case "response_delayed" -> IssueSource::responseDelayed;
      case "level1" -> issue -> issue.isSeverity("LEVEL1");
      case "level2" -> issue -> issue.isSeverity("LEVEL2");
      case "level3" -> issue -> issue.isSeverity("LEVEL3");
      case "suggestion" -> issue -> issue.isSeverity("SUGGESTION");
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.displayModuleNames()));
          case "functionName" -> SortSupport.nullableString(IssueSource::functionName);
          case "projectName" -> SortSupport.nullableString(IssueSource::projectName);
          case "severityLevel" -> SortSupport.nullableString(IssueSource::severityLevel);
          case "priorityLevel" -> SortSupport.nullableString(IssueSource::priorityLevel);
          case "state" -> SortSupport.nullableComparable(issue -> issue.isClosed() ? 1 : 0);
          case "reasonCategory" -> SortSupport.nullableString(IssueSource::reasonCategory);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueFields(record, issue.iid(), issue.projectId(), issue.projectName());
    record.put("title", issue.title());
    record.put("moduleNames", String.join("、", issue.displayModuleNames()));
    record.put("functionName", issue.functionName());
    record.put("projectName", issue.projectName());
    record.put("severityLevel", issue.severityLevel());
    record.put("priorityLevel", issue.priorityLevel());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("reasonCategory", StringUtils.hasText(issue.reasonCategory()) ? issue.reasonCategory() : "未归因");
    record.put("updatedAt", issue.updatedAt() == null ? "" : DATE_TIME_FORMATTER.format(issue.updatedAt()));
    return record;
  }

  private String rowKey(String moduleName, String functionName) {
    return moduleName + ROW_KEY_SEPARATOR + functionName;
  }

  private static String count(long value) {
    return StatisticMetricCalculator.count(value);
  }

  private static String rate(long numerator, long denominator) {
    return StatisticMetricCalculator.rate(numerator, denominator);
  }

  private record AggregateBucket(String rowLabel, String rowKey, List<IssueSource> issues) {
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

    StatisticRowData toRowData(long overall) {
      long total = issues.size();
      long fixed = issues.stream().filter(IssueSource::isSolvedLike).count();
      long open = issues.stream().filter(issue -> !issue.isClosed()).count();
      long delay = issues.stream().filter(IssueSource::delayIssue).count();
      long responseDelayed = issues.stream().filter(IssueSource::responseDelayed).count();
      long level1 = issues.stream().filter(issue -> issue.isSeverity("LEVEL1")).count();
      long level2 = issues.stream().filter(issue -> issue.isSeverity("LEVEL2")).count();
      long level3 = issues.stream().filter(issue -> issue.isSeverity("LEVEL3")).count();
      long suggestion = issues.stream().filter(issue -> issue.isSeverity("SUGGESTION")).count();
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              countCell("total", total),
              countCell("fixed", fixed),
              countCell("open", open),
              countCell("delay", delay),
              countCell("response_delayed", responseDelayed),
              rateCell("function_ratio", total, overall),
              countCell("level1", level1),
              countCell("level2", level2),
              countCell("level3", level3),
              countCell("suggestion", suggestion)));
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
      String functionName,
      List<String> labels,
      boolean fixed,
      boolean delayIssue,
      boolean responseDelayed,
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

    boolean isClosed() {
      return closedAt != null || "closed".equalsIgnoreCase(issueState);
    }

    boolean isSolvedLike() {
      return fixed || isClosed();
    }

    boolean isSeverity(String severity) {
      return severity.equalsIgnoreCase(severityLevel);
    }
  }

  private record RuleFlowSnapshot(List<IssueSource> finalSources, List<StatisticRuleFlowStep> flowSteps) {}
}
