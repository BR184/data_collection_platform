package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardDetailResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardResponse;
import com.data.collection.platform.entity.analytics.AnalyticsDashboardRulesResponse;
import com.data.collection.platform.service.analytics.AnalyticsDashboardQueryContextResolver;
import com.data.collection.platform.service.analytics.AnalyticsDashboardRegistry;
import com.data.collection.platform.security.RequirePagePermission;
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
  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;

  public AnalyticsDashboardController(
      AnalyticsDashboardRegistry registry,
      AnalyticsDashboardQueryContextResolver contextResolver,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.pageReads = pageReads;
    this.registry = registry;
    this.contextResolver = contextResolver;
  }

  @GetMapping("/{dashboardKey}")
  @RequirePagePermission(resource = RequirePagePermission.Resource.ANALYTICS_DASHBOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<AnalyticsDashboardResponse> getDashboard(
      @PathVariable @NotBlank String dashboardKey,
      @RequestParam Map<String, String> parameters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, dashboardKey, parameters, selection -> {
    var scopedRegistry = registry.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var scopedResolver = contextResolver.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var provider = scopedRegistry.getRequired(dashboardKey);
    return scopedRegistry.loadDashboard(dashboardKey, scopedResolver.resolveDashboard(provider, pageReads.businessParameters(parameters)));
      }).response();
  }

  @GetMapping("/{dashboardKey}/rules")
  @RequirePagePermission(resource = RequirePagePermission.Resource.ANALYTICS_DASHBOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<AnalyticsDashboardRulesResponse> getRules(
      @PathVariable @NotBlank String dashboardKey,
      @RequestParam Map<String, String> parameters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, dashboardKey, parameters, selection -> {
    var scopedRegistry = registry.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var scopedResolver = contextResolver.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var provider = scopedRegistry.getRequired(dashboardKey);
    return scopedRegistry.loadRules(dashboardKey, scopedResolver.resolveDashboard(provider, pageReads.businessParameters(parameters)));
      }).response();
  }

  @GetMapping("/{dashboardKey}/details/{viewKey}")
  @RequirePagePermission(resource = RequirePagePermission.Resource.ANALYTICS_DASHBOARD, action = RequirePagePermission.Action.VIEW)
  public ApiResponse<AnalyticsDashboardDetailResponse> getDetails(
      @PathVariable @NotBlank String dashboardKey,
      @PathVariable @NotBlank String viewKey,
      @RequestParam Map<String, String> parameters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, dashboardKey, parameters, selection -> {
    var scopedRegistry = registry.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var scopedResolver = contextResolver.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var provider = scopedRegistry.getRequired(dashboardKey);
    return scopedRegistry.loadDetail(
        dashboardKey,
        viewKey,
        scopedResolver.resolveDetails(provider, viewKey, pageReads.businessParameters(parameters)));
      }).response();
  }

  @GetMapping("/{dashboardKey}/exports/{exportKey}")
  @RequirePagePermission(resource = RequirePagePermission.Resource.ANALYTICS_DASHBOARD, action = RequirePagePermission.Action.EXPORT)
  public ResponseEntity<byte[]> export(
      @PathVariable @NotBlank String dashboardKey,
      @PathVariable @NotBlank String exportKey,
      @RequestParam Map<String, String> parameters, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var prepared = pageReads.read(httpRequest, dashboardKey, parameters, selection -> {
    var scopedRegistry = registry.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var scopedResolver = contextResolver.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection);
    var provider = scopedRegistry.getRequired(dashboardKey);
    var export = scopedRegistry.export(
        dashboardKey,
        exportKey,
        scopedResolver.resolveExport(provider, exportKey, pageReads.businessParameters(parameters)));
    return export;
    });
    return prepared.download(ResponseEntity.ok()
        .contentType(prepared.data().mediaType())
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(prepared.data().filename()))
        .body(prepared.data().content()));
  }
}
