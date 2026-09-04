package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 代码走查非法记录服务承接规则配置、实时刷新、记录查询、导出和规则说明的编排。
// 默认规则尽量下沉到 SQL 查询；用户自定义规则则通过规则配置和源数据加载器组合执行。
// Excel 导出、筛选候选装配和响应映射分别委托给专属协作者。
@Service
@Slf4j
public class CodeReviewIllegalRecordService implements PageRecordSnapshotRefresher {
  public static final String WORKSPACE_KEY = "code-review-illegal-records";
  private static final String LEGACY_DEFAULT_SOURCE = "cc";
  private static final String LEGACY_DEFAULT_REPOSITORY_NAME = "CrownCAD";
  private static final String LEGACY_DGM_REPOSITORY_NAME = "DGM";
  private static final String RULE_VERSION = "code-review-illegal-records@2026-07-10-v8";
  private static final int EXPORT_PAGE_SIZE = 100;

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final FactBuildService factBuildService;
  private final CodeReviewIllegalRecordSourceLoader sourceLoader;
  private final CodeReviewMatchModeRecordLoader matchModeRecordLoader;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final CodeReviewMatchModeLegacyRefreshService matchModeLegacyRefreshService;
  private final LegacyPlatformFormalImportService legacyPlatformFormalImportService;
  private final CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService;
  private final ObjectMapper objectMapper;
  private final PageRecordSnapshotService pageRecordSnapshotService;
  private final CodeReviewIllegalRecordExcelExporter excelExporter;
  private final CodeReviewIllegalRecordFilterOptionAssembler filterOptionAssembler;
  private final CodeReviewIllegalRecordResponseMapper responseMapper;

  public CodeReviewIllegalRecordService(
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService,
      FactBuildService factBuildService,
      CodeReviewIllegalRecordSourceLoader sourceLoader,
      CodeReviewMatchModeRecordLoader matchModeRecordLoader,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      CodeReviewMatchModeLegacyRefreshService matchModeLegacyRefreshService,
      LegacyPlatformFormalImportService legacyPlatformFormalImportService,
      CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService,
      ObjectMapper objectMapper,
      PageRecordSnapshotService pageRecordSnapshotService,
      CodeReviewIllegalRecordExcelExporter excelExporter,
      CodeReviewIllegalRecordFilterOptionAssembler filterOptionAssembler,
      CodeReviewIllegalRecordResponseMapper responseMapper) {
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
    this.factBuildService = factBuildService;
    this.sourceLoader = sourceLoader;
    this.matchModeRecordLoader = matchModeRecordLoader;
    this.matchModeSwitchService = matchModeSwitchService;
    this.matchModeLegacyRefreshService = matchModeLegacyRefreshService;
    this.legacyPlatformFormalImportService = legacyPlatformFormalImportService;
    this.dgmProjectOptionService = dgmProjectOptionService;
    this.objectMapper = objectMapper;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
    this.excelExporter = excelExporter;
    this.filterOptionAssembler = filterOptionAssembler;
    this.responseMapper = responseMapper;
  }

  public CodeReviewIllegalRecordListResponse listRecords(CodeReviewIllegalRecordQueryRequest request) {
    CodeReviewIllegalRecordQueryRequest safeRequest = withLegacyDefaultScope(request);
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_LIST,
            codeReviewScopeKey(safeRequest),
            safeRequest),
        CodeReviewIllegalRecordListResponse.class,
        () -> loadRecords(safeRequest));
  }

  private CodeReviewIllegalRecordListResponse loadRecords(CodeReviewIllegalRecordQueryRequest request) {
    CodeReviewIllegalRecordQueryRequest safeRequest = withLegacyDefaultScope(request);
    int safePage = request.page() <= 0 ? 1 : request.page();
    int safeSize = request.size() <= 0 ? 20 : Math.min(request.size(), 100);
    String safeSortField = CodeReviewIllegalRecordQuerySupport.normalizeSortField(safeRequest.sortField());
    String safeSortOrder = CodeReviewIllegalRecordQuerySupport.normalizeSortOrder(safeRequest.sortOrder());
    CodeReviewRuleConfig ruleConfig = parseRuleConfig(safeRequest.ruleConfigJson());
    StatisticFilterGroup filterGroup =
        CodeReviewIllegalRecordFilterGroupSupport.parse(objectMapper, safeRequest.filterGroupJson());
    if (canUseDefaultSqlPage(safeRequest)) {
      PageSlice<CodeReviewIllegalRecordSource> sourcePage =
          activeLoader().loadDefaultIllegalPage(
              new CodeReviewIllegalRecordSourcePageQuery(
                  safeRequest, filterGroup, safePage, safeSize, safeSortField, safeSortOrder));
      CodeReviewRuleConfig responseRuleConfig = null;
      List<CodeReviewIllegalRecordRowResponse> records =
          sourcePage.records().stream()
              .map(responseMapper::toView)
              .map(row -> responseMapper.toResponse(row, responseRuleConfig))
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
            safeRequest.projectId(),
            safeRequest.repositoryName(),
            safeRequest.mergedAtStart(),
            safeRequest.mergedAtEnd(),
            safeRequest.keyword(),
            safeRequest.projectName(),
            safeRequest.requestType(),
            safeRequest.targetBranch(),
            safeRequest.mergedBy(),
            safeRequest.moduleName(),
            safeRequest.mergeRequestIid(),
            safeRequest.owner(),
            safeRequest.source());
    List<CodeReviewIllegalRecordView> judgedRows =
        CodeReviewRuleConfigSupport.hasReadyConfig(ruleConfig)
            ? CodeReviewRuleConfigSupport.apply(scopedRows, ruleConfig)
            : scopedRows.stream()
                .filter(row -> CodeReviewIllegalRuleRegistry.matchesDefaultIllegalType(row.illegalTypes(), row.sourceInstance()))
                .toList();
    List<CodeReviewIllegalRecordView> filtered =
        judgedRows.stream()
            .filter(row -> CodeReviewIllegalRecordFilterGroupSupport.matches(row, filterGroup))
            .filter(
                row ->
                    CodeReviewIllegalRecordQuerySupport.matchesIllegalType(
                        row.illegalTypes(), safeRequest.illegalType()))
            .sorted(CodeReviewIllegalRecordQuerySupport.buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<CodeReviewIllegalRecordView> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);
    CodeReviewRuleConfig responseRuleConfig =
        CodeReviewRuleConfigSupport.hasReadyConfig(ruleConfig) ? ruleConfig : null;
    List<CodeReviewIllegalRecordRowResponse> records =
        pageSlice.records().stream().map(row -> responseMapper.toResponse(row, responseRuleConfig)).toList();

    return new CodeReviewIllegalRecordListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public byte[] exportRecordsWorkbook(CodeReviewIllegalRecordQueryRequest request) {
    List<CodeReviewIllegalRecordRowResponse> illegalRows = loadAllRows(request);
    if (shouldExportAllCodeReviewSheet(request)) {
      return excelExporter.exportWorkbook(
          "非法代码走查数据", illegalRows, "全量代码走查数据", loadAllRowsWithoutIllegalType(request));
    }
    return excelExporter.exportWorkbook("非法代码走查数据", illegalRows, null, null);
  }

  private List<CodeReviewIllegalRecordRowResponse> loadAllRows(CodeReviewIllegalRecordQueryRequest request) {
    CodeReviewIllegalRecordQueryRequest allQuery = pageRequest(request, 1, request.illegalType());
    if (canUseDefaultSqlPage(allQuery)) {
      StatisticFilterGroup filterGroup =
          CodeReviewIllegalRecordFilterGroupSupport.parse(objectMapper, allQuery.filterGroupJson());
      return activeLoader().loadDefaultIllegalExportSources(allQuery, filterGroup).stream()
          .map(responseMapper::toView)
          .map(responseMapper::toResponse)
          .toList();
    }
    List<CodeReviewIllegalRecordRowResponse> rows = new ArrayList<>();
    int page = 1;
    while (true) {
      CodeReviewIllegalRecordListResponse response = listRecords(pageRequest(request, page, request.illegalType()));
      rows.addAll(response.records());
      if (response.records().size() < EXPORT_PAGE_SIZE || rows.size() >= response.total()) {
        break;
      }
      page += 1;
    }
    return rows;
  }

  private List<CodeReviewIllegalRecordRowResponse> loadAllRowsWithoutIllegalType(
      CodeReviewIllegalRecordQueryRequest request) {
    CodeReviewIllegalRecordQueryRequest allQuery = pageRequest(request, 1, null);
    StatisticFilterGroup filterGroup =
        CodeReviewIllegalRecordFilterGroupSupport.parse(objectMapper, allQuery.filterGroupJson());
    return activeLoader().loadLegacyAllExportSources(allQuery, filterGroup).stream()
        .map(responseMapper::toView)
        .map(responseMapper::toResponse)
        .toList();
  }

  private CodeReviewIllegalRecordQueryRequest pageRequest(
      CodeReviewIllegalRecordQueryRequest request, int page, String illegalType) {
    CodeReviewIllegalRecordQueryRequest safeRequest = withLegacyDefaultScope(request);
    return new CodeReviewIllegalRecordQueryRequest(
        safeRequest.projectId(),
        safeRequest.repositoryName(),
        safeRequest.mergedAtStart(),
        safeRequest.mergedAtEnd(),
        safeRequest.keyword(),
        safeRequest.projectName(),
        safeRequest.requestType(),
        safeRequest.targetBranch(),
        safeRequest.mergedBy(),
        safeRequest.moduleName(),
        illegalType,
        safeRequest.mergeRequestIid(),
        safeRequest.owner(),
        safeRequest.source(),
        safeRequest.filterGroupJson(),
        page,
        EXPORT_PAGE_SIZE,
        safeRequest.sortField(),
        safeRequest.sortOrder(),
        safeRequest.ruleConfigJson());
  }

  private CodeReviewIllegalRecordQueryRequest defaultRequest(String source, String repositoryName) {
    return new CodeReviewIllegalRecordQueryRequest(
        null,
        repositoryName,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        source,
        null,
        1,
        20,
        "mergedAt",
        "descending",
        null);
  }

  private PageRecordSnapshotService.SnapshotRequest snapshotRequest(
      String snapshotType, String scopeKey, Object requestPayload) {
    //兼容模式-MatchMode：正式事实表与兼容表必须使用不同快照命名空间。
    //后续删除兼容模式时只需移除此维度，不需要清理散落在设置保存流程中的手工失效逻辑。
    String readModeScope = codeReviewCompatibilityReadEnabled() ? "match-mode" : "formal";
    return new PageRecordSnapshotService.SnapshotRequest(
        WORKSPACE_KEY,
        snapshotType,
        scopeKey + "|readMode:" + readModeScope,
        RULE_VERSION,
        pageRecordSnapshotService.codeReviewSourceVersion(),
        requestPayload);
  }

  private String codeReviewScopeKey(CodeReviewIllegalRecordQueryRequest request) {
    String source = TextQuerySupport.trimToNull(request.source());
    String repositoryName = TextQuerySupport.trimToNull(request.repositoryName());
    return "source:" + (source == null ? "default" : source)
        + "|repository:" + (repositoryName == null ? "all" : repositoryName);
  }

  private String codeReviewScopeKey(CodeReviewIllegalRecordFilterOptionsRequest request) {
    String source = TextQuerySupport.trimToNull(request.source());
    String repositoryName = TextQuerySupport.trimToNull(request.repositoryName());
    String projectName = TextQuerySupport.trimToNull(request.projectName());
    return "source:" + (source == null ? "default" : source)
        + "|repository:" + (repositoryName == null ? "all" : repositoryName)
        + "|project:" + (projectName == null ? "all" : projectName);
  }

  private boolean shouldExportAllCodeReviewSheet(CodeReviewIllegalRecordQueryRequest request) {
    if (!codeReviewCompatibilityReadEnabled()) {
      return false;
    }
    String repositoryName = TextQuerySupport.trimToNull(withLegacyDefaultScope(request).repositoryName());
    return repositoryName != null && !"CrownCAD".equalsIgnoreCase(repositoryName);
  }

  private boolean canUseDefaultSqlPage(CodeReviewIllegalRecordQueryRequest request) {
    return TextQuerySupport.trimToNull(request.ruleConfigJson()) == null;
  }

  public CodeReviewIllegalRecordFilterOptionsResponse getFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    CodeReviewIllegalRecordFilterOptionsRequest safeRequest =
        request == null ? new CodeReviewIllegalRecordFilterOptionsRequest(null, null, null, null) : request;
    return pageRecordSnapshotService.readOrRefresh(
        snapshotRequest(
            PageRecordSnapshotService.SNAPSHOT_TYPE_FILTER_OPTIONS,
            codeReviewScopeKey(safeRequest),
            safeRequest),
        CodeReviewIllegalRecordFilterOptionsResponse.class,
        () -> loadFilterOptions(safeRequest));
  }

  private CodeReviewIllegalRecordFilterOptionsResponse loadFilterOptions(
      CodeReviewIllegalRecordFilterOptionsRequest request) {
    String source = request == null ? null : request.source();
    Long projectId = request == null ? null : request.projectId();
    String repositoryName = request == null ? null : request.repositoryName();
    String projectName = request == null ? null : request.projectName();
    boolean matchMode = codeReviewCompatibilityReadEnabled();
    String scopedRepositoryName =
        matchMode ? defaultLegacyRepositoryName(repositoryName, source) : repositoryName;
    CodeReviewIllegalRecordFilterOptionValues options =
        activeLoader()
            .loadFilterOptions(
                new CodeReviewIllegalRecordFilterOptionsRequest(
                    projectId, scopedRepositoryName, projectName, source));

    return new CodeReviewIllegalRecordFilterOptionsResponse(
        filterOptionAssembler.requestTypeOptions(),
        filterOptionAssembler.toProjectOptions(options.projects()),
        filterOptionAssembler.toCodeReviewRepositoryNameOptions(matchMode, options.repositoryNames()),
        filterOptionAssembler.legacyIllegalTypeOptions(),
        filterOptionAssembler.toLegacyOptions(options.targetBranches()),
        filterOptionAssembler.toLegacyOptions(options.owners()),
        filterOptionAssembler.toLegacyOptions(options.mergedBys()),
        filterOptionAssembler.toLegacyOptions(options.moduleNames()),
        filterOptionAssembler.toCodeReviewProjectNameOptions(matchMode, source, options.projectNames(), dgmProjectOptionService));
  }

  @Override
  public void refreshRecordSnapshots(com.data.collection.platform.entity.FactPublicationContext context) {
    if (context == null
        || !context.covers(
            FactType.MERGE_REQUEST,
            ProjectionScopeType.GLOBAL_VIEW,
            FactProjectionScopeKeyCodec.SINGLETON_SCOPE_KEY)) {
      return;
    }
    getFilterOptions(new CodeReviewIllegalRecordFilterOptionsRequest(null, LEGACY_DEFAULT_REPOSITORY_NAME, null, "cc"));
    listRecords(defaultRequest("cc", LEGACY_DEFAULT_REPOSITORY_NAME));
    getFilterOptions(new CodeReviewIllegalRecordFilterOptionsRequest(null, LEGACY_DGM_REPOSITORY_NAME, null, "dgm"));
    listRecords(defaultRequest("dgm", LEGACY_DGM_REPOSITORY_NAME));
  }

  public RealtimeWorkspaceStatusResponse getRealtimeStatus() {
    return realtimeWorkspaceService.getStatus(WORKSPACE_KEY);
  }

  public RealtimeWorkspaceStatusResponse getRealtimeStatus(String source) {
    String normalizedSource = TextQuerySupport.trimToNull(source);
    return realtimeWorkspaceService.getStatus(
        WORKSPACE_KEY,
        normalizedSource == null ? Map.of() : Map.of("source", normalizedSource));
  }

  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh() {
    //兼容模式-MatchMode
    if (codeReviewCompatibilityReadEnabled()) {
      return new RealtimeWorkspaceStatusResponse(
          WORKSPACE_KEY,
          false,
          "IDLE",
          "当前数据由后台定时同步，页面继续展示最近一次完成同步的数据。",
          false,
          null,
          null,
          null);
    }
    return realtimeWorkspaceService.requestRefreshWithResult(WORKSPACE_KEY, this::refreshMirrorForRealtimeView);
  }

  public CodeReviewIllegalRecordRowResponse refreshSingleRecord(
      String source, Long projectId, Long mergeRequestIid) {
    //兼容模式-MatchMode：对齐老平台行级刷新，先调用老平台后端接口，再同步这一条 MR 的全部拆分行。
    if (codeReviewCompatibilityReadEnabled()) {
      matchModeLegacyRefreshService.refreshOne(source, mergeRequestIid);
      pageRecordSnapshotService.invalidatePage(WORKSPACE_KEY);
      return firstScopedRow(null, null, String.valueOf(mergeRequestIid), source);
    }
    //兼容模式-MatchMode：若代码走查数据已从老平台转入正式事实表，行级刷新仍按老平台源刷新后再提升到正式事实。
    if (legacyPlatformFormalImportService.hasPromotedCodeReviewData(source)) {
      legacyPlatformFormalImportService.refreshAndPromoteCodeReviewRecord(source, mergeRequestIid);
      return firstScopedRow(null, null, String.valueOf(mergeRequestIid), source);
    }
    factBuildService.rebuildMergeRequestFactByIid(source, projectId, mergeRequestIid);
    pageRecordSnapshotService.invalidatePage(WORKSPACE_KEY);
    return firstScopedRow(projectId, null, String.valueOf(mergeRequestIid), source);
  }

  private CodeReviewIllegalRecordRowResponse firstScopedRow(
      Long projectId, String repositoryName, String mergeRequestIid, String source) {
    List<CodeReviewIllegalRecordView> rows =
        loadScopedViews(
            projectId,
            repositoryName,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            String.valueOf(mergeRequestIid),
            null,
            source);
    return rows.stream()
        .map(responseMapper::toResponse)
        .findFirst()
        .orElse(null);
  }

  public StatisticBoardRuleExplanationResponse getRuleExplanation() {
    return new StatisticBoardRuleExplanationResponse(
        WORKSPACE_KEY,
        true,
        "代码走查非法记录规则说明",
        RULE_VERSION,
        "当前统计范围内的代码合并请求数据会继续按页面查询条件筛选。",
        "规则说明只展示判定口径，不在打开说明时扫描全量记录；实际数量以当前列表、筛选和导出结果为准。",
        List.of(),
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
            request == null ? null : request.owner(),
            request == null ? null : request.source());
    List<CodeReviewIllegalRecordView> defaultRows =
        scopedRows.stream()
            .filter(row -> CodeReviewIllegalRuleRegistry.matchesDefaultIllegalType(row.illegalTypes(), row.sourceInstance()))
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
            .map(row -> responseMapper.toRulePreviewSample(row, ruleConfig))
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
      String owner,
      String source) {
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
            owner,
            source);
    return activeLoader().loadSources(factFilters).stream()
        .map(responseMapper::toView)
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesKeyword(row, keyword))
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesRequestType(row.requestType(), requestType))
        .filter(row -> CodeReviewIllegalRecordQuerySupport.matchesEquals(row.mergedBy(), mergedBy))
        .toList();
  }

  private CodeReviewIllegalRecordQueryRequest withLegacyDefaultScope(
      CodeReviewIllegalRecordQueryRequest request) {
    String source = TextQuerySupport.trimToNull(request.source());
    String normalizedSource = source == null ? LEGACY_DEFAULT_SOURCE : GitlabSourceInstanceSupport.normalizeSourceInstance(source);
    String repositoryName =
        codeReviewCompatibilityReadEnabled()
            ? defaultLegacyRepositoryName(request.repositoryName(), normalizedSource)
            : request.repositoryName();
    return new CodeReviewIllegalRecordQueryRequest(
        request.projectId(),
        repositoryName,
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
        normalizedSource,
        request.filterGroupJson(),
        request.page(),
        request.size(),
        request.sortField(),
        request.sortOrder(),
        request.ruleConfigJson());
  }

  //兼容模式-MatchMode
  private String defaultLegacyRepositoryName(String repositoryName, String source) {
    String normalized = TextQuerySupport.trimToNull(repositoryName);
    if (normalized != null) {
      return normalized;
    }
    String normalizedSource =
        GitlabSourceInstanceSupport.normalizeSourceInstance(source == null ? LEGACY_DEFAULT_SOURCE : source);
    return "dgm".equals(normalizedSource) ? LEGACY_DGM_REPOSITORY_NAME : LEGACY_DEFAULT_REPOSITORY_NAME;
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

  private RealtimeWorkspaceRefreshResult refreshMirrorForRealtimeView() {
    return realtimeIncrementalRefreshService.requestIncrementalRefresh(
        com.data.collection.platform.entity.WorkspaceRefreshRequest.global(WORKSPACE_KEY));
  }

  //兼容模式-MatchMode
  private CodeReviewIllegalRecordSourceAccess activeLoader() {
    return codeReviewCompatibilityReadEnabled()
        ? new CodeReviewIllegalRecordSourceAccess.MatchMode(matchModeRecordLoader)
        : new CodeReviewIllegalRecordSourceAccess.Fact(sourceLoader);
  }

  //兼容模式-MatchMode
  private boolean codeReviewCompatibilityReadEnabled() {
    return matchModeSwitchService.isCodeReviewCompatibilityReadEnabled();
  }

  private sealed interface CodeReviewIllegalRecordSourceAccess {
    List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters);

    CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
        CodeReviewIllegalRecordFilterOptionsRequest request);

    PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(CodeReviewIllegalRecordSourcePageQuery query);

    List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
        CodeReviewIllegalRecordQueryRequest request,
        StatisticFilterGroup filterGroup);

    List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
        CodeReviewIllegalRecordQueryRequest request,
        StatisticFilterGroup filterGroup);

    record Fact(CodeReviewIllegalRecordSourceLoader loader) implements CodeReviewIllegalRecordSourceAccess {
      @Override
      public List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters) {
        return loader.loadSources(filters);
      }

      @Override
      public CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
          CodeReviewIllegalRecordFilterOptionsRequest request) {
        return loader.loadFilterOptions(request);
      }

      @Override
      public PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(
          CodeReviewIllegalRecordSourcePageQuery query) {
        return loader.loadDefaultIllegalPage(query);
      }

      @Override
      public List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
          CodeReviewIllegalRecordQueryRequest request,
          StatisticFilterGroup filterGroup) {
        return loader.loadDefaultIllegalExportSources(request, filterGroup);
      }

      @Override
      public List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
          CodeReviewIllegalRecordQueryRequest request,
          StatisticFilterGroup filterGroup) {
        return loader.loadLegacyAllExportSources(request, filterGroup);
      }
    }

    record MatchMode(CodeReviewMatchModeRecordLoader loader) implements CodeReviewIllegalRecordSourceAccess {
      @Override
      public List<CodeReviewIllegalRecordSource> loadSources(Map<String, String> filters) {
        return loader.loadSources(filters);
      }

      @Override
      public CodeReviewIllegalRecordFilterOptionValues loadFilterOptions(
          CodeReviewIllegalRecordFilterOptionsRequest request) {
        return loader.loadFilterOptions(request);
      }

      @Override
      public PageSlice<CodeReviewIllegalRecordSource> loadDefaultIllegalPage(
          CodeReviewIllegalRecordSourcePageQuery query) {
        return loader.loadDefaultIllegalPage(query);
      }

      @Override
      public List<CodeReviewIllegalRecordSource> loadDefaultIllegalExportSources(
          CodeReviewIllegalRecordQueryRequest request,
          StatisticFilterGroup filterGroup) {
        return loader.loadDefaultIllegalExportSources(request, filterGroup);
      }

      @Override
      public List<CodeReviewIllegalRecordSource> loadLegacyAllExportSources(
          CodeReviewIllegalRecordQueryRequest request,
          StatisticFilterGroup filterGroup) {
        return loader.loadLegacyAllExportSources(request, filterGroup);
      }
    }
  }

  private List<StatisticRuleMetricDefinition> buildMetricDefinitions() {
    return List.of(
        new StatisticRuleMetricDefinition(
            "illegalTypes",
            "非法类型",
            "系统按代码走查非法数据规则标记这条合并请求命中的非法类型。",
            "非法类型 = 未标注项目名称 / 未标注模块名称 / 无代码走查 / 未代码扫描 / 静态扫描问题未关闭 / 代码注释量未达标 / 静态扫描失败 / 注释率分析工具Clang分析错误",
            "一条记录可以同时命中多种非法类型。"),
        new StatisticRuleMetricDefinition(
            "reviewStatus",
            "代码走查记录",
            "表示这条合并请求是否已经形成可识别的代码走查记录。",
            "代码走查记录 = 评审表单时长或走查状态已形成有效值",
            "如果当前还没有形成有效走查记录，这条记录会被判定为“代码走查异常”。"),
        new StatisticRuleMetricDefinition(
            "codeWalkthroughDate",
            "走查时间",
            "表示展开行和导出中的代码走查时间。",
            "走查时间优先取 MR 评论中“## 代码走查数据”的走查评论更新时间，取不到时回退到新平台走查表单更新时间，再回退到 MR 更新时间。",
            "该时间与合并时间是两个字段，导出第一列不能用合并时间替代。"),
        new StatisticRuleMetricDefinition(
            "moduleName",
            "模块名称",
            "表示这条合并请求所属的功能模块。",
            "模块名称来自合并请求关联的模块标识。",
            "如果模块名为“未标注模块名”，这条记录会被判定为“未标注模块名”。"),
        new StatisticRuleMetricDefinition(
            "scanStatus",
            "代码扫描结果",
            "表示这条合并请求是否已经完成静态扫描，以及静态扫描问题是否已经清理。",
            "未代码扫描 = 代码扫描结果为“未进行代码扫描”；静态扫描问题未关闭 = Bug 数量不为 0。",
            "只有事实层中已经带出扫描状态时，才会命中这类非法规则。"),
        new StatisticRuleMetricDefinition(
            "commentRate",
            "代码注释比例",
            "表示本次改动中代码注释的覆盖情况。",
            "代码注释比例 = 外部工具结果或 MR 机器人解析结果；CC 低于 15%、DGM 低于 20% 判定为代码注释量未达标。",
            "如果编码规范扫描结果为“代码注释量未达标”或“注释率分析工具Clang分析错误”，也会命中对应非法类型。"),
        new StatisticRuleMetricDefinition(
            "defectCount",
            "缺陷数量",
            "表示本次改动关联的缺陷数量。",
            "缺陷数量 = MR 评论 / 机器人结果 / Sonar 汇总结果",
            "该指标用于展示和计算效率，不再单独作为默认非法类型。"),
        new StatisticRuleMetricDefinition(
            "addedLines",
            "新增代码行数",
            "表示本次合并请求新增的代码规模。",
            "新增代码行数 = 本次改动新增代码行数",
            "该指标用于展示和计算速率，不再单独作为默认非法类型。"));
  }
}
