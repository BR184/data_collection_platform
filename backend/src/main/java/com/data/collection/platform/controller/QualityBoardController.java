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
import com.data.collection.platform.security.RequirePermission;
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

  private final com.data.collection.platform.service.read.ReadViewApplicationService pageReads;

  public QualityBoardController(
      QualityBoardRdService qualityBoardRdService,
      QualityBoardWorkbookExportService workbookExportService,
      com.data.collection.platform.service.read.ReadViewApplicationService pageReads) {
    this.pageReads = pageReads;
    this.qualityBoardRdService = qualityBoardRdService;
    this.workbookExportService = workbookExportService;
  }

  @GetMapping("/rd/project-options")
  @RequirePermission("quality.rd.view")
  public ApiResponse<QualityBoardProjectOptionsResponse> listRdProjectOptions(jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).listProjectOptions()).response();
  }

  /** 其它质量页候选使用该页的完整读取身份及权限，不能借用研发质量页身份。 */
  @GetMapping("/other/project-options")
  @RequirePermission("quality.other.view")
  public ApiResponse<QualityBoardProjectOptionsResponse> listOtherProjectOptions(
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-board-other", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection)
            .listProjectOptions()).response();
  }

  @GetMapping("/rd/filter-options")
  @RequirePermission("quality.rd.view")
  public ApiResponse<QualityBoardRdFilterOptionsResponse> listRdFilterOptions(jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).listFilterOptions()).response();
  }

  @GetMapping("/rd/overview")
  @RequirePermission("quality.rd.view")
  public ApiResponse<QualityBoardRdOverviewResponse> getRdOverview(
      @RequestParam(required = false) String projectName, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).getOverview(projectName)).response();
  }

  @GetMapping("/rd/dashboard")
  @RequirePermission("quality.rd.view")
  public ApiResponse<QualityBoardRdDashboardResponse> getRdDashboard(
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).getRdDashboard(projectName, codeReviewSource)).response();
  }

  @GetMapping("/rd/charts/{chartKey}/export")
  @RequirePermission("quality.rd.export")
  public ResponseEntity<byte[]> exportRdChart(
      @PathVariable String chartKey,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> workbookExportService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).exportRdChartWorkbook(projectName, codeReviewSource, chartKey));
    byte[] workbook = exported.data();
    return exported.download(excelResponse(workbook, workbookExportService.rdChartFilename(projectName, chartKey)));
  }

  @GetMapping("/rd/code-review-records/export")
  @RequirePermission("quality.rd.export")
  public ResponseEntity<byte[]> exportRdCodeReviewRecords(
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String codeReviewSource, jakarta.servlet.http.HttpServletRequest httpRequest) {
    var exported = pageReads.read(httpRequest, "quality-rd", pageReads.parameters(httpRequest),
        selection -> workbookExportService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).exportCodeReviewRecordsWorkbook(projectName, codeReviewSource));
    byte[] workbook = exported.data();
    return exported.download(excelResponse(
        workbook, workbookExportService.codeReviewRecordsFilename(projectName, codeReviewSource)));
  }

  @GetMapping("/other/overview")
  @RequirePermission("quality.other.view")
  public ApiResponse<QualityBoardOtherOverviewResponse> getOtherOverview(
      @RequestParam(required = false) String projectName, jakarta.servlet.http.HttpServletRequest httpRequest) {
    return pageReads.read(httpRequest, "quality-board-other", pageReads.parameters(httpRequest),
        selection -> qualityBoardRdService.forRead((com.data.collection.platform.entity.read.DatasetReadSelection) selection).getOtherOverview(projectName)).response();
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
