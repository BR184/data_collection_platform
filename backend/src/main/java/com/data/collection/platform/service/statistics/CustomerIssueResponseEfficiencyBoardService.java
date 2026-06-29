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
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticFilterOption;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.IssueScopeContext;
import com.data.collection.platform.service.PageSlice;
import com.data.collection.platform.service.PageSliceSupport;
import com.data.collection.platform.service.SortSupport;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CustomerIssueResponseEfficiencyBoardService extends AbstractStatisticBoardService
    implements RuleExplainableStatisticBoardSupport, StatisticBoardSnapshotRefresher {
  private static final String BOARD_KEY = "customer-issue-response-efficiency";
  private static final String RULE_VERSION = "customer-issue-response-efficiency@2026-06-18-v2";
  private static final String TOTAL_ROW_KEY = "__total__";
  private static final String TOTAL_ROW_LABEL = "总计";
  private static final String EMPTY_MODULE_LABEL = IssueDisplayValueSupport.EMPTY_MODULE_LABEL;
  private static final String FIXED_STATUS = "已修复/完成";
  private static final long LEGACY_CC_PRODUCT_PROJECT_ID = 325L;
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final String FACT_SQL =
      """
      select source_instance,
             project_id,
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
             coalesce(is_excluded, false) as is_excluded,
             research_template_time,
             fixed_label_time,
             created_at_source,
             updated_at_source,
             closed_at_source
        from issue_fact
       where deleted = false
      """;

  private static final List<StatisticDetailColumn> DETAIL_COLUMNS =
      StatisticIssueDetailColumns.customerIssue(
          "议题标题",
          "模块名",
          List.of(
              StatisticIssueDetailColumns.state("议题状态"),
              StatisticIssueDetailColumns.severity("severityLevel", "严重程度", 140),
              StatisticIssueDetailColumns.bugStatus()),
          List.of(
              new StatisticDetailColumn("researchTemplateTime", "调研模板回复时间", 180, 180, true),
              new StatisticDetailColumn("fixedLabelTime", "已修复标签时间", 180, 180, true),
              StatisticIssueDetailColumns.author("议题提交人"),
              StatisticIssueDetailColumns.assignee("议题处理人", 160)),
          List.of(
              StatisticIssueDetailColumns.milestone("产品版本"),
              StatisticIssueDetailColumns.createdAt()));

  private final IssueFactQueryService issueFactQueryService;
  private final CustomerIssueScopeProfile customerIssueScopeProfile;
  private final StatisticIssueLinkSupport issueLinkSupport;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;
  private final CustomerIssueMilestoneCatalogService milestoneCatalogService;
  private final StatisticBoardSnapshotService snapshotService;
  private final StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  public CustomerIssueResponseEfficiencyBoardService(
      JsonUtils jsonUtils,
      IssueFactQueryService issueFactQueryService,
      CustomerIssueScopeProfile customerIssueScopeProfile,
      StatisticIssueLinkSupport issueLinkSupport,
      SystemTestPhaseScopeResolver phaseScopeResolver,
      CustomerIssueMilestoneCatalogService milestoneCatalogService,
      StatisticBoardSnapshotService snapshotService,
      StatisticBoardSnapshotRequestFactory snapshotRequestFactory) {
    super(jsonUtils);
    this.issueFactQueryService = issueFactQueryService;
    this.customerIssueScopeProfile = customerIssueScopeProfile;
    this.issueLinkSupport = issueLinkSupport;
    this.phaseScopeResolver = phaseScopeResolver;
    this.milestoneCatalogService = milestoneCatalogService;
    this.snapshotService = snapshotService;
    this.snapshotRequestFactory = snapshotRequestFactory;
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
        "按老平台口径展示 CC_Product 客户问题模块维度响应周期和解决周期。",
        "",
        "",
        "模块",
        List.of(
            StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
            StatisticFilterFieldFactory.text(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, "测试阶段", 200),
            StatisticFilterFieldFactory.text("milestoneTitle", "产品版本", 180),
            StatisticFilterFieldFactory.text("moduleName", "模块名", 180),
            StatisticFilterFieldFactory.select(
                "severityLevel",
                "严重程度",
                180,
                IssueDisplayValueSupport.severityFilterOptions(true)),
            StatisticFilterFieldFactory.select(
                "priorityLevel",
                "紧急程度",
                160,
                List.of(
                    new StatisticFilterOption("P1", "P1"),
                    new StatisticFilterOption("P2", "P2"),
                    new StatisticFilterOption("P3", "P3"))),
            StatisticFilterFieldFactory.text("issueState", "议题状态", 160),
            StatisticFilterFieldFactory.text("bugStatus", "测试状态", 160),
            StatisticFilterFieldFactory.text("authorName", "议题提交人", 160),
            StatisticFilterFieldFactory.text("assigneeName", "议题处理人", 160)),
        List.of(
            new StatisticColumnGroup(
                "legacy-fields",
                "缺陷响应效率",
                List.of(
                    leaf("milestone_title", "产品版本", false, "text"),
                    leaf("response_cycle_hours", "响应周期（小时）", true, "duration"),
                    leaf("resolution_cycle_days", "解决周期（天）", true, "duration")))),
        DETAIL_COLUMNS,
        10,
        "当前没有可展示的客户问题响应效率数据。");
  }

  @Override
  protected StatisticBoardResponse doLoadBoard(
      Map<String, String> filters, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    Map<String, String> snapshotFilters = customerSnapshotFilters(filters, effectiveFilterGroup);
    return snapshotService.readOrRefresh(
        snapshotRequest(snapshotFilters, effectiveFilterGroup, buildDefinition()),
        () -> buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardResponse buildBoardResponse(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    long startedAt = System.currentTimeMillis();
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    StatisticBoardDefinition definition = buildDefinition();
    Map<String, AggregateBucket> buckets = new LinkedHashMap<>();
    for (IssueSource issue : snapshot.rowSources()) {
      for (String moduleName : issue.displayModuleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        buckets.computeIfAbsent(moduleName, AggregateBucket::new);
      }
    }
    if (StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(EMPTY_MODULE_LABEL, effectiveFilterGroup)) {
      buckets.computeIfAbsent(EMPTY_MODULE_LABEL, AggregateBucket::new);
    }
    for (IssueSource issue : snapshot.finalSources()) {
      for (String moduleName : issue.displayModuleNames()) {
        if (!StatisticExplicitModuleFilterSupport.matchesExplicitModuleFilter(moduleName, effectiveFilterGroup)) {
          continue;
        }
        buckets.computeIfAbsent(moduleName, AggregateBucket::new).accept(issue);
      }
    }
    List<StatisticRowData> rows =
        buckets.values().stream()
            .sorted(Comparator.comparing(AggregateBucket::rowLabel, String.CASE_INSENSITIVE_ORDER))
            .map(AggregateBucket::toRowData)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    rows.add(new AggregateBucket(TOTAL_ROW_LABEL, TOTAL_ROW_KEY).acceptAll(snapshot.finalSources()).toRowData());
    int columnCount = definition.columnGroups().stream().mapToInt(StatisticColumnGroup::columnCount).sum();
    int drilldownCount =
        definition.columnGroups().stream()
            .flatMap(group -> group.leafColumns().stream())
            .mapToInt(column -> column.drilldown() ? 1 : 0)
            .sum();
    return new StatisticBoardResponse(
        definition,
        withoutReservedFilters(filters),
        effectiveFilterGroup,
        rows,
        new StatisticBoardMeta(
            LocalDateTime.now(),
            System.currentTimeMillis() - startedAt,
            rows.size(),
            columnCount,
            drilldownCount));
  }

  @Override
  public void refreshSnapshots(StatisticBoardSnapshotRefresher.RefreshContext context) {
    if (!context.affectsIssues()) {
      return;
    }
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(emptyFilterGroup());
    Map<String, String> filters = customerSnapshotFilters(Map.of(), effectiveFilterGroup);
    snapshotService.save(
        snapshotRequest(filters, effectiveFilterGroup, buildDefinition()),
        buildBoardResponse(filters, effectiveFilterGroup));
  }

  private StatisticBoardSnapshotService.SnapshotRequest snapshotRequest(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup,
      StatisticBoardDefinition definition) {
    String selectedMilestone = CustomerIssueMilestoneFilterSupport.selectedMilestone(effectiveFilterGroup);
    return snapshotRequestFactory.issueRequest(
        BOARD_KEY,
        RULE_VERSION,
        "project=325;milestone=" + (StringUtils.hasText(selectedMilestone) ? selectedMilestone : "none"),
        filters,
        definition,
        effectiveFilterGroup);
  }

  private Map<String, String> customerSnapshotFilters(
      Map<String, String> filters,
      StatisticFilterGroup effectiveFilterGroup) {
    return CustomerIssueMilestoneFilterSupport
        .snapshotFilters(withoutReservedFilters(filters), effectiveFilterGroup, 325L)
        .filters();
  }

  @Override
  protected StatisticDetailResponse doLoadDetail(
      StatisticDetailRequest request, StatisticFilterGroup filterGroup) {
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    List<IssueSource> scoped =
        buildRuleFlowSnapshot(loadSources(request.filters()), effectiveFilterGroup).finalSources().stream()
            .filter(issue -> matchesRow(issue, request.rowKey()))
            .filter(matchesMetric(request.columnKey()))
            .sorted(buildDetailComparator(request.sortField(), request.sortOrder()))
            .toList();
    PageSlice<IssueSource> pageSlice =
        PageSliceSupport.slice(scoped, request.page(), request.size() <= 0 ? 10 : request.size());
    return new StatisticDetailResponse(
        "客户问题响应效率明细",
        "展示当前模块与响应/解决周期指标命中的 CC_Product 议题明细。",
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
    StatisticFilterGroup filterGroup = parseFilterGroup(filters, buildDefinition());
    StatisticFilterGroup effectiveFilterGroup = applyDefaultMilestone(filterGroup);
    RuleFlowSnapshot snapshot = buildRuleFlowSnapshot(loadSources(filters), effectiveFilterGroup);
    return new StatisticBoardRuleExplanationResponse(
        BOARD_KEY,
        true,
        "客户问题缺陷响应效率规则说明",
        RULE_VERSION,
        "统计范围为 CC_Product 自 2026-01-01 以来创建且携带里程碑的客户问题议题，open 和 closed 都统计。",
        "同一条议题如果关联多个模块，会分别计入模块行；响应周期只统计已回复调研模板的议题，解决周期只统计已标注“已修复/完成”的议题。",
        snapshot.flowSteps(),
        List.of(
            new StatisticRuleMetricDefinition(
                "response_cycle_hours",
                "响应周期（小时）",
                "只统计 research_template_time 非空的议题。",
                "响应周期 = avg(第一条调研模板回复时间 - 议题创建时间)，单位小时，四舍五入取整",
                null),
            new StatisticRuleMetricDefinition(
                "resolution_cycle_days",
                "解决周期（天）",
                "只统计 bug_status 包含“已修复/完成”且 fixed_label_time 非空的议题。",
                "解决周期 = avg(已修复标签时间 - 议题创建时间)，单位天，保留 1 位小数",
                null)),
        null);
  }

  private RuleFlowSnapshot buildRuleFlowSnapshot(List<IssueSource> loaded, StatisticFilterGroup filterGroup) {
    List<IssueSource> initial = loaded == null ? List.of() : List.copyOf(loaded);
    List<IssueSource> scoped =
        initial.stream().filter(issue -> customerIssueScopeProfile.matches(issue.scopeContext())).toList();
    List<IssueSource> visible = scoped.stream().filter(issue -> !issue.excluded()).toList();
    List<IssueSource> rowSources =
        visible.stream().filter(issue -> matchesFilterGroup(issue, filterGroup)).toList();
    return new RuleFlowSnapshot(
        rowSources,
        rowSources,
        List.of(
            StatisticRuleFlowSupport.step(
                "source-load",
                "加载议题事实",
                "从 issue_fact 读取已归一化的客户问题事实和响应效率时间字段。",
                initial.size(),
                initial,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "scope-filter",
                "限定客户问题范围",
                "复用 CustomerIssueScopeProfile 收口 CC_Product、自 2026-01-01 以来创建且携带里程碑的客户问题。",
                initial.size(),
                scoped,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "exclude-filter",
                "剔除排除数据",
                "按客户问题公共排除规则剔除 issue_fact.is_excluded = true 的议题。",
                scoped.size(),
                visible,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "condition-filter",
                "应用页面筛选",
                "应用当前页面条件筛选和顶部查询参数。",
                visible.size(),
                rowSources,
                this::toRuleFlowSample),
            StatisticRuleFlowSupport.step(
                "module-expand",
                "按模块展开",
                "同一条议题可归属多个模块；未设定模块的议题归入“未设定模块”。",
                rowSources.size(),
                rowSources.stream().mapToLong(issue -> issue.displayModuleNames().size()).sum(),
                rowSources,
                this::toRuleFlowSample)));
  }

  private StatisticRuleFlowStepSample toRuleFlowSample(IssueSource issue) {
    return new StatisticRuleFlowStepSample(
        "#" + issue.iid() + " " + issue.projectName(),
        issue.title()
            + " | 产品版本: "
            + issue.milestoneTitle()
            + " | 响应周期: "
            + issue.responseCycleDisplay()
            + " | 解决周期: "
            + issue.resolutionCycleDisplay());
  }

  private List<IssueSource> loadSources(Map<String, String> filters) {
    Map<String, String> queryFilters = new LinkedHashMap<>(withoutReservedFilters(filters));
    queryFilters.putIfAbsent("projectId", String.valueOf(LEGACY_CC_PRODUCT_PROJECT_ID));
    try {
      return issueFactQueryService.query(FACT_SQL, queryFilters, this::mapIssueFact);
    } catch (DataAccessException error) {
      log.warn("Failed to load customer issue response efficiency facts", error);
      return List.of();
    }
  }

  private IssueSource mapIssueFact(ResultSet rs, int rowNum) throws SQLException {
    return new IssueSource(
        StatisticSourceValueSupport.text(rs.getString("source_instance"), "default"),
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
        rs.getBoolean("is_excluded"),
        StatisticSourceValueSupport.time(rs.getTimestamp("research_template_time")),
        StatisticSourceValueSupport.time(rs.getTimestamp("fixed_label_time")),
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
      case "response_cycle_hours" -> IssueSource::hasResponseCycle;
      case "resolution_cycle_days" -> IssueSource::hasResolutionCycle;
      default -> issue -> true;
    };
  }

  private Comparator<IssueSource> buildDetailComparator(String sortField, String sortOrder) {
    Comparator<IssueSource> comparator =
        switch (StringUtils.hasText(sortField) ? sortField.trim() : "updatedAt") {
          case "iid" -> SortSupport.nullableComparable(IssueSource::iid);
          case "moduleNames" -> SortSupport.nullableString(issue -> String.join("、", issue.displayModuleNames()));
          case "title" -> SortSupport.nullableString(IssueSource::title);
          case "state" -> SortSupport.nullableString(IssueSource::issueState);
          case "severityLevel" -> SortSupport.nullableString(IssueSource::displaySeverityLevel);
          case "bugStatus" -> SortSupport.nullableString(IssueSource::bugStatus);
          case "milestoneTitle" -> SortSupport.nullableString(IssueSource::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(IssueSource::createdAt);
          case "researchTemplateTime" -> SortSupport.nullableComparable(IssueSource::researchTemplateTime);
          case "fixedLabelTime" -> SortSupport.nullableComparable(IssueSource::fixedLabelTime);
          case "authorName" -> SortSupport.nullableString(IssueSource::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueSource::assigneeName);
          default -> SortSupport.nullableComparable(IssueSource::updatedAt);
        };
    comparator = comparator.thenComparing(IssueSource::iid);
    return SortSupport.applyDirection(comparator, "ascending".equalsIgnoreCase(sortOrder));
  }

  private Map<String, Object> toDetailRecord(IssueSource issue) {
    Map<String, Object> record = new LinkedHashMap<>();
    issueLinkSupport.putIssueMetadata(
        record, issue.sourceInstance(), issue.iid(), issue.projectId(), issue.projectName(), issue.id(), issue.labels());
    record.put("moduleNames", String.join("、", issue.displayModuleNames()));
    record.put("title", issue.title());
    record.put("state", issue.isClosed() ? "已关闭" : "未关闭");
    record.put("severityLevel", issue.displaySeverityLevel());
    record.put("bugStatus", issue.bugStatus());
    record.put("milestoneTitle", issue.milestoneTitle());
    record.put("createdAt", format(issue.createdAt()));
    record.put("researchTemplateTime", format(issue.researchTemplateTime()));
    record.put("fixedLabelTime", format(issue.fixedLabelTime()));
    record.put("authorName", issue.authorName());
    record.put("assigneeName", issue.assigneeName());
    record.put("updatedAt", format(issue.updatedAt()));
    return record;
  }

  private boolean matchesFilterGroup(IssueSource issue, StatisticFilterGroup filterGroup) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(issue, condition);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private boolean matchesCondition(IssueSource issue, StatisticFilterCondition condition) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey())) {
      return true;
    }
    if (CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD.equals(condition.fieldKey())) {
      return CustomerIssueMilestoneFilterSupport.matches(
          issue.milestoneTitle(),
          issue.testingPhase(),
          CustomerIssueMilestoneFilterSupport.normalizeLegacyTestingPhase(
              new StatisticFilterGroup("AND", List.of(condition)), phaseScopeResolver));
    }
    if ("moduleName".equals(condition.fieldKey())) {
      return matchesCandidates(issue.displayModuleNames(), condition);
    }
    String candidate =
        switch (condition.fieldKey()) {
          case "projectName" -> issue.projectName();
          case "milestoneTitle" -> issue.milestoneTitle();
          case "severityLevel" -> issue.severityLevel();
          case "priorityLevel" -> issue.priorityLevel();
          case "issueState" -> issue.issueState();
          case "bugStatus" -> issue.bugStatus();
          case "authorName" -> issue.authorName();
          case "assigneeName" -> issue.assigneeName();
          default -> "";
        };
    return matchesCandidate(candidate, condition);
  }

  private boolean matchesCandidates(List<String> candidates, StatisticFilterCondition condition) {
    List<String> safeCandidates = candidates == null ? List.of() : candidates;
    String value = trim(condition.value());
    return switch (condition.operator()) {
      case "eq" -> value == null || safeCandidates.stream().anyMatch(candidate -> normalizedEquals(candidate, value));
      case "ne" -> value == null || safeCandidates.stream().noneMatch(candidate -> normalizedEquals(candidate, value));
      case "contains" ->
          value == null || safeCandidates.stream().anyMatch(candidate -> normalize(candidate).contains(normalize(value)));
      case "isEmpty" -> safeCandidates.stream().noneMatch(StringUtils::hasText);
      case "isNotEmpty" -> safeCandidates.stream().anyMatch(StringUtils::hasText);
      default -> true;
    };
  }

  private boolean matchesCandidate(String candidate, StatisticFilterCondition condition) {
    String value = trim(condition.value());
    return switch (condition.operator()) {
      case "eq" -> value == null || normalizedEquals(candidate, value);
      case "ne" -> value == null || !normalizedEquals(candidate, value);
      case "contains" -> value == null || normalize(candidate).contains(normalize(value));
      case "isEmpty" -> !StringUtils.hasText(candidate);
      case "isNotEmpty" -> StringUtils.hasText(candidate);
      default -> true;
    };
  }

  private StatisticFilterGroup applyDefaultMilestone(StatisticFilterGroup filterGroup) {
    return CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(
        filterGroup, milestoneCatalogService.listMilestones(), phaseScopeResolver);
  }

  private StatisticColumnLeaf leaf(String key, String label, boolean drilldown, String metricType) {
    return new StatisticColumnLeaf(key, label, drilldown, metricType);
  }

  private static String format(LocalDateTime time) {
    return time == null ? "" : DATE_TIME_FORMATTER.format(time);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean normalizedEquals(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
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
      List<IssueSource> responseIssues = issues.stream().filter(IssueSource::hasResponseCycle).toList();
      List<IssueSource> resolutionIssues = issues.stream().filter(IssueSource::hasResolutionCycle).toList();
      long responseAverage = averageHours(responseIssues);
      BigDecimal resolutionAverage = averageDays(resolutionIssues);
      String milestoneTitle = commonMilestoneTitle();
      return new StatisticRowData(
          rowKey,
          rowLabel,
          List.of(
              textCell("milestone_title", milestoneTitle),
              cycleCell("response_cycle_hours", responseIssues.isEmpty() ? null : responseAverage, responseIssues.isEmpty() ? "" : String.valueOf(responseAverage)),
              cycleCell(
                  "resolution_cycle_days",
                  resolutionIssues.isEmpty() ? null : resolutionAverage.multiply(BigDecimal.TEN).longValue(),
                  resolutionIssues.isEmpty() ? "" : resolutionAverage.toPlainString())));
    }

    private String commonMilestoneTitle() {
      List<String> values =
          issues.stream()
              .map(IssueSource::milestoneTitle)
              .filter(StringUtils::hasText)
              .distinct()
              .limit(2)
              .toList();
      if (values.isEmpty()) {
        return "";
      }
      return values.size() == 1 ? values.get(0) : "多个版本";
    }

    private long averageHours(List<IssueSource> records) {
      if (records.isEmpty()) {
        return 0;
      }
      double average =
          records.stream().mapToLong(IssueSource::responseCycleHours).average().orElse(0D);
      return Math.round(average);
    }

    private BigDecimal averageDays(List<IssueSource> records) {
      if (records.isEmpty()) {
        return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
      }
      BigDecimal sum =
          records.stream()
              .map(IssueSource::resolutionCycleDays)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      return sum.divide(BigDecimal.valueOf(records.size()), 1, RoundingMode.HALF_UP);
    }

    private StatisticCellData textCell(String key, String value) {
      return new StatisticCellData(key, 0, value, false, null, Map.of("rowKey", rowKey));
    }

    private StatisticCellData cycleCell(String key, Long numericValue, String displayValue) {
      return new StatisticCellData(
          key,
          numericValue == null ? 0L : numericValue,
          displayValue,
          StringUtils.hasText(displayValue),
          "issue-list",
          Map.of("rowKey", rowKey));
    }
  }

  private record IssueSource(
      String sourceInstance,
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
      boolean excluded,
      LocalDateTime researchTemplateTime,
      LocalDateTime fixedLabelTime,
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

    boolean hasResponseCycle() {
      return createdAt != null && researchTemplateTime != null;
    }

    boolean hasResolutionCycle() {
      return createdAt != null
          && fixedLabelTime != null
          && StringUtils.hasText(bugStatus)
          && bugStatus.contains(FIXED_STATUS);
    }

    long responseCycleHours() {
      return hasResponseCycle() ? Duration.between(createdAt, researchTemplateTime).toHours() : 0L;
    }

    BigDecimal resolutionCycleDays() {
      if (!hasResolutionCycle()) {
        return BigDecimal.ZERO;
      }
      long millis = Duration.between(createdAt, fixedLabelTime).toMillis();
      return BigDecimal.valueOf(millis)
          .divide(BigDecimal.valueOf(24L * 60L * 60L * 1000L), 6, RoundingMode.HALF_UP);
    }

    String responseCycleDisplay() {
      return hasResponseCycle() ? String.valueOf(responseCycleHours()) + "小时" : "未响应";
    }

    String resolutionCycleDisplay() {
      return hasResolutionCycle() ? resolutionCycleDays().setScale(1, RoundingMode.HALF_UP).toPlainString() + "天" : "未解决";
    }

    String displaySeverityLevel() {
      return IssueDisplayValueSupport.displaySeverityLevelOrBlank(severityLevel);
    }
  }

  private record RuleFlowSnapshot(
      List<IssueSource> rowSources,
      List<IssueSource> finalSources,
      List<StatisticRuleFlowStep> flowSteps) {}
}
