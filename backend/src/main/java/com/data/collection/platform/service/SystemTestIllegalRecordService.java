package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIllegalRecordService extends AbstractIssueFactRecordListService {
  private static final String WORKSPACE_KEY = "system-test-illegal-records";
  private static final String RULE_VERSION = "system-test-illegal-records@2026-04-27-v1";
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final SystemTestScopeProfile systemTestScopeProfile;
  private final ObjectMapper objectMapper;

  public SystemTestIllegalRecordService(
      IssueFactRecordRepository issueFactRecordRepository,
      SystemTestScopeProfile systemTestScopeProfile,
      ObjectMapper objectMapper,
      GitlabMirrorProperties gitlabMirrorProperties) {
    super(issueFactRecordRepository, gitlabMirrorProperties.getWebBaseUrl());
    this.systemTestScopeProfile = systemTestScopeProfile;
    this.objectMapper = objectMapper;
  }

  public SystemTestIllegalRecordListResponse listRecords(SystemTestIllegalRecordQueryRequest request) {
    IssueFactRecordListRequest listRequest = request.listRequest();
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());
    StatisticFilterGroup filterGroup =
        IssueFactRecordFilterGroupSupport.parse(
            objectMapper,
            request.filterGroupJson(),
            IssueFactRecordFilterGroupSupport.SYSTEM_TEST_FILTER_OPERATORS);

    if (canUseSqlPage(listRequest, request.filterGroupJson(), safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.SYSTEM_TEST,
                  listRequest,
                  filterGroup,
                  null,
                  request.illegalReason(),
                  request.testingPhase(),
                  request.authorName(),
                  request.assigneeName(),
                  false,
                  true,
                  true,
                  true,
                  true,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder));
      List<SystemTestIllegalRecordRowResponse> records =
          pageSlice.records().stream().map(this::toResponse).toList();
      return new SystemTestIllegalRecordListResponse(
          records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadScopedIllegalViews(listRequest.projectId()),
                withoutModuleFilter(listRequest),
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesDisplayModule(view, listRequest.moduleName()))
            .filter(view -> matchesTestingPhase(view, request.testingPhase()))
            .filter(view -> matchesIllegalReason(view, request.illegalReason()))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .filter(view -> IssueFactRecordFilterGroupSupport.matches(view, filterGroup))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<SystemTestIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new SystemTestIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public String exportRecordsCsv(SystemTestIllegalRecordQueryRequest request) {
    List<SystemTestIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      IssueFactRecordListRequest listRequest = request.listRequest();
      SystemTestIllegalRecordQueryRequest pageRequest =
          new SystemTestIllegalRecordQueryRequest(
              new IssueFactRecordListRequest(
                  listRequest.projectId(),
                  listRequest.keyword(),
                  listRequest.issueIid(),
                  listRequest.title(),
                  listRequest.projectName(),
                  listRequest.moduleName(),
                  listRequest.severityLevel(),
                  listRequest.priorityLevel(),
                  listRequest.issueState(),
                  listRequest.bugStatus(),
                  listRequest.category(),
                  listRequest.milestoneTitle(),
                  listRequest.createdAtStart(),
                  listRequest.createdAtEnd(),
                  listRequest.updatedAtStart(),
                  listRequest.updatedAtEnd(),
                  listRequest.sourceInstance(),
                  page,
                  EXPORT_PAGE_SIZE,
                  listRequest.sortField(),
                  listRequest.sortOrder()),
              request.testingPhase(),
              request.illegalReason(),
              request.authorName(),
              request.assigneeName(),
              request.filterGroupJson());
      SystemTestIllegalRecordListResponse response = listRecords(pageRequest);
      CsvExportSupport.ensureWithinRowLimit(response.total());
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }

    List<String> lines = new ArrayList<>();
    lines.add(
        String.join(
            ",",
            List.of(
                "问题编号",
                "非法类型",
                "项目",
                "模块",
                "测试阶段",
                "严重程度",
                "缺陷状态",
                "状态",
                "创建人",
                "处理人",
                "缺陷分类",
                "里程碑",
                "创建时间",
                "更新时间",
                "关闭时间",
                "标题",
                "链接")));
    for (SystemTestIllegalRecordRowResponse row : rows) {
      lines.add(
          String.join(
              ",",
              List.of(
                  CsvExportSupport.cell(row.issueIid()),
                  CsvExportSupport.cell(row.illegalReason()),
                  CsvExportSupport.cell(row.projectName()),
                  CsvExportSupport.cell(row.moduleNames()),
                  CsvExportSupport.cell(row.testingPhase()),
                  CsvExportSupport.cell(row.severityLevel()),
                  CsvExportSupport.cell(row.bugStatus()),
                  CsvExportSupport.cell(row.issueState()),
                  CsvExportSupport.cell(row.authorName()),
                  CsvExportSupport.cell(row.assigneeName()),
                  CsvExportSupport.cell(row.category()),
                  CsvExportSupport.cell(row.milestoneTitle()),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.createdAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.updatedAt())),
                  CsvExportSupport.cell(CsvExportSupport.dateTime(row.closedAt())),
                  CsvExportSupport.cell(row.title()),
                  CsvExportSupport.cell(row.issueLink()))));
    }
    return String.join("\n", lines) + "\n";
  }

  public SystemTestIllegalRecordFilterOptionsResponse getFilterOptions(Long projectId) {
    List<IssueFactRecord> rows = loadScopedIllegalViews(projectId);
    return new SystemTestIllegalRecordFilterOptionsResponse(
        toOptions(rows, IssueFactRecord::projectName),
        toOptions(rows.stream().flatMap(view -> displayModuleNames(view).stream()).toList()),
        toOptions(
            rows.stream()
                .map(IssueFactRecord::phaseFilterValue)
                .filter(StringUtils::hasText)
                .toList()),
        toOptions(
            rows.stream()
                .map(view -> SystemTestIllegalReasonSupport.normalize(view.illegalReason()))
                .filter(StringUtils::hasText)
                .toList()),
        toOptions(rows, IssueFactRecord::authorName),
        toOptions(rows, IssueFactRecord::assigneeName),
        toOptions(rows, IssueFactRecord::issueState),
        toOptions(rows, IssueFactRecord::severityLevel),
        toOptions(rows, IssueFactRecord::bugStatus),
        toOptions(rows, IssueFactRecord::category),
        toOptions(rows, IssueFactRecord::milestoneTitle));
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation(Long projectId) {
    List<IssueFactRecord> loaded = loadFacts(projectId);
    List<IssueFactRecord> scoped = scopeSystemTests(loaded);
    List<IssueFactRecord> valid = scoped.stream().filter(view -> !view.excluded()).toList();
    List<IssueFactRecord> illegal = valid.stream().filter(IssueFactRecord::illegal).toList();
    List<IssueFactRecord> supported =
        illegal.stream()
            .filter(view -> SystemTestIllegalReasonSupport.normalize(view.illegalReason()) != null)
            .toList();
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "系统测试非法数据规则说明",
        RULE_VERSION,
        "当前页面基于 issue_fact 事实层，先用 SystemTestScopeProfile 限定系统测试/回归测试范围，再剔除排除标签数据。",
        "非法原因来自 issue_fact.illegal_reason，页面层只做交接文档口径的原因归一化，不重新解析标签或评论。",
        List.of(
            step("source-load", "加载议题事实", "从 issue_fact 读取已归一化的议题事实。", loaded, loaded.size()),
            step("scope-filter", "限定系统测试范围", "复用系统测试 scope profile，保留系统测试和回归测试议题。", scoped, loaded.size()),
            step("exclude-filter", "剔除排除数据", "排除功能屏蔽、已拒绝、建议、申请否决关闭、需求如此关闭等数据。", valid, scoped.size()),
            step("illegal-filter", "筛出非法数据", "保留 issue_fact.is_illegal = true 的系统测试议题。", illegal, valid.size()),
            step("reason-normalize", "归一化非法类型", "仅保留交接文档定义的四类非法类型，并映射历史事实层别名。", supported, illegal.size())),
        List.of(
            new StatisticRuleMetricDefinition(
                "missing-severity",
                SystemTestIllegalReasonSupport.MISSING_SEVERITY,
                "严重程度未命中一级缺陷、二级缺陷或三级缺陷。",
                "未设定严重程度 = count(system-test issue_fact where illegal_reason in [缺失严重程度, 未设定严重程度])",
                null),
            new StatisticRuleMetricDefinition(
                "missing-module",
                SystemTestIllegalReasonSupport.MISSING_MODULE,
                "议题没有模块标签。",
                "未设定模块 = count(system-test issue_fact where illegal_reason in [缺失模块, 未设定模块])",
                null),
            new StatisticRuleMetricDefinition(
                "template-not-followed",
                SystemTestIllegalReasonSupport.TEMPLATE_NOT_FOLLOWED,
                "议题带已修复/完成标签，但未按缺陷调研模板回复。",
                "未按照模板回复 = count(system-test issue_fact where illegal_reason = 未按照模板回复)",
                null),
            new StatisticRuleMetricDefinition(
                "non-unique-reason",
                SystemTestIllegalReasonSupport.NON_UNIQUE_REASON,
                "议题带已修复/完成标签，但缺陷原因数量不是 1 个。",
                "缺陷原因不唯一 = count(system-test issue_fact where illegal_reason = 缺陷原因不唯一)",
                null)),
        null);
  }

  private List<IssueFactRecord> loadScopedIllegalViews(Long projectId) {
    return scopeSystemTests(loadFacts(projectId)).stream()
        .filter(view -> !view.excluded())
        .filter(IssueFactRecord::illegal)
        .filter(view -> SystemTestIllegalReasonSupport.normalize(view.illegalReason()) != null)
        .toList();
  }

  private List<IssueFactRecord> scopeSystemTests(List<IssueFactRecord> rows) {
    return rows.stream()
        .filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private IssueFactRecordListRequest withoutModuleFilter(IssueFactRecordListRequest request) {
    return new IssueFactRecordListRequest(
        request.projectId(),
        request.keyword(),
        request.issueIid(),
        request.title(),
        request.projectName(),
        null,
        request.severityLevel(),
        request.priorityLevel(),
        request.issueState(),
        request.bugStatus(),
        request.category(),
        request.milestoneTitle(),
        request.createdAtStart(),
        request.createdAtEnd(),
        request.updatedAtStart(),
        request.updatedAtEnd(),
        request.sourceInstance(),
        request.page(),
        request.size(),
        request.sortField(),
        request.sortOrder());
  }

  private SystemTestIllegalRecordRowResponse toResponse(IssueFactRecord view) {
    return new SystemTestIllegalRecordRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.issueIid()),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.primaryPhaseLabel(),
        SystemTestIllegalReasonSupport.normalize(view.illegalReason()),
        view.severityLevel(),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.authorName(),
        view.assigneeName(),
        String.join("、", displayModuleNames(view)),
        view.createdAt(),
        view.updatedAt(),
        view.closedAt(),
        view.labels());
  }

  private boolean matchesKeyword(IssueFactRecord view, String keyword) {
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword == null) {
      return true;
    }
    return TextQuerySupport.containsAbstractSearch(String.valueOf(view.issueIid()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.title(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.projectName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(String.join(" ", displayModuleNames(view)), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.primaryPhaseLabel(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(SystemTestIllegalReasonSupport.normalize(view.illegalReason()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.bugStatus(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.category(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private boolean matchesDisplayModule(IssueFactRecord view, String moduleName) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    return normalized == null
        || displayModuleNames(view).stream()
            .anyMatch(item -> TextQuerySupport.equalsNormalized(item, normalized));
  }

  private boolean matchesTestingPhase(IssueFactRecord view, String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    return normalized == null || TextQuerySupport.equalsNormalized(view.phaseFilterValue(), normalized);
  }

  private boolean matchesIllegalReason(IssueFactRecord view, String illegalReason) {
    return SystemTestIllegalReasonSupport.matches(view.illegalReason(), illegalReason);
  }

  private List<String> displayModuleNames(IssueFactRecord view) {
    return view.moduleNames().isEmpty() ? List.of(SystemTestIllegalReasonSupport.MISSING_MODULE) : view.moduleNames();
  }

  @Override
  protected List<StatisticRuleFlowStepSample> sample(List<IssueFactRecord> rows) {
    return rows.stream()
        .limit(3)
        .map(
            row ->
                new StatisticRuleFlowStepSample(
                    "#" + row.issueIid() + " " + row.projectName(),
                    row.title()
                        + (StringUtils.hasText(SystemTestIllegalReasonSupport.normalize(row.illegalReason()))
                            ? " | 非法类型: " + SystemTestIllegalReasonSupport.normalize(row.illegalReason())
                            : "")))
        .toList();
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("、", view.moduleNames())));
    comparators.put("testingPhase", SortSupport.nullableString(IssueFactRecord::primaryPhaseLabel));
    comparators.put(
        "illegalReason",
        SortSupport.nullableString(view -> SystemTestIllegalReasonSupport.normalize(view.illegalReason())));
    comparators.put("severityLevel", SortSupport.nullableString(IssueFactRecord::severityLevel));
    comparators.put("bugStatus", SortSupport.nullableString(IssueFactRecord::bugStatus));
    comparators.put("issueState", SortSupport.nullableString(IssueFactRecord::issueState));
    comparators.put("authorName", SortSupport.nullableString(IssueFactRecord::authorName));
    comparators.put("assigneeName", SortSupport.nullableString(IssueFactRecord::assigneeName));
    comparators.put("category", SortSupport.nullableString(IssueFactRecord::category));
    comparators.put("milestoneTitle", SortSupport.nullableString(IssueFactRecord::milestoneTitle));
    comparators.put("createdAt", SortSupport.nullableComparable(IssueFactRecord::createdAt));
    comparators.put("updatedAt", SortSupport.nullableComparable(IssueFactRecord::updatedAt));
    comparators.put("closedAt", SortSupport.nullableComparable(IssueFactRecord::closedAt));
    return Map.copyOf(comparators);
  }
}
