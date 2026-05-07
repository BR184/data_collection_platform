package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.IntegrationTestDetailResponse;
import com.data.collection.platform.entity.IntegrationTestPhaseOptionResponse;
import com.data.collection.platform.entity.IntegrationTestProjectOptionResponse;
import com.data.collection.platform.entity.IntegrationTestSummaryResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.FactBuildOperationGuard;
import com.data.collection.platform.service.IntegrationTestFactBuildService;
import com.data.collection.platform.service.IntegrationTestQueryService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration-tests")
// 集成测试控制器暴露事实重建、范围选项、汇总、明细和导出接口。
// 备注解析和事实聚合不在控制层展开，避免接口层耦合集成测试口径细节。
public class IntegrationTestController {
  private final IntegrationTestFactBuildService integrationTestFactBuildService;
  private final IntegrationTestQueryService integrationTestQueryService;
  private final FactBuildOperationGuard factBuildOperationGuard;

  public IntegrationTestController(
      IntegrationTestFactBuildService integrationTestFactBuildService,
      IntegrationTestQueryService integrationTestQueryService,
      FactBuildOperationGuard factBuildOperationGuard) {
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.integrationTestQueryService = integrationTestQueryService;
    this.factBuildOperationGuard = factBuildOperationGuard;
  }

  @PostMapping("/rebuild")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<FactBuildResponse> rebuild(
      @RequestParam(defaultValue = "false") boolean full,
      @RequestParam(required = false) Long configId) {
    FactBuildResponse response =
        factBuildOperationGuard.run(
            guardScope(configId),
            () -> configId == null
                ? integrationTestFactBuildService.rebuildFacts(full)
                : integrationTestFactBuildService.rebuildFacts(full, configId));
    return ApiResponse.success(response.message(), response);
  }

  private String guardScope(Long configId) {
    return configId == null ? "integration-test" : "integration-test:config-" + configId;
  }

  @GetMapping("/project-options")
  public ApiResponse<List<IntegrationTestProjectOptionResponse>> listProjectOptions() {
    return ApiResponse.success(integrationTestQueryService.listProjectOptions());
  }

  @GetMapping("/phase-options")
  public ApiResponse<List<IntegrationTestPhaseOptionResponse>> listPhaseOptions(
      @RequestParam(required = false) Long projectId) {
    return ApiResponse.success(integrationTestQueryService.listPhaseOptions(projectId));
  }

  @GetMapping("/summary")
  public ApiResponse<IntegrationTestSummaryResponse> getSummary(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase) {
    return ApiResponse.success(integrationTestQueryService.getSummary(projectId, testingPhase));
  }

  @GetMapping("/details")
  public ApiResponse<IntegrationTestDetailResponse> getDetails(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String moduleName,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "noteUpdatedAt") String sortField,
      @RequestParam(defaultValue = "desc") String sortOrder) {
    return ApiResponse.success(
        integrationTestQueryService.getDetails(
            projectId, testingPhase, moduleName, page, size, sortField, sortOrder));
  }

  @GetMapping("/details/export")
  public ResponseEntity<String> exportDetails(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String moduleName,
      @RequestParam(defaultValue = "noteUpdatedAt") String sortField,
      @RequestParam(defaultValue = "desc") String sortOrder) {
    String csv =
        integrationTestQueryService.exportDetailsCsv(
            projectId, testingPhase, moduleName, sortField, sortOrder);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"integration-test-details.csv\"")
        .body(csv);
  }
}
