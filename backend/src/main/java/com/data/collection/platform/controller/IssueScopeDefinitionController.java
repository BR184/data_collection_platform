package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.IssueScopeCatalogResponse;
import com.data.collection.platform.entity.IssueScopeCatalogSaveRequest;
import com.data.collection.platform.entity.IssueScopeDiscoveredValueResponse;
import com.data.collection.platform.entity.IssueScopeGroupResponse;
import com.data.collection.platform.entity.IssueScopeGroupSaveRequest;
import com.data.collection.platform.entity.IssueScopeMemberResponse;
import com.data.collection.platform.entity.IssueScopeMemberSaveRequest;
import com.data.collection.platform.entity.IssueScopeOrderRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.IssueScopeDefinitionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 议题范围定义管理 API。 */
@RestController
@RequestMapping("/api/issue-scopes")
public class IssueScopeDefinitionController {
  private final IssueScopeDefinitionService definitionService;

  public IssueScopeDefinitionController(IssueScopeDefinitionService definitionService) {
    this.definitionService = definitionService;
  }

  @GetMapping("/catalogs")
  public ApiResponse<List<IssueScopeCatalogResponse>> listCatalogs() {
    return ApiResponse.success(definitionService.listCatalogs());
  }

  @PostMapping("/catalogs")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeCatalogResponse> createCatalog(
      @RequestBody @Valid IssueScopeCatalogSaveRequest request) {
    return ApiResponse.success("议题范围目录已创建", definitionService.createCatalog(request));
  }

  @PutMapping("/catalogs/{id}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeCatalogResponse> updateCatalog(
      @PathVariable Long id, @RequestBody @Valid IssueScopeCatalogSaveRequest request) {
    return ApiResponse.success("议题范围目录已更新", definitionService.updateCatalog(id, request));
  }

  @PatchMapping("/catalogs/{id}/enabled")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeCatalogResponse> setCatalogEnabled(
      @PathVariable Long id, @RequestBody EnabledRequest request) {
    return ApiResponse.success(
        "议题范围目录状态已更新", definitionService.setCatalogEnabled(id, request.enabled()));
  }

  @GetMapping("/catalogs/{catalogId}/groups")
  public ApiResponse<List<IssueScopeGroupResponse>> listGroups(
      @PathVariable Long catalogId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled) {
    return ApiResponse.success(definitionService.listGroups(catalogId, keyword, enabled));
  }

  @GetMapping("/catalogs/{catalogId}/unassigned-values")
  public ApiResponse<List<IssueScopeDiscoveredValueResponse>> listUnassignedValues(
      @PathVariable Long catalogId) {
    return ApiResponse.success(definitionService.listUnassignedValues(catalogId));
  }

  @PostMapping("/groups")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeGroupResponse> createGroup(
      @RequestBody @Valid IssueScopeGroupSaveRequest request) {
    return ApiResponse.success("议题范围已创建", definitionService.createGroup(request));
  }

  @PutMapping("/groups/{id}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeGroupResponse> updateGroup(
      @PathVariable Long id, @RequestBody @Valid IssueScopeGroupSaveRequest request) {
    return ApiResponse.success("议题范围已更新", definitionService.updateGroup(id, request));
  }

  @PatchMapping("/groups/{id}/enabled")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeGroupResponse> setGroupEnabled(
      @PathVariable Long id, @RequestBody EnabledRequest request) {
    return ApiResponse.success(
        "议题范围状态已更新", definitionService.setGroupEnabled(id, request.enabled()));
  }

  @PutMapping("/catalogs/{catalogId}/group-order")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<Void> reorderGroups(
      @PathVariable Long catalogId, @RequestBody @Valid IssueScopeOrderRequest request) {
    definitionService.reorderGroups(catalogId, request.ids());
    return ApiResponse.success("议题范围顺序已更新", null);
  }

  @DeleteMapping("/groups/{id}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<Void> deleteGroup(@PathVariable Long id) {
    definitionService.deleteGroup(id);
    return ApiResponse.success("议题范围已删除", null);
  }

  @PostMapping("/members")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeMemberResponse> createMember(
      @RequestBody @Valid IssueScopeMemberSaveRequest request) {
    return ApiResponse.success("范围匹配值已创建", definitionService.createMember(request));
  }

  @PutMapping("/members/{id}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeMemberResponse> updateMember(
      @PathVariable Long id, @RequestBody @Valid IssueScopeMemberSaveRequest request) {
    return ApiResponse.success("范围匹配值已更新", definitionService.updateMember(id, request));
  }

  @PatchMapping("/members/{id}/enabled")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<IssueScopeMemberResponse> setMemberEnabled(
      @PathVariable Long id, @RequestBody EnabledRequest request) {
    return ApiResponse.success(
        "范围匹配值状态已更新", definitionService.setMemberEnabled(id, request.enabled()));
  }

  @PutMapping("/groups/{groupId}/member-order")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<Void> reorderMembers(
      @PathVariable Long groupId, @RequestBody @Valid IssueScopeOrderRequest request) {
    definitionService.reorderMembers(groupId, request.ids());
    return ApiResponse.success("范围匹配值顺序已更新", null);
  }

  @DeleteMapping("/members/{id}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_TESTING_PHASE_MANAGE)
  public ApiResponse<Void> deleteMember(@PathVariable Long id) {
    definitionService.deleteMember(id);
    return ApiResponse.success("范围匹配值已删除", null);
  }

  public record EnabledRequest(boolean enabled) {}
}

