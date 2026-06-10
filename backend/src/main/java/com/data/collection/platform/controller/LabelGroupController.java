package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.labelgroup.LabelDimensionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.service.labelgroup.LabelDimensionCatalogService;
import com.data.collection.platform.service.labelgroup.LabelValueQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/label-groups")
public class LabelGroupController {
  private final LabelDimensionCatalogService labelDimensionCatalogService;
  private final LabelValueQueryService labelValueQueryService;

  public LabelGroupController(
      LabelDimensionCatalogService labelDimensionCatalogService,
      LabelValueQueryService labelValueQueryService) {
    this.labelDimensionCatalogService = labelDimensionCatalogService;
    this.labelValueQueryService = labelValueQueryService;
  }

  @GetMapping("/dimensions")
  public ApiResponse<List<LabelDimensionResponse>> listDimensions() {
    return ApiResponse.success(
        labelDimensionCatalogService.listDimensions().stream()
            .map(LabelDimensionResponse::from)
            .toList());
  }

  @GetMapping("/dimensions/{dimensionKey}/values")
  public ApiResponse<LabelValuePageResponse> listValues(
      @PathVariable String dimensionKey,
      @RequestParam(required = false) String pageKey,
      @RequestParam(required = false) String sourceInstanceId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(
        labelValueQueryService.listValues(dimensionKey, pageKey, sourceInstanceId, keyword, page, size));
  }

  @GetMapping("/dimensions/{dimensionKey}/compatible-pages")
  public ApiResponse<List<LabelGroupCompatiblePageResponse>> listCompatiblePages(
      @PathVariable String dimensionKey) {
    return ApiResponse.success(labelDimensionCatalogService.listCompatiblePages(dimensionKey));
  }
}
