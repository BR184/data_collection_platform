package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.PermissionRoleUpdateRequest;
import com.data.collection.platform.entity.PermissionSettingsResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.PlatformPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/permission-settings")
public class PermissionSettingsController {
  private final PlatformPermissionService permissionService;

  public PermissionSettingsController(PlatformPermissionService permissionService) {
    this.permissionService = permissionService;
  }

  @GetMapping
  @RequirePermission(PlatformPermissionCodes.SYSTEM_PERMISSION_VIEW)
  public ApiResponse<PermissionSettingsResponse> getSettings() {
    return ApiResponse.success(permissionService.loadSettings());
  }

  @PutMapping("/roles/{roleCode}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_PERMISSION_MANAGE)
  public ApiResponse<PermissionSettingsResponse> updateRolePermissions(
      @PathVariable String roleCode,
      @Valid @RequestBody PermissionRoleUpdateRequest request,
      HttpServletRequest servletRequest) {
    AuthUserResponse user = AuthSessionSupport.currentUser(servletRequest);
    permissionService.updateRolePermissions(user.username(), roleCode, request.permissionCodes());
    return ApiResponse.success("权限已保存", permissionService.loadSettings());
  }

  @PostMapping("/restore-defaults")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_PERMISSION_MANAGE)
  public ApiResponse<PermissionSettingsResponse> restoreDefaultPermissions(
      HttpServletRequest servletRequest) {
    AuthUserResponse user = AuthSessionSupport.currentUser(servletRequest);
    permissionService.restoreDefaultPermissions(user.username());
    return ApiResponse.success("已恢复默认权限", permissionService.loadSettings());
  }
}
