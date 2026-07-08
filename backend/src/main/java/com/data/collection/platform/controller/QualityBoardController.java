package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.QualityBoardProjectOptionsResponse;
import com.data.collection.platform.entity.QualityBoardRdOverviewResponse;
import com.data.collection.platform.service.QualityBoardRdService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quality-board")
public class QualityBoardController {
  private final QualityBoardRdService qualityBoardRdService;

  public QualityBoardController(QualityBoardRdService qualityBoardRdService) {
    this.qualityBoardRdService = qualityBoardRdService;
  }

  @GetMapping("/rd/project-options")
  public ApiResponse<QualityBoardProjectOptionsResponse> listRdProjectOptions() {
    return ApiResponse.success(qualityBoardRdService.listProjectOptions());
  }

  @GetMapping("/rd/overview")
  public ApiResponse<QualityBoardRdOverviewResponse> getRdOverview(
      @RequestParam(required = false) String projectName) {
    return ApiResponse.success(qualityBoardRdService.getOverview(projectName));
  }
}
