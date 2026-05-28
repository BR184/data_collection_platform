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
import com.data.collection.platform.service.IntegrationTestExcelExportService;
import com.data.collection.platform.service.IntegrationTestQueryService;
import java.net.URLEncoder;
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
  private final IntegrationTestExcelExportService integrationTestExcelExportService;
  private final FactBuildOperationGuard factBuildOperationGuard;

  public IntegrationTestController(
      IntegrationTestFactBuildService integrationTestFactBuildService,
      IntegrationTestQueryService integrationTestQueryService,
      IntegrationTestExcelExportService integrationTestExcelExportService,
      FactBuildOperationGuard factBuildOperationGuard) {
    this.integrationTestFactBuildService = integrationTestFactBuildService;
    this.integrationTestQueryService = integrationTestQueryService;
    this.integrationTestExcelExportService = integrationTestExcelExportService;
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
  public ApiResponse<List<IntegrationTestProjectOptionResponse>> listProjectOptions(
      @RequestParam(required = false) String sourceInstance) {
    return ApiResponse.success(integrationTestQueryService.listProjectOptions(sourceInstance));
  }

  @GetMapping("/phase-options")
  public ApiResponse<List<IntegrationTestPhaseOptionResponse>> listPhaseOptions(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String sourceInstance) {
    return ApiResponse.success(integrationTestQueryService.listPhaseOptions(projectId, sourceInstance));
  }

  @GetMapping("/summary")
  public ApiResponse<IntegrationTestSummaryResponse> getSummary(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String sourceInstance) {
    return ApiResponse.success(integrationTestQueryService.getSummary(projectId, testingPhase, sourceInstance));
  }

  @GetMapping("/details")
  public ApiResponse<IntegrationTestDetailResponse> getDetails(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String moduleName,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "noteUpdatedAt") String sortField,
      @RequestParam(defaultValue = "desc") String sortOrder,
      @RequestParam(required = false) String sourceInstance) {
    return ApiResponse.success(
        integrationTestQueryService.getDetails(
            projectId, testingPhase, moduleName, page, size, sortField, sortOrder, sourceInstance));
  }

  @GetMapping("/details/export")
  public ResponseEntity<String> exportDetails(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String moduleName,
      @RequestParam(defaultValue = "noteUpdatedAt") String sortField,
      @RequestParam(defaultValue = "desc") String sortOrder,
      @RequestParam(required = false) String sourceInstance) {
    String csv =
        integrationTestQueryService.exportDetailsCsv(
            projectId, testingPhase, moduleName, sortField, sortOrder, sourceInstance);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"integration-test-details.csv\"")
        .body(csv);
  }

  @GetMapping("/module-function/export")
  public ResponseEntity<byte[]> exportModuleFunctionWorkbook(
      @RequestParam(required = false) Long projectId,
      @RequestParam(required = false) String testingPhase,
      @RequestParam(required = false) String sourceInstance) {
    byte[] workbook =
        integrationTestExcelExportService.exportModuleFunctionWorkbook(projectId, testingPhase, sourceInstance);
    return excelResponse(workbook, safeFilePart(testingPhase, "集成测试") + "集成测试数据.xlsx");
  }

  @GetMapping("/comparison/export")
  public ResponseEntity<byte[]> exportComparisonWorkbook(
      @RequestParam(required = false) Long projectId,
      @RequestParam String basePhase,
      @RequestParam String targetPhase,
      @RequestParam(required = false) String sourceInstance) {
    byte[] workbook =
        integrationTestExcelExportService.exportComparisonWorkbook(projectId, basePhase, targetPhase, sourceInstance);
    return excelResponse(
        workbook,
        safeFilePart(basePhase, "前阶段")
            + "-"
            + safeFilePart(targetPhase, "后阶段")
            + "集成测试横向对比.xlsx");
  }

  private ResponseEntity<byte[]> excelResponse(byte[] workbook, String filename) {
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
        .body(workbook);
  }

  private String contentDisposition(String filename) {
    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"integration-test.xlsx\"; filename*=UTF-8''" + encoded;
  }

  private String safeFilePart(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.replaceAll("[\\\\/:*?\"<>|]", "-");
  }
}
