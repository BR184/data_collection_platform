package com.data.collection.platform.controller;

import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.QualityBoardOtherOverviewResponse;
import com.data.collection.platform.entity.QualityBoardProjectOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdDashboardResponse;
import com.data.collection.platform.entity.QualityBoardRdFilterOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.service.QualityBoardWorkbookExportService;
import com.data.collection.platform.service.QualityBoardRdService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quality-board")
public class QualityBoardController {
  private final QualityBoardRdService qualityBoardRdService;
  private final QualityBoardWorkbookExportService workbookExportService;

  public QualityBoardController(
      QualityBoardRdService qualityBoardRdService,
      QualityBoardWorkbookExportService workbookExportService) {
    this.qualityBoardRdService = qualityBoardRdService;
    this.workbookExportService = workbookExportService;
  }

  @GetMapping("/rd/project-options")
  public ApiResponse<QualityBoardProjectOptionsResponse> listRdProjectOptions() {
    return ApiResponse.success(qualityBoardRdService.listProjectOptions());
  }

  @GetMapping("/rd/filter-options")
  public ApiResponse<QualityBoardRdFilterOptionsResponse> listRdFilterOptions() {
    return ApiResponse.success(qualityBoardRdService.listFilterOptions());
  }

  @GetMapping("/rd/overview")
  public ApiResponse<QualityBoardRdOverviewResponse> getRdOverview(
      @RequestParam(required = false) String projectName) {
    return ApiResponse.success(qualityBoardRdService.getOverview(projectName));
  }

  @GetMapping("/rd/dashboard")
  public ApiResponse<QualityBoardRdDashboardResponse> getRdDashboard(
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource) {
    return ApiResponse.success(
        qualityBoardRdService.getRdDashboard(projectName, codeReviewSource));
  }

  @GetMapping("/rd/charts/{chartKey}/export")
  public ResponseEntity<byte[]> exportRdChart(
      @PathVariable String chartKey,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource) {
    byte[] workbook =
        workbookExportService.exportRdChartWorkbook(projectName, codeReviewSource, chartKey);
    return excelResponse(workbook, workbookExportService.rdChartFilename(projectName, chartKey));
  }

  @GetMapping("/rd/code-review-records/export")
  public ResponseEntity<byte[]> exportRdCodeReviewRecords(
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource) {
    byte[] workbook =
        workbookExportService.exportCodeReviewRecordsWorkbook(projectName, codeReviewSource);
    return excelResponse(
        workbook, workbookExportService.codeReviewRecordsFilename(projectName, codeReviewSource));
  }

  @GetMapping("/other/overview")
  public ApiResponse<QualityBoardOtherOverviewResponse> getOtherOverview(
      @RequestParam(required = false) String projectName) {
    return ApiResponse.success(qualityBoardRdService.getOtherOverview(projectName));
  }

  private ResponseEntity<byte[]> excelResponse(byte[] workbook, String filename) {
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, DownloadResponseHeaders.attachment(filename))
        .body(workbook);
  }
}
