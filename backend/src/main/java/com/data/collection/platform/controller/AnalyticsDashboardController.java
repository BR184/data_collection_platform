package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.analytics.AnalyticsDashboardQueryContextResolver;
import com.data.collection.platform.service.analytics.AnalyticsDashboardRegistry;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/analytics-dashboards")
public class AnalyticsDashboardController {
  private final AnalyticsDashboardRegistry registry;
  private final AnalyticsDashboardQueryContextResolver contextResolver;

  public AnalyticsDashboardController(
      AnalyticsDashboardRegistry registry,
      AnalyticsDashboardQueryContextResolver contextResolver) {
    this.registry = registry;
    this.contextResolver = contextResolver;
  }

  @GetMapping("/{dashboardKey}")
  public ApiResponse<AnalyticsDashboardResponse> getDashboard(
      @PathVariable @NotBlank String dashboardKey,
      @RequestParam Map<String, String> parameters) {
    var provider = registry.getRequired(dashboardKey);
    return ApiResponse.success(
        registry.loadDashboard(dashboardKey, contextResolver.resolveDashboard(provider, parameters)));
  }

  @GetMapping("/{dashboardKey}/rules")
  public ApiResponse<AnalyticsDashboardRulesResponse> getRules(
      @PathVariable @NotBlank String dashboardKey,
      @RequestParam Map<String, String> parameters) {
    var provider = registry.getRequired(dashboardKey);
    return ApiResponse.success(
        registry.loadRules(dashboardKey, contextResolver.resolveDashboard(provider, parameters)));
  }

  @GetMapping("/{dashboardKey}/details/{viewKey}")
  public ApiResponse<AnalyticsDashboardDetailResponse> getDetails(
      @PathVariable @NotBlank String dashboardKey,
      @PathVariable @NotBlank String viewKey,
      @RequestParam Map<String, String> parameters) {
    var provider = registry.getRequired(dashboardKey);
    return ApiResponse.success(registry.loadDetail(
        dashboardKey,
        viewKey,
        contextResolver.resolveDetails(provider, viewKey, parameters)));
  }

  @GetMapping("/{dashboardKey}/exports/{exportKey}")
  public ResponseEntity<byte[]> export(
      @PathVariable @NotBlank String dashboardKey,
      @PathVariable @NotBlank String exportKey,
      @RequestParam Map<String, String> parameters) {
    var provider = registry.getRequired(dashboardKey);
    var export = registry.export(
        dashboardKey,
        exportKey,
        contextResolver.resolveExport(provider, exportKey, parameters));
    return ResponseEntity.ok()
        .contentType(export.mediaType())
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(export.filename()))
        .body(export.content());
  }
}
