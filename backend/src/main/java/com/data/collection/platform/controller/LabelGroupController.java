package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.labelgroup.LabelDimensionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleTemplateResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCreateRequest;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupUpdateRequest;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.labelgroup.LabelDimensionCatalogService;
import com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleTemplateService;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.data.collection.platform.service.labelgroup.LabelGroupService;
import com.data.collection.platform.service.labelgroup.LabelValueQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/label-groups")
public class LabelGroupController {
  private final LabelDimensionCatalogService labelDimensionCatalogService;
  private final LabelValueQueryService labelValueQueryService;
  private final LabelGroupService labelGroupService;
  private final LabelGroupExpansionService labelGroupExpansionService;
  private final LabelGroupDynamicRuleTemplateService dynamicRuleTemplateService;

  public LabelGroupController(
      LabelDimensionCatalogService labelDimensionCatalogService,
      LabelValueQueryService labelValueQueryService,
      LabelGroupService labelGroupService,
      LabelGroupExpansionService labelGroupExpansionService,
      LabelGroupDynamicRuleTemplateService dynamicRuleTemplateService) {
    this.labelDimensionCatalogService = labelDimensionCatalogService;
    this.labelValueQueryService = labelValueQueryService;
    this.labelGroupService = labelGroupService;
    this.labelGroupExpansionService = labelGroupExpansionService;
    this.dynamicRuleTemplateService = dynamicRuleTemplateService;
  }

  @GetMapping
  public ApiResponse<List<LabelGroupResponse>> listGroups(
      @RequestParam(required = false) String valueType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled) {
    return ApiResponse.success(labelGroupService.list(valueType, keyword, enabled));
  }

  @PostMapping
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<LabelGroupResponse> createGroup(@RequestBody LabelGroupCreateRequest request) {
    return ApiResponse.success(labelGroupService.create(request));
  }

  @GetMapping("/{groupId}")
  public ApiResponse<LabelGroupResponse> getGroup(@PathVariable Long groupId) {
    return ApiResponse.success(labelGroupService.get(groupId));
  }

  @PutMapping("/{groupId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<LabelGroupResponse> updateGroup(
      @PathVariable Long groupId, @RequestBody LabelGroupUpdateRequest request) {
    return ApiResponse.success(labelGroupService.update(groupId, request));
  }

  @DeleteMapping("/{groupId}")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<Void> deleteGroup(@PathVariable Long groupId) {
    labelGroupService.delete(groupId);
    return ApiResponse.success(null);
  }

  @PostMapping("/{groupId}/expand")
  public ApiResponse<LabelGroupExpansionResponse> expandGroup(
      @PathVariable Long groupId,
      @RequestParam(required = false) String valueType,
      @RequestParam(required = false) String fieldKey,
      @RequestParam(required = false) String pageKey,
      @RequestParam(required = false) String sourceInstanceId) {
    return ApiResponse.success(
        labelGroupExpansionService.expand(
            groupId, valueType, fieldKey, pageKey, sourceInstanceId));
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

  @GetMapping("/dynamic-rule-templates")
  public ApiResponse<List<LabelGroupDynamicRuleTemplateResponse>> listDynamicRuleTemplates() {
    return ApiResponse.success(dynamicRuleTemplateService.listTemplates());
  }
}
