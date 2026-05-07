package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
// 代码走查非法记录服务承接规则配置、实时刷新、记录查询、导出和规则说明。
// 默认规则尽量下沉到 SQL 查询；用户自定义规则则通过规则配置和源数据加载器组合执行。
public class CodeReviewIllegalRecordService {
  public static final String WORKSPACE_KEY = "code-review-illegal-records";
  private static final String RULE_VERSION = "code-review-illegal-records@2026-04-10-v5";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final DateTimeFormatter CSV_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final List<String> REALTIME_REFRESH_TABLES =
      List.of(
          "merge_requests",
          "merge_request_metrics",
          "merge_request_reviewers",
          "merge_request_assignees",
          "label_links",
          "labels",
          "projects",
          "namespaces",
          "users");

  private static final List<OptionItemResponse> REQUEST_TYPE_OPTIONS =
      List.of(new OptionItemResponse("合并请求", "merge_request"));

  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final FactBuildService factBuildService;
  private final CodeReviewIllegalRecordSourceLoader sourceLoader;
  private final String defaultGitlabBaseUrl;
  private final ObjectMapper objectMapper;

  public CodeReviewIllegalRecordService(
      GitlabMirrorSyncService gitlabMirrorSyncService,
      RealtimeWorkspaceService realtimeWorkspaceService,
      FactBuildService factBuildService,
      CodeReviewIllegalRecordSourceLoader sourceLoader,
      ObjectMapper objectMapper,
      GitlabMirrorProperties gitlabMirrorProperties) {
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.factBuildService = factBuildService;
    this.sourceLoader = sourceLoader;
    this.objectMapper = objectMapper;
    this.defaultGitlabBaseUrl = gitlabMirrorProperties.getWebBaseUrl();
  }

  public CodeReviewIllegalRecordListResponse listRecords(CodeReviewIllegalRecordQueryRequest request) {
    int safePage = request.page() <= 0 ? 1 : request.page();
    int safeSize = request.size() <= 0 ? 20 : Math.min(request.size(), 100);
    String safeSortField = CodeReviewIllegalRecordQuerySupport.normalizeSortField(request.sortField());
    String safeSortOrder = CodeReviewIllegalRecordQuerySupport.normalizeSortOrder(request.sortOrder());
    CodeReviewRuleConfig ruleConfig = parseRuleConfig(request.ruleConfigJson());
    StatisticFilterGroup filterGroup =
        CodeReviewIllegalRecordFilterGroupSupport.parse(objectMapper, request.filterGroupJson());
    if (canUseDefaultSqlPage(request)) {
      PageSlice<CodeReviewIllegalRecordSource> sourcePage =
          sourceLoader.loadDefaultIllegalPage(
              new CodeReviewIllegalRecordSourcePageQuery(
                  request, filterGroup, safePage, safeSize, safeSortField, safeSortOrder));
      CodeReviewRuleConfig responseRuleConfig = null;
      List<CodeReviewIllegalRecordRowResponse> records =
          sourcePage.records().stream()
              .map(this::toView)
              .map(row -> toResponse(row, responseRuleConfig))
              .toList();
      return new CodeReviewIllegalRecordListResponse(
          records,
          sourcePage.total(),
          sourcePage.page(),
          sourcePage.size(),
          safeSortField,
          safeSortOrder);
    }
    List<CodeReviewIllegalRecordView> scopedRows =
        loadScopedViews(
            request.projectId(),
            request.repositoryName(),
            request.mergedAtStart(),
            request.mergedAtEnd(),
            request.keyword(),
            request.projectName(),
            request.requestType(),
            request.targetBranch(),
            request.mergedBy(),
            request.moduleName(),
            request.mergeRequestIid(),
            request.owner());
    List<CodeReviewIllegalRecordView> judgedRows =
        CodeReviewRuleConfigSupport.hasReadyConfig(ruleConfig)
            ? CodeReviewRuleConfigSupport.apply(scopedRows, ruleConfig)
            : scopedRows.stream().filter(row -> !row.illegalTypes().isEmpty()).toList();
    List<CodeReviewIllegalRecordView> filtered =
        judgedRows.stream()
            .filter(row -> CodeReviewIllegalRecordFilterGroupSupport.matches(row, filterGroup))
            .filter(
                row ->
                    CodeReviewIllegalRecordQuerySupport.matchesIllegalType(
                        row.illegalTypes(), request.illegalType()))
            .sorted(CodeReviewIllegalRecordQuerySupport.buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<CodeReviewIllegalRecordView> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);
    CodeReviewRuleConfig responseRuleConfig =
        CodeReviewRuleConfigSupport.hasReadyConfig(ruleConfig) ? ruleConfig : null;
    List<CodeReviewIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(row -> toResponse(row, responseRuleConfig)).toList();

    return new CodeReviewIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public String exportRecordsCsv(CodeReviewIllegalRecordQueryRequest request) {
    List<CodeReviewIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      CodeReviewIllegalRecordQueryRequest pageRequest =
          new CodeReviewIllegalRecordQueryRequest(
              request.projectId(),
              request.repositoryName(),
              request.mergedAtStart(),
              request.mergedAtEnd(),
              request.keyword(),
              request.projectName(),
              request.requestType(),
              request.targetBranch(),
              request.mergedBy(),
              request.moduleName(),
              request.illegalType(),
              request.mergeRequestIid(),
              request.owner(),
              request.filterGroupJson(),
              page,
              EXPORT_PAGE_SIZE,
              request.sortField(),
              request.sortOrder(),
              request.ruleConfigJson());
      CodeReviewIllegalRecordListResponse response = listRecords(pageRequest);
      CsvExportSupport.ensureWithinRowLimit(response.total());
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }

    List<String> lines = new ArrayList<>();
    lines.add(String.join(",", List.of(
        "请求类型",
        "MR IID",
        "项目",
        "仓库",
        "模块",
        "责任人",
        "目标分支",
        "合并人",
        "合并时间",
        "非法类型",
        "评论率",
        "缺陷数",
        "新增行数",
        "标题",
        "链接")));
    for (CodeReviewIllegalRecordRowResponse row : rows) {
      lines.add(String.join(",", List.of(
          csv(row.requestType()),
          csv(row.mergeRequestIid()),
          csv(row.projectName()),
          csv(row.repositoryName()),
          csv(row.moduleName()),
          csv(row.owner()),
          csv(row.targetBranch()),
          csv(row.mergedBy()),
          csv(row.mergedAt() == null ? "" : CSV_DATE_TIME.format(row.mergedAt())),
          csv(row.illegalTypes() == null ? "" : String.join("；", row.illegalTypes())),
          csv(row.commentRate()),
          csv(row.defectCount()),
          csv(row.addedLines()),
          csv(row.mergeRequestContent()),
          csv(row.mergeRequestLink()))));
    }
    return String.join("\n", lines) + "\n";
  }

  private String csv(Object value) {
    if (value == null) {
      return "";
    }
    String text = String.valueOf(value);
    if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private boolean canUseDefaultSqlPage(CodeReviewIllegalRecordQueryRequest request) {
    return TextQuerySupport.trimToNull(request.ruleConfigJson()) == null;
  }

  public CodeReviewIllegalRecordFilterOptionsResponse getFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    List<CodeReviewIllegalRecordView> rows =
        sourceLoader
            .loadSources(
                CodeReviewIllegalRecordQuerySupport.buildFactFilters(
                    request.projectId(), null, null, null, null, null, null, null, null))
            .stream()
            .map(this::toView)
            .filter(row -> !row.illegalTypes().isEmpty())
            .toList();

    return new CodeReviewIllegalRecordFilterOptionsResponse(
        REQUEST_TYPE_OPTIONS,
        toOptions(rows, CodeReviewIllegalRecordView::repositoryName),
        toOptions(rows.stream().flatMap(row -> row.illegalTypes().stream()).toList()),
        toOptions(rows, CodeReviewIllegalRecordView::targetBranch),
        toOptions(rows, CodeReviewIllegalRecordView::mergedBy),
        toOptions(rows, CodeReviewIllegalRecordView::moduleName),
        toOptions(rows, CodeReviewIllegalRecordView::projectName));
  }

  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return realtimeWorkspaceService.getStatus(WORKSPACE_KEY);
  }

  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    return realtimeWorkspaceService.requestRefresh(WORKSPACE_KEY, this::refreshMirrorForRealtimeView);
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation() {
    List<CodeReviewIllegalRecordSource> sources = sourceLoader.loadSources(Map.of());
    List<CodeReviewIllegalRecordView> views = sources.stream().map(this::toView).toList();
    List<CodeReviewIllegalRecordView> illegalViews =
        views.stream().filter(row -> !row.illegalTypes().isEmpty()).toList();
    long total = views.size();
    long illegalTotal = illegalViews.size();

    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "代码走查非法记录规则说明",
        RULE_VERSION,
        "当前统计范围是已归一化到事实表中的 Merge Request 相关数据；页面查询条件会在这个范围上继续筛选。",
        "这里先说明总共有多少条非法记录，再说明它们分别是因为什么被判定为非法。",
        buildRuleFlowSteps(views, illegalViews, total, illegalTotal),
        buildMetricDefinitions(),
        null);
  }

  public CodeReviewRulePreviewResponse previewRuleConfig(CodeReviewRulePreviewRequest request) {
    CodeReviewRuleConfig ruleConfig = request == null ? null : request.ruleConfig();
    List<CodeReviewIllegalRecordView> scopedRows =
        loadScopedViews(
            request == null ? null : request.projectId(),
            request == null ? null : request.repositoryName(),
            request == null ? null : request.mergedAtStart(),
            request == null ? null : request.mergedAtEnd(),
            request == null ? null : request.keyword(),
            request == null ? null : request.projectName(),
            request == null ? null : request.requestType(),
            request == null ? null : request.targetBranch(),
            request == null ? null : request.mergedBy(),
            request == null ? null : request.moduleName(),
            request == null ? null : request.mergeRequestIid(),
            request == null ? null : request.owner());
    List<CodeReviewIllegalRecordView> defaultRows =
        scopedRows.stream()
            .filter(row -> !row.illegalTypes().isEmpty())
            .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesIllegalType(row.illegalTypes(), request == null ? null : request.illegalType()))
            .toList();
    List<CodeReviewIllegalRecordView> filteredRows =
        CodeReviewRuleConfigSupport.hasReadyConfig(ruleConfig)
            ? CodeReviewRuleConfigSupport.apply(scopedRows, ruleConfig).stream()
                .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesIllegalType(row.illegalTypes(), request == null ? null : request.illegalType()))
                .toList()
            : defaultRows;
    long baseTotal = scopedRows.size();
    long filteredTotal = filteredRows.size();
    long deltaCount = filteredTotal - defaultRows.size();
    double retainedRate = baseTotal == 0 ? 0.0 : filteredTotal * 100.0 / baseTotal;
    List<CodeReviewRulePreviewSample> samples =
        filteredRows.stream()
            .limit(8)
            .map(row -> toRulePreviewSample(row, ruleConfig))
            .toList();
    return new CodeReviewRulePreviewResponse(baseTotal, filteredTotal, deltaCount, retainedRate, samples);
  }

  private List<CodeReviewIllegalRecordView> loadScopedViews(
      Long projectId,
      String repositoryName,
      String mergedAtStart,
      String mergedAtEnd,
      String keyword,
      String projectName,
      String requestType,
      String targetBranch,
      String mergedBy,
      String moduleName,
      String mergeRequestIid,
      String owner) {
    Map<String, String> factFilters =
        CodeReviewIllegalRecordQuerySupport.buildFactFilters(
            projectId,
            repositoryName,
            mergedAtStart,
            mergedAtEnd,
            projectName,
            targetBranch,
            moduleName,
            mergeRequestIid,
            owner);
    return sourceLoader.loadSources(factFilters).stream()
        .map(this::toView)
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, keyword))
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesRequestType(row.requestType(), requestType))
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesEquals(row.mergedBy(), mergedBy))
        .toList();
  }

  private CodeReviewRuleConfig parseRuleConfig(String ruleConfigJson) {
    String normalized = TextQuerySupport.trimToNull(ruleConfigJson);
    if (normalized == null) {
      return null;
    }
    try {
      return objectMapper.readValue(normalized, CodeReviewRuleConfig.class);
    } catch (Exception error) {
      log.warn("Failed to parse code review rule config from query", error);
      return null;
    }
  }

  private CodeReviewRulePreviewSample toRulePreviewSample(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfig ruleConfig) {
    return new CodeReviewRulePreviewSample(
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectName(),
        row.moduleName(),
        row.owner(),
        row.targetBranch(),
        row.mergeRequestContent(),
        CodeReviewRuleConfigSupport.explainRow(row, ruleConfig));
  }

  private void refreshMirrorForRealtimeView() {
    try {
      gitlabMirrorSyncService.refreshTablesOnDemand(
          REALTIME_REFRESH_TABLES, "code-review-illegal-records");
      factBuildService.rebuildMergeRequestFacts(false);
    } catch (Exception e) {
      log.warn(
          "On-demand mirror refresh for code review illegal records failed, fallback to current mirror snapshot",
          e);
    }
  }

  private CodeReviewIllegalRecordView toView(CodeReviewIllegalRecordSource source) {
    List<String> illegalTypes = CodeReviewIllegalRuleRegistry.evaluateIllegalTypes(source);
    String mergeRequestLink =
        buildMergeRequestLink(source.repositoryName(), source.mergeRequestIid());
    return new CodeReviewIllegalRecordView(
        "merge_request",
        source.mergeRequestId(),
        source.mergeRequestIid(),
        source.projectId(),
        TextQuerySupport.normalizeDisplay(source.mergeRequestContent()),
        mergeRequestLink,
        TextQuerySupport.normalizeDisplay(source.owner()),
        TextQuerySupport.normalizeDisplay(source.projectName()),
        TextQuerySupport.normalizeDisplay(source.repositoryName()),
        source.mergedAt(),
        TextQuerySupport.normalizeDisplay(source.mergedBy()),
        TextQuerySupport.normalizeDisplay(source.moduleName()),
        TextQuerySupport.normalizeDisplay(source.targetBranch()),
        illegalTypes,
        TextQuerySupport.normalizeDisplay(source.reviewStatus()),
        source.reviewDurationMinutes(),
        TextQuerySupport.normalizeDisplay(source.scanStatus()),
        source.scanBugCount(),
        source.commentRate(),
        source.defectCount(),
        source.addedLines());
  }

  private List<StatisticRuleFlowStep> buildRuleFlowSteps(
      List<CodeReviewIllegalRecordView> views,
      List<CodeReviewIllegalRecordView> illegalViews,
      long total,
      long illegalTotal) {
    List<StatisticRuleFlowStep> steps = new ArrayList<>();
    steps.add(
        new StatisticRuleFlowStep(
            "source-load",
            "加载合并请求事实",
            "从 merge_request_fact 读取已经归一化的合并请求、责任人、模块和指标数据。",
            total,
            total,
            sampleIllegalRecords(views)));
    steps.add(
        new StatisticRuleFlowStep(
            "illegal-total",
            "汇总非法记录",
            "只要命中任意一条非法判定规则，这条合并请求就会出现在非法记录列表里。",
            total,
            illegalTotal,
            sampleIllegalRecords(illegalViews)));
    CodeReviewIllegalRuleRegistry.explanationGroups()
        .forEach(
            group ->
                steps.add(
                    new StatisticRuleFlowStep(
                        group.key(),
                        group.title(),
                        group.description(),
                        illegalTotal,
                        CodeReviewIllegalRuleRegistry.countMatches(illegalViews, group),
                        sampleIllegalRecords(
                            CodeReviewIllegalRuleRegistry.filterMatches(illegalViews, group)))));
    return steps;
  }

  private List<StatisticRuleMetricDefinition> buildMetricDefinitions() {
    return List.of(
        new StatisticRuleMetricDefinition(
            "illegalTypes",
            "非法类型",
            "系统会根据规则检查结果，标记这条记录需要补充哪些信息。",
            "非法类型 = 缺少模块标签 / 缺少标注责任人 / 无代码走查 / 未代码扫描 / 静态扫描问题未关闭 / 缺少外部指标",
            "一条记录可以同时命中多种非法类型。"),
        new StatisticRuleMetricDefinition(
            "reviewStatus",
            "代码走查记录",
            "表示这条合并请求是否已经形成可识别的代码走查记录。",
            "代码走查记录 = 评审表单时长或走查状态已形成有效值",
            "如果当前还没有形成有效走查记录，这条记录会被判定为“无代码走查”。"),
        new StatisticRuleMetricDefinition(
            "moduleName",
            "模块名称",
            "表示这条合并请求所属的功能模块。",
            "模块名称来自合并请求关联的模块标识。",
            "如果缺少模块信息，这条记录会被判定为需要关注。"),
        new StatisticRuleMetricDefinition(
            "scanStatus",
            "代码扫描结果",
            "表示这条合并请求是否已经完成静态扫描，以及静态扫描问题是否已经清理。",
            "未代码扫描 = 明确标记为未扫描；静态扫描问题未关闭 = 扫描问题数大于 0",
            "只有事实层中已经带出扫描状态时，才会命中这类非法规则。"),
        new StatisticRuleMetricDefinition(
            "commentRate",
            "代码注释比例",
            "表示本次改动中代码注释的覆盖情况。",
            "代码注释比例 = 外部工具结果 或 MR 机器人解析结果",
            "如果缺少该指标，这条记录会被判定为需要关注。"),
        new StatisticRuleMetricDefinition(
            "defectCount",
            "缺陷数量",
            "表示本次改动关联的缺陷数量。",
            "缺陷数量 = MR 评论 / 机器人结果 / Sonar 汇总结果",
            "如果缺少该指标，这条记录会被判定为需要关注。"),
        new StatisticRuleMetricDefinition(
            "addedLines",
            "新增代码行数",
            "表示本次合并请求新增的代码规模。",
            "新增代码行数 = 本次改动新增代码行数",
            "如果缺少该指标，这条记录也会被判定为需要关注。"));
  }

  private StatisticRuleFlowStepSample toIllegalRecordSample(CodeReviewIllegalRecordView row) {
    return new StatisticRuleFlowStepSample(
        "MR #" + row.mergeRequestIid(),
        row.projectName() + " | "
            + (row.illegalTypes().isEmpty() ? "无非法类型" : String.join("、", row.illegalTypes())));
  }

  private List<StatisticRuleFlowStepSample> sampleIllegalRecords(List<CodeReviewIllegalRecordView> rows) {
    return rows.stream().limit(3).map(this::toIllegalRecordSample).toList();
  }

  private String buildMergeRequestLink(String repositoryName, Integer mergeRequestIid) {
    if (!StringUtils.hasText(defaultGitlabBaseUrl)
        || !StringUtils.hasText(repositoryName)
        || mergeRequestIid == null) {
      return null;
    }
    return defaultGitlabBaseUrl.replaceAll("/+$", "")
        + "/"
        + repositoryName
        + "/-/merge_requests/"
        + mergeRequestIid;
  }

  private CodeReviewIllegalRecordRowResponse toResponse(CodeReviewIllegalRecordView row) {
    return toResponse(row, null);
  }

  private CodeReviewIllegalRecordRowResponse toResponse(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfig ruleConfig) {
    String link = TextQuerySupport.trimToNull(row.mergeRequestLink());
    List<String> illegalTypes =
        ruleConfig == null
            ? row.illegalTypes()
            : CodeReviewRuleConfigSupport.explainRow(row, ruleConfig).stream()
                .map(reason -> reason.replaceFirst("^满足：", ""))
                .toList();
    return new CodeReviewIllegalRecordRowResponse(
        row.requestType(),
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectId(),
        row.mergeRequestContent(),
        link,
        row.owner(),
        row.projectName(),
        row.repositoryName(),
        row.mergedAt(),
        row.mergedBy(),
        row.moduleName(),
        row.targetBranch(),
        illegalTypes,
        row.commentRate(),
        row.defectCount(),
        row.addedLines());
  }

  private List<OptionItemResponse> toOptions(
      List<CodeReviewIllegalRecordView> rows,
      Function<CodeReviewIllegalRecordView, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }
}
