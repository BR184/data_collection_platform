package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.data.collection.platform.entity.statistics.StatisticDetailRequest;
import com.data.collection.platform.entity.statistics.StatisticDetailResponse;
import com.data.collection.platform.service.statistics.RuleExplainableStatisticBoardSupport;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.statistics.RealtimeStatisticBoardSupport;
import com.data.collection.platform.service.statistics.StatisticBoardRegistry;
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
  private final StatisticBoardRegistry registry;
  private final RealtimeWorkspaceService realtimeWorkspaceService;

  public StatisticBoardController(
      StatisticBoardRegistry registry,
      RealtimeWorkspaceService realtimeWorkspaceService) {
    this.registry = registry;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
  }

  @GetMapping("/{boardKey}")
  public ApiResponse<StatisticBoardResponse> getBoard(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters) {
    return ApiResponse.success(registry.getRequired(boardKey).loadBoard(filters));
  }

  @GetMapping("/{boardKey}/details")
  public ApiResponse<StatisticDetailResponse> getDetails(
      @PathVariable @NotBlank String boardKey,
      @RequestParam String rowKey,
      @RequestParam String columnKey,
      @RequestParam(defaultValue = "1") @Positive int page,
      @RequestParam(defaultValue = "10") @Positive int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(required = false) String sortOrder,
      @RequestParam Map<String, String> filters) {
    return ApiResponse.success(
        registry.getRequired(boardKey).loadDetail(
            new StatisticDetailRequest(boardKey, rowKey, columnKey, page, size, sortField, sortOrder, filters)));
  }

  @GetMapping("/{boardKey}/rule-explanation")
  public ApiResponse<StatisticBoardRuleExplanationResponse> getRuleExplanation(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters) {
    var service = registry.getRequired(boardKey);
    if (service instanceof RuleExplainableStatisticBoardSupport ruleExplainable) {
      return ApiResponse.success(ruleExplainable.getRuleExplanation(filters));
    }
    return ApiResponse.success(
        new StatisticBoardRuleExplanationResponse(
            boardKey,
            false,
            null,
            null,
            null,
            null,
            java.util.List.of(),
            java.util.List.of(),
            "当前统计表暂不支持规则说明"));
  }

  @GetMapping("/{boardKey}/export")
  public ResponseEntity<String> exportBoard(
      @PathVariable @NotBlank String boardKey,
      @RequestParam Map<String, String> filters) {
    String csv = registry.getRequired(boardKey).exportBoardCsv(filters);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + boardKey + ".csv\"")
        .body(csv);
  }

  @GetMapping("/{boardKey}/status")
  public ApiResponse<RealtimeWorkspaceStatusResponse> getBoardRealtimeStatus(
      @PathVariable @NotBlank String boardKey) {
    var service = registry.getRequired(boardKey);
    if (service instanceof RealtimeStatisticBoardSupport realtimeSupport) {
      return ApiResponse.success(realtimeSupport.getRealtimeStatus());
    }
    return ApiResponse.success(realtimeWorkspaceService.getStatus(boardKey));
  }

  @PostMapping("/{boardKey}/refresh")
  @RequireRole(AuthRole.ADMIN)
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
