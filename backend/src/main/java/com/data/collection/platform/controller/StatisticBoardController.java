package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardControlOptions;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.service.statistics.RuleExplainableStatisticBoardSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePagePermission;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.statistics.RealtimeStatisticBoardSupport;
import com.data.collection.platform.service.statistics.StatisticBoardRegistry;
import com.data.collection.platform.service.statistics.StatisticBoardIssueWorkbookExportSupport;
import com.data.collection.platform.service.statistics.StatisticBoardWorkbookExportSupport;
import com.data.collection.platform.service.statistics.SystemTestHorizontalComparisonExportService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/statistic-boards")
// 统计板控制器通过 boardKey 路由到注册表中的具体看板服务。
// 看板定义、明细下钻、实时刷新和规则说明都由服务能力声明，控制器只做统一 HTTP 外壳。
public class StatisticBoardController {
  private static final String SYSTEM_TEST_DEFECT_SUMMARY_BOARD_KEY = "system-test-defect-summary";
  private static final String SYSTEM_TEST_PHASE_STATISTICS_BOARD_KEY = "system-test-phase-statistics";
  private final StatisticBoardRegistry registry;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final SystemTestHorizontalComparisonExportService systemTestHorizontalComparisonExportService;
  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;
  public StatisticBoardController(
      StatisticBoardRegistry registry,
      RealtimeWorkspaceService realtimeWorkspaceService,
      SystemTestHorizontalComparisonExportService systemTestHorizontalComparisonExportService,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.registry = registry;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.systemTestHorizontalComparisonExportService = systemTestHorizontalComparisonExportService;
    this.pageReads = pageReads;
  }

  /** 每个统计页只接受其拥有者的完整身份；通用镜像归档与三类事实不能混用证明。 */
  private <T> com.data.collection.platform.service.read.ReadViewApplicationService.ReadOutcome<T> readBoard(
      jakarta.servlet.http.HttpServletRequest request, String boardKey, Map<String, String> filters,
      java.util.function.Function<com.data.collection.platform.service.statistics.AbstractStatisticBoardService, T> reader) {
    var factory = registry.getRequired(boardKey);
    if (factory instanceof com.data.collection.platform.service.statistics.MirrorTableOverviewBoardService mirror) {
      return pageReads.read(request, boardKey, filters, selection -> reader.apply(mirror.forRead(
          (com.data.collection.platform.entity.read.DatasetReadSelection) selection)));
    }
    return pageReads.readFacts(request, boardKey, filters, selection -> reader.apply(factory.forRead(selection)));
  }

  @GetMapping("/{boardKey}")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<StatisticBoardResponse> getBoard(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest request) {
    return readBoard(request, boardKey, filters,
        service -> service.loadBoard(pageReads.businessParameters(filters))).response();
  }

  /**
   * 成员控制条候选：由同一事实源在该板的完整基础范围上求得，不随当前成员选择收缩。
   *
   * @param boardKey 统计板标识
   * @param filters 页面范围参数（里程碑与来源参与范围判定，成员参与筛选但不参与候选求解）
   */
  @GetMapping("/{boardKey}/control-options")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<StatisticBoardControlOptions> getControlOptions(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest request) {
    return readBoard(request, boardKey, filters,
        service -> service.controlOptions(pageReads.businessParameters(filters))).response();
  }

  @GetMapping("/{boardKey}/details")  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<StatisticDetailResponse> getDetails(
      @PathVariable @NotBlank String boardKey,
      @RequestParam String rowKey,
      @RequestParam String columnKey,
      @RequestParam(defaultValue = "1") @Positive int page,
      @RequestParam(defaultValue = "10") @Positive int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(required = false) String sortOrder,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest request) {
    return readBoard(request, boardKey, filters,
        service -> service.loadDetail(
            new StatisticDetailRequest(boardKey, rowKey, columnKey, page, size, sortField, sortOrder,
                pageReads.businessParameters(filters)))).response();
  }

  @GetMapping("/{boardKey}/rule-explanation")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<StatisticBoardRuleExplanationResponse> getRuleExplanation(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest request) {
    return readBoard(request, boardKey, filters, service -> {
    if (service instanceof RuleExplainableStatisticBoardSupport ruleExplainable) {
      return ruleExplainable.getRuleExplanation(pageReads.businessParameters(filters));
    }
    return new StatisticBoardRuleExplanationResponse(
            boardKey,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            java.util.List.of(),
            "当前统计表暂不支持规则说明");
    }).response();
  }

  @GetMapping("/{boardKey}/export")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.EXPORT)
  public ResponseEntity<?> exportBoard(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = readBoard(httpRequest, boardKey, filters, service -> {
    if (SYSTEM_TEST_PHASE_STATISTICS_BOARD_KEY.equals(boardKey)) {
      throw new IllegalArgumentException("当前统计表不支持导出: " + boardKey);
    }
    if (service instanceof StatisticBoardWorkbookExportSupport workbookExportSupport) {
      byte[] workbook = workbookExportSupport.exportBoardWorkbook(pageReads.businessParameters(filters));
      String filename = workbookExportSupport.exportFilename(pageReads.businessParameters(filters));
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
          .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(filename))
          .body(workbook);
    }
    String csv = service.exportBoardCsv(pageReads.businessParameters(filters));
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + boardKey + ".csv\"")
        .body(csv);
      });
    return exported.download(exported.data());
  }

  @GetMapping("/{boardKey}/horizontal-comparison/export")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.EXPORT)
  public ResponseEntity<byte[]> exportHorizontalComparison(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest request) {
    registry.getRequired(boardKey);
    if (!SYSTEM_TEST_DEFECT_SUMMARY_BOARD_KEY.equals(boardKey)) {
      throw new IllegalArgumentException("当前统计表不支持横向对比导出: " + boardKey);
    }
    var outcome = pageReads.read(request, boardKey, pageReads.parameters(request), selected ->
        {
          if (!(selected instanceof com.data.collection.platform.entity.read.DatasetReadSelection dataset)) {
            throw com.data.collection.platform.service.read.ReadViewException.unavailable("横向对比的扩展来源尚无完整产出，请完成对应来源初始化");
          }
          return systemTestHorizontalComparisonExportService.forRead(dataset)
              .exportWorkbook(pageReads.businessParameters(filters));
        });
    byte[] workbook = outcome.data();
    return outcome.download(ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            DownloadResponseHeaders.attachment(systemTestHorizontalComparisonExportService.exportFilename(filters)))
        .body(workbook));
  }

  @GetMapping("/{boardKey}/issues/export")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.ISSUE_EXPORT)
  public ResponseEntity<byte[]> exportBoardIssues(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.readFacts(httpRequest, boardKey, filters, selection -> {
    var service = registry.getRequired(boardKey).forRead(selection);
    if (!(service instanceof StatisticBoardIssueWorkbookExportSupport issueWorkbookExportSupport)) {
      throw new IllegalArgumentException("当前统计表不支持议题数据导出: " + boardKey);
    }
    byte[] workbook = issueWorkbookExportSupport.exportIssueRecordsWorkbook(pageReads.businessParameters(filters));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            DownloadResponseHeaders.attachment(issueWorkbookExportSupport.exportIssueRecordsFilename(pageReads.businessParameters(filters))))
        .body(workbook);
      });
    return exported.download(exported.data());
  }

  @GetMapping("/{boardKey}/status")
  @RequirePagePermission(resource = RequirePagePermission.Resource.STATISTIC_BOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<RealtimeWorkspaceStatusResponse> getBoardRealtimeStatus(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters) {
    var service = registry.getRequired(boardKey);
    if (service instanceof RealtimeStatisticBoardSupport realtimeSupport) {
      return ApiResponse.success(realtimeSupport.getRealtimeStatus(filters));
    }
    return ApiResponse.success(realtimeWorkspaceService.getStatus(boardKey, filters));
  }

  @PostMapping("/{boardKey}/refresh")
  @RequirePermission(PlatformPermissionCodes.BUSINESS_DATA_REFRESH)
  public ApiResponse<RealtimeWorkspaceStatusResponse> refreshBoardRealtimeData(
      @PathVariable @NotBlank String boardKey) {
    var service = registry.getRequired(boardKey);
    if (service instanceof RealtimeStatisticBoardSupport realtimeSupport) {
      return ApiResponse.success("已开始刷新最新数据", realtimeSupport.requestRealtimeRefresh());
    }
    return ApiResponse.success(
        "当前统计表暂不支持实时刷新",
        new RealtimeWorkspaceStatusResponse(
            boardKey,
            false,
            "UNSUPPORTED",
            "当前统计表暂不支持实时刷新",
            false,
            null,
            null,
            null));
  }
}
