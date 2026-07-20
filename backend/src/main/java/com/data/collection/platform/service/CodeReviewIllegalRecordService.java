package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.CodeReviewIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordListResponse;
import com.data.collection.platform.entity.CodeReviewIllegalRecordRowResponse;
import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRulePreviewResponse;
import com.data.collection.platform.entity.CodeReviewRulePreviewSample;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import com.data.collection.platform.entity.statistics.StatisticRuleMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.data.collection.platform.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
@Slf4j
// 代码走查非法记录服务承接规则配置、实时刷新、记录查询、导出和规则说明。
// 默认规则尽量下沉到 SQL 查询；用户自定义规则则通过规则配置和源数据加载器组合执行。
public class CodeReviewIllegalRecordService implements PageRecordSnapshotRefresher {
  public static final String WORKSPACE_KEY = "code-review-illegal-records";
  private static final String LEGACY_DEFAULT_SOURCE = "cc";
  private static final String LEGACY_DEFAULT_REPOSITORY_NAME = "CrownCAD";
  private static final String LEGACY_DGM_REPOSITORY_NAME = "DGM";
  private static final List<String> LEGACY_EXTRA_PROJECT_NAME_OPTIONS =
      List.of("广数CAM", "CC2025R4", "CC2026R1", "CC 2025 R4&2026 R1");
  private static final String RULE_VERSION = "code-review-illegal-records@2026-07-10-v8";
  private static final int EXPORT_PAGE_SIZE = 100;
  private static final DateTimeFormatter CSV_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final String[] LEGACY_EXPORT_HEADERS = {
    "走查时间",
    "项目名",
    "模块名",
    "合并请求状态",
    "合并请求编号",
    "合并请求内容",
    "被走查人",
    "走查人",
    "被指派人",
    "合并时间",
    "合并人",
    "走查工作量（分钟）",
    "新增走查代码行数（LOC）",
    "删除走查代码行数（LOC）",
    "规范类缺陷数（个）",
    "逻辑类缺陷数（个）",
    "性能类缺陷个数",
    "设计类缺陷个数",
    "其他类缺陷数（个）",
    "缺陷数（个）",
    "代码走查速率（LOC/H）",
    "代码走查速率（KLOC/H）",
    "代码走查缺陷密度（个/KLOC）",
    "代码走查效率（个/H）",
    "合并目标分支",
    "是否进行sonQube扫描",
    "提交次数",
    "提交频率(行每次)",
    "功能名称",
    "代码注释量%",
    "编码规范扫描结果",
    "bug数量",
    "静态扫描结果",
    "所属项目名称",
    "Clang-tidy 解析的新增代码行数结果"
  };

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
  private static final List<OptionItemResponse> LEGACY_ILLEGAL_TYPE_OPTIONS =
      List.of(
          new OptionItemResponse("未标注项目名称", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_PROJECT_FILTER_LABEL),
          new OptionItemResponse("未标注模块名称", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_MODULE_FILTER_LABEL),
          new OptionItemResponse("无代码走查", CodeReviewIllegalRuleRegistry.LEGACY_MISSING_REVIEW_FILTER_LABEL),
          new OptionItemResponse("未代码扫描", CodeReviewIllegalRuleRegistry.LEGACY_NOT_SCANNED_FILTER_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL, CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.COMMENT_RATE_NOT_PASS_LABEL, CodeReviewIllegalRuleRegistry.COMMENT_RATE_NOT_PASS_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.SCAN_FAILED_LABEL, CodeReviewIllegalRuleRegistry.SCAN_FAILED_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.CLANG_RESULT_FALSE_LABEL, CodeReviewIllegalRuleRegistry.CLANG_RESULT_FALSE_LABEL),
          new OptionItemResponse(CodeReviewIllegalRuleRegistry.GITLAB_ERROR_LABEL, CodeReviewIllegalRuleRegistry.GITLAB_ERROR_LABEL));

  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;
  private final FactBuildService factBuildService;
  private final CodeReviewIllegalRecordSourceLoader sourceLoader;
  private final CodeReviewMatchModeRecordLoader matchModeRecordLoader;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final CodeReviewMatchModeLegacyRefreshService matchModeLegacyRefreshService;
  private final LegacyPlatformFormalImportService legacyPlatformFormalImportService;
  private final CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService;
  private final GitlabResourceLinkService issueLinkService;
  private final ObjectMapper objectMapper;
  private final PageRecordSnapshotService pageRecordSnapshotService;

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
      GitlabResourceLinkService issueLinkService,
      ObjectMapper objectMapper,
      GitlabMirrorProperties gitlabMirrorProperties,
      PageRecordSnapshotService pageRecordSnapshotService) {
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
    this.factBuildService = factBuildService;
    this.sourceLoader = sourceLoader;
    this.matchModeRecordLoader = matchModeRecordLoader;
    this.matchModeSwitchService = matchModeSwitchService;
    this.matchModeLegacyRefreshService = matchModeLegacyRefreshService;
    this.legacyPlatformFormalImportService = legacyPlatformFormalImportService;
    this.dgmProjectOptionService = dgmProjectOptionService;
    this.issueLinkService = issueLinkService;
    this.objectMapper = objectMapper;
    this.pageRecordSnapshotService = pageRecordSnapshotService;
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
              request.source(),
              request.filterGroupJson(),
              page,
              EXPORT_PAGE_SIZE,
              request.sortField(),
              request.sortOrder(),
              request.ruleConfigJson());
      CodeReviewIllegalRecordListResponse response = listRecords(pageRequest);
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
        "被走查人",
        "走查人",
        "被指派人",
        "目标分支",
        "合并人",
        "合并时间",
        "非法类型",
        "评论率",
        "缺陷数",
        "新增行数",
        "走查工作量（分钟）",
        "代码走查速率（LOC/H）",
        "代码走查缺陷密度（个/KLOC）",
        "代码走查效率（个/H）",
        "扫描状态",
        "静态扫描问题数",
        "编码规范扫描结果",
        "静态扫描结果",
        "标题",
        "链接")));
    for (CodeReviewIllegalRecordRowResponse row : rows) {
      lines.add(String.join(",", List.of(
          csv(row.requestType()),
          csv(row.mergeRequestIid()),
          csv(row.projectName()),
          csv(row.repositoryName()),
          csv(row.moduleName()),
          csv(row.author()),
          csv(row.reviewerNames()),
          csv(row.assigneeNames()),
          csv(row.targetBranch()),
          csv(row.mergedBy()),
          csv(row.mergedAt() == null ? "" : CSV_DATE_TIME.format(row.mergedAt())),
          csv(row.illegalTypes() == null ? "" : String.join("；", row.illegalTypes())),
          csv(row.commentRate()),
          csv(row.defectCount()),
          csv(row.addedLines()),
          csv(row.reviewDurationMinutes()),
          csv(row.reviewSpeedLocPerHour()),
          csv(row.defectDensityPerKloc()),
          csv(row.reviewEfficiencyPerHour()),
          csv(row.scanStatus()),
          csv(row.scanBugCount()),
          csv(row.annotationRateResult()),
          csv(row.bugCountResult()),
          csv(row.mergeRequestContent()),
          csv(row.mergeRequestLink()))));
    }
    return String.join("\n", lines) + "\n";
  }

  public byte[] exportRecordsWorkbook(CodeReviewIllegalRecordQueryRequest request) {
    List<CodeReviewIllegalRecordRowResponse> illegalRows = loadAllRows(request);
    try (Workbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ExportStyles styles = new ExportStyles(workbook);
      writeLegacySheet(workbook, styles, "非法代码走查数据", illegalRows);
      if (shouldExportAllCodeReviewSheet(request)) {
        writeLegacySheet(workbook, styles, "全量代码走查数据", loadAllRowsWithoutIllegalType(request));
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException e) {
      throw new BizException("代码走查非法数据 Excel 导出失败");
    }
  }

  private List<CodeReviewIllegalRecordRowResponse> loadAllRows(CodeReviewIllegalRecordQueryRequest request) {
    CodeReviewIllegalRecordQueryRequest allQuery = pageRequest(request, 1, request.illegalType());
    if (canUseDefaultSqlPage(allQuery)) {
      StatisticFilterGroup filterGroup =
          CodeReviewIllegalRecordFilterGroupSupport.parse(objectMapper, allQuery.filterGroupJson());
      List<CodeReviewIllegalRecordRowResponse> rows =
          activeLoader().loadDefaultIllegalExportSources(allQuery, filterGroup).stream()
              .map(this::toView)
              .map(this::toResponse)
              .toList();
      return rows;
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
    List<CodeReviewIllegalRecordRowResponse> rows =
        activeLoader().loadLegacyAllExportSources(allQuery, filterGroup).stream()
            .map(this::toView)
            .map(this::toResponse)
            .toList();
    return rows;
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

  private void writeLegacySheet(
      Workbook workbook,
      ExportStyles styles,
      String sheetName,
      List<CodeReviewIllegalRecordRowResponse> rows) {
    var sheet = workbook.createSheet(sheetName);
    writeHeader(sheet.createRow(0), styles.header, LEGACY_EXPORT_HEADERS);
    int rowIndex = 1;
    for (CodeReviewIllegalRecordRowResponse row : rows) {
      writeLegacyExportRow(sheet.createRow(rowIndex++), row, styles.body);
    }
    setColumnWidths(
        sheet,
        18, 18, 16, 14, 14, 36, 16, 22, 22, 20, 16, 18, 20, 20, 18, 18, 18, 18,
        18, 14, 20, 20, 24, 20, 18, 20, 12, 18, 20, 14, 22, 12, 22, 18, 28);
    sheet.createFreezePane(0, 1);
    ExcelExportStyles.applyHeaderRows(sheet, 1);
  }

  private void writeHeader(Row row, CellStyle style, String[] headers) {
    for (int index = 0; index < headers.length; index++) {
      writeText(row, index, headers[index], style);
    }
  }

  private void writeLegacyExportRow(
      Row row, CodeReviewIllegalRecordRowResponse record, CellStyle style) {
    writeText(row, 0, formatDate(record.codeWalkthroughDate()), style);
    writeText(row, 1, record.projectName(), style);
    writeText(row, 2, record.moduleName(), style);
    writeText(row, 3, "MERGED", style);
    writeNumber(row, 4, record.mergeRequestIid(), style);
    writeText(row, 5, record.mergeRequestContent(), style);
    writeText(row, 6, record.author(), style);
    writeText(row, 7, record.reviewerNames(), style);
    writeText(row, 8, record.assigneeNames(), style);
    writeText(row, 9, formatDateTime(record.mergedAt()), style);
    writeText(row, 10, record.mergedBy(), style);
    writeNumber(row, 11, record.reviewDurationMinutes(), style);
    writeNumber(row, 12, record.addedLines(), style);
    writeNumber(row, 13, record.deletedLines(), style);
    writeNumber(row, 14, record.codeSpecificationCount(), style);
    writeNumber(row, 15, record.codeLogicSpecificationCount(), style);
    writeNumber(row, 16, record.performanceSpecificationCount(), style);
    writeNumber(row, 17, record.designSpecificationCount(), style);
    writeNumber(row, 18, record.otherSpecificationCount(), style);
    writeNumber(row, 19, record.defectCount(), style);
    writeNumber(row, 20, record.reviewSpeedLocPerHour(), style);
    writeNumber(row, 21, record.reviewSpeedKlocPerHour(), style);
    writeNumber(row, 22, record.defectDensityPerKloc(), style);
    writeNumber(row, 23, record.reviewEfficiencyPerHour(), style);
    writeText(row, 24, record.targetBranch(), style);
    writeText(row, 25, record.scanStatus(), style);
    writeNumber(row, 26, record.commitCount(), style);
    writeNumber(row, 27, record.commitRate(), style);
    writeText(row, 28, record.functionName(), style);
    writeNumber(row, 29, record.commentRate(), style);
    writeText(row, 30, record.annotationRateResult(), style);
    writeNumber(row, 31, record.scanBugCount(), style);
    writeText(row, 32, record.bugCountResult(), style);
    writeText(row, 33, record.repositoryName(), style);
    writeNumber(row, 34, record.clangAddedLineCount(), style);
  }

  private void writeText(Row row, int column, String value, CellStyle style) {
    var cell = row.createCell(column);
    cell.setCellValue(value == null ? "" : value);
    cell.setCellStyle(style);
  }

  private void writeNumber(Row row, int column, Number value, CellStyle style) {
    var cell = row.createCell(column);
    if (value != null) {
      cell.setCellValue(value.doubleValue());
    }
    cell.setCellStyle(style);
  }

  private void setColumnWidths(org.apache.poi.ss.usermodel.Sheet sheet, int... widths) {
    ExcelExportStyles.setReadableColumnWidths(sheet, widths);
  }

  private String formatDateTime(java.time.LocalDateTime value) {
    return value == null ? "" : CSV_DATE_TIME.format(value);
  }

  private String formatDate(java.time.LocalDateTime value) {
    return value == null ? "" : CSV_DATE.format(value);
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
        REQUEST_TYPE_OPTIONS,
        toProjectOptions(options.projects()),
        toCodeReviewRepositoryNameOptions(matchMode, options.repositoryNames()),
        LEGACY_ILLEGAL_TYPE_OPTIONS,
        toLegacyOptions(options.targetBranches()),
        toLegacyOptions(options.owners()),
        toLegacyOptions(options.mergedBys()),
        toLegacyOptions(options.moduleNames()),
        toCodeReviewProjectNameOptions(matchMode, source, options.projectNames()));
  }

  @Override
  public void refreshRecordSnapshots(PageRecordSnapshotRefresher.RefreshContext context) {
    if (!context.affectsMergeRequests()) {
      return;
    }
    getFilterOptions(new CodeReviewIllegalRecordFilterOptionsRequest(null, LEGACY_DEFAULT_REPOSITORY_NAME, null, "cc"));
    listRecords(defaultRequest("cc", LEGACY_DEFAULT_REPOSITORY_NAME));
    getFilterOptions(new CodeReviewIllegalRecordFilterOptionsRequest(null, LEGACY_DGM_REPOSITORY_NAME, null, "dgm"));
    listRecords(defaultRequest("dgm", LEGACY_DGM_REPOSITORY_NAME));
  }

  private List<OptionItemResponse> toProjectOptions(
      List<CodeReviewIllegalRecordFilterProjectOption> projects) {
    return projects.stream()
        .filter(project -> project.projectId() != null)
        .collect(
            java.util.stream.Collectors.toMap(
                CodeReviewIllegalRecordFilterProjectOption::projectId,
                project -> new OptionItemResponse(
                    projectOptionLabel(project), String.valueOf(project.projectId())),
                (left, right) -> left,
                java.util.LinkedHashMap::new))
        .values()
        .stream()
        .toList();
  }

  private String projectOptionLabel(CodeReviewIllegalRecordFilterProjectOption project) {
    String name = TextQuerySupport.trimToNull(project.projectName());
    String projectId = String.valueOf(project.projectId());
    return name == null ? projectId : name + " / " + projectId;
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
      List<CodeReviewIllegalRecordView> rows =
          loadScopedViews(
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
              String.valueOf(mergeRequestIid),
              null,
              source);
      return rows.stream()
          .map(this::toResponse)
          .findFirst()
          .orElse(null);
    }
    //兼容模式-MatchMode：若代码走查数据已从老平台转入正式事实表，行级刷新仍按老平台源刷新后再提升到正式事实。
    if (legacyPlatformFormalImportService.hasPromotedCodeReviewData(source)) {
      legacyPlatformFormalImportService.refreshAndPromoteCodeReviewRecord(source, mergeRequestIid);
      List<CodeReviewIllegalRecordView> rows =
          loadScopedViews(
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
              String.valueOf(mergeRequestIid),
              null,
              source);
      return rows.stream()
          .map(this::toResponse)
          .findFirst()
          .orElse(null);
    }
    factBuildService.rebuildMergeRequestFactByIid(source, projectId, mergeRequestIid);
    pageRecordSnapshotService.invalidatePage(WORKSPACE_KEY);
    List<CodeReviewIllegalRecordView> rows =
        loadScopedViews(
            projectId,
            null,
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
        .map(this::toResponse)
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
        .map(this::toView)
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

  private CodeReviewRulePreviewSample toRulePreviewSample(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfig ruleConfig) {
    return new CodeReviewRulePreviewSample(
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectName(),
        row.moduleName(),
        row.author(),
        row.targetBranch(),
        row.mergeRequestContent(),
        CodeReviewRuleConfigSupport.explainRow(row, ruleConfig));
  }

  private RealtimeWorkspaceRefreshResult refreshMirrorForRealtimeView() {
    return realtimeIncrementalRefreshService.requestIncrementalRefresh(WORKSPACE_KEY, REALTIME_REFRESH_TABLES);
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

  private CodeReviewIllegalRecordView toView(CodeReviewIllegalRecordSource source) {
    List<String> illegalTypes =
        codeReviewCompatibilityReadEnabled()
            ? CodeReviewIllegalRuleRegistry.evaluateLegacyMatchModeIllegalTypes(source)
            : CodeReviewIllegalRuleRegistry.evaluateIllegalTypes(source);
    String mergeRequestLink = mergeRequestLink(source);
    return new CodeReviewIllegalRecordView(
        "merge_request",
        source.sourceInstance(),
        source.mergeRequestId(),
        source.mergeRequestIid(),
        source.projectId(),
        TextQuerySupport.normalizeDisplay(source.mergeRequestContent()),
        mergeRequestLink,
        TextQuerySupport.normalizeDisplay(source.owner()),
        TextQuerySupport.normalizeDisplay(source.projectName()),
        TextQuerySupport.normalizeDisplay(source.repositoryName()),
        source.mergedAt(),
        TextQuerySupport.normalizeDisplay(source.author()),
        TextQuerySupport.normalizeDisplay(source.mergedBy()),
        TextQuerySupport.normalizeDisplay(source.moduleName()),
        TextQuerySupport.normalizeDisplay(source.targetBranch()),
        illegalTypes,
        TextQuerySupport.normalizeDisplay(source.reviewerNames()),
        TextQuerySupport.normalizeDisplay(source.assigneeNames()),
        TextQuerySupport.normalizeDisplay(source.reviewStatus()),
        source.reviewDurationMinutes(),
        TextQuerySupport.normalizeDisplay(source.reviewExceptionReason()),
        source.codeWalkthroughDate(),
        TextQuerySupport.normalizeDisplay(source.scanStatus()),
        source.scanBugCount(),
        TextQuerySupport.normalizeDisplay(source.annotationRateResult()),
        TextQuerySupport.normalizeDisplay(source.bugCountResult()),
        source.commentRate(),
        source.defectCount(),
        source.addedLines(),
        source.deletedLines(),
        source.codeSpecificationCount(),
        source.codeLogicSpecificationCount(),
        source.performanceSpecificationCount(),
        source.designSpecificationCount(),
        source.otherSpecificationCount(),
        source.reviewSpeedLocPerHour(),
        source.reviewSpeedKlocPerHour(),
        source.reviewDefectDensityPerKloc(),
        source.reviewEfficiencyPerHour(),
        source.commitCount(),
        displayCommitRate(source),
        TextQuerySupport.normalizeDisplay(source.functionName()),
        source.clangAddedLineCount());
  }

  private String mergeRequestLink(CodeReviewIllegalRecordSource source) {
    String link =
        issueLinkService.mergeRequestUrl(source.sourceInstance(), source.projectId(), source.mergeRequestIid());
    if (link != null || !codeReviewCompatibilityReadEnabled()) {
      return link;
    }
    //兼容模式-MatchMode：老平台代码走查表来自 MySQL 兼容表，project_id 可能为 0，
    //无法走 GitLab ODS 项目路径，需要按老平台代码走查页面的 MR 链接规则生成。
    return issueLinkService.legacyCodeReviewMergeRequestUrl(
        source.sourceInstance(), source.repositoryName(), source.mergeRequestIid());
  }

  private Integer displayCommitRate(CodeReviewIllegalRecordSource source) {
    if (!codeReviewCompatibilityReadEnabled()) {
      return source.commitRate();
    }
    return legacyCommitRate(source.addedLines(), source.commitCount());
  }

  private Integer legacyCommitRate(Integer addedLines, Integer commitCount) {
    if (addedLines == null || commitCount == null || addedLines <= 0 || commitCount <= 0) {
      return 0;
    }
    return addedLines / commitCount;
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
            "加载合并请求数据",
            "加载已同步到平台的合并请求数据，并使用整理后的责任人、模块、目标分支和代码走查指标。",
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

  private StatisticRuleFlowStepSample toIllegalRecordSample(CodeReviewIllegalRecordView row) {
    return new StatisticRuleFlowStepSample(
        "MR #" + row.mergeRequestIid(),
        row.projectName() + " | "
            + (row.illegalTypes().isEmpty() ? "无非法类型" : String.join("、", row.illegalTypes())));
  }

  private List<StatisticRuleFlowStepSample> sampleIllegalRecords(List<CodeReviewIllegalRecordView> rows) {
    return rows.stream().limit(3).map(this::toIllegalRecordSample).toList();
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
        row.sourceInstance(),
        row.mergeRequestId(),
        row.mergeRequestIid(),
        row.projectId(),
        row.mergeRequestContent(),
        link,
        row.owner(),
        row.projectName(),
        row.repositoryName(),
        row.mergedAt(),
        row.author(),
        row.mergedBy(),
        row.moduleName(),
        row.targetBranch(),
        illegalTypes,
        row.reviewerNames(),
        row.assigneeNames(),
        row.reviewStatus(),
        row.reviewDurationMinutes(),
        row.reviewExceptionReason(),
        row.codeWalkthroughDate(),
        row.scanStatus(),
        row.scanBugCount(),
        row.annotationRateResult(),
        row.bugCountResult(),
        row.commentRate(),
        row.defectCount(),
        row.addedLines(),
        row.deletedLines(),
        row.codeSpecificationCount(),
        row.codeLogicSpecificationCount(),
        row.performanceSpecificationCount(),
        row.designSpecificationCount(),
        row.otherSpecificationCount(),
        row.reviewSpeedLocPerHour(),
        row.reviewSpeedKlocPerHour(),
        row.defectDensityPerKloc(),
        row.reviewEfficiencyPerHour(),
        row.commitCount(),
        row.commitRate(),
        row.functionName(),
        row.clangAddedLineCount());
  }

  private List<OptionItemResponse> toOptions(
      List<CodeReviewIllegalRecordView> rows,
      Function<CodeReviewIllegalRecordView, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  private List<OptionItemResponse> toLegacyOptions(
      List<CodeReviewIllegalRecordView> rows,
      Function<CodeReviewIllegalRecordView, String> extractor) {
    return OptionItemResponseFactory.fromLegacyBusinessValues(rows.stream().map(extractor).toList());
  }

  private List<OptionItemResponse> toLegacyOptions(List<String> values) {
    return OptionItemResponseFactory.fromLegacyBusinessValues(values);
  }

  private List<OptionItemResponse> toCodeReviewProjectNameOptions(
      boolean matchMode,
      String source,
      List<String> values) {
    List<String> rawValues = new ArrayList<>(values == null ? List.of() : values);
    if ("dgm".equalsIgnoreCase(TextQuerySupport.trimToNull(source))) {
      //兼容模式-MatchMode：DGM 项目名称候选不依赖当前兼容表/正式事实表的数据量，
      //而是额外合并系统设置中维护的 GitLab API 本地缓存，确保交接期和非兼容模式候选一致。
      rawValues.addAll(dgmProjectOptionService.listProjectNames());
    }
    if (matchMode && isMatchModeCcSource(source)) {
      //兼容模式-MatchMode：老平台只在 CC/CrownCAD 项目下拉补充这四项；
      //DGM 与非兼容事实表不得继承此兜底，便于后续删除 Match mode 时整体移除。
      rawValues.addAll(LEGACY_EXTRA_PROJECT_NAME_OPTIONS);
    }
    if (!matchMode) {
      // 非兼容模式继续使用正式事实表的统一候选清洗规则，不继承老平台“无需标注”等特殊口径。
      return OptionItemResponseFactory.fromLegacyBusinessValues(rawValues);
    }
    List<String> visibleValues = new ArrayList<>();
    for (String rawValue : rawValues) {
      String value = TextQuerySupport.trimToNull(rawValue);
      if (value == null || "未标注项目名".equals(value)) {
        continue;
      }
      for (String part : value.split("\\s+&\\s+")) {
        String candidate = TextQuerySupport.trimToNull(part);
        if (candidate != null && !candidate.startsWith("未设定")) {
          // 老平台明确保留“无需标注”；这里只排除其项目下拉实际排除的占位值。
          visibleValues.add(candidate);
        }
      }
    }
    return OptionItemResponseFactory.fromValuesPreservingOrder(visibleValues, TextQuerySupport::trimToNull);
  }

  //兼容模式-MatchMode
  private boolean isMatchModeCcSource(String source) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(
        TextQuerySupport.trimToNull(source) == null ? LEGACY_DEFAULT_SOURCE : source);
    return LEGACY_DEFAULT_SOURCE.equals(normalizedSource);
  }

  private List<OptionItemResponse> toCodeReviewRepositoryNameOptions(boolean matchMode, List<String> values) {
    List<String> visibleValues = new ArrayList<>(
        values.stream().filter(repositoryName -> !isHiddenCodeReviewProjectName(repositoryName)).toList());
    if (matchMode) {
      //兼容模式-MatchMode：仅老平台兼容读路径补充 CrownCAD 默认仓库；
      //非兼容模式必须只展示正式事实表中的仓库，避免兼容默认值污染新平台数据源。
      visibleValues.add(0, LEGACY_DEFAULT_REPOSITORY_NAME);
    }
    return OptionItemResponseFactory.fromLegacyBusinessValues(visibleValues);
  }

  private boolean isHiddenCodeReviewProjectName(String projectName) {
    String normalized = TextQuerySupport.trimToNull(projectName);
    return normalized != null && "CC".equalsIgnoreCase(normalized);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }

  private static class ExportStyles {
    private final CellStyle header;
    private final CellStyle body;

    private ExportStyles(Workbook workbook) {
      header = ExcelExportStyles.createHeaderStyle(workbook);
      body = ExcelExportStyles.createBodyStyle(workbook);
    }
  }
}
