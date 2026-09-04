package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionConfigSaveRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldConfigResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldSummaryResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionPreviewRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionPreviewResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.dropdown.DropdownOptionFieldService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下拉框选项设置 API：字段清单、配置读写、自动获取值池联想、草稿预览与字段绑定/拆分。
 *
 * <p>读取与预览收敛到管理员查看权限，保存与改绑要求管理员维护权限
 * （权限码仅授予 SUPER_ADMIN/ADMIN，见迁移 V20260903_01）。
 */
@RestController
@RequestMapping("/api/dropdown-option-fields")
public class DropdownOptionFieldController {
  private final DropdownOptionFieldService fieldService;

  public DropdownOptionFieldController(DropdownOptionFieldService fieldService) {
    this.fieldService = fieldService;
  }

  /** 全部注册字段及其绑定状态与配置推导名。 */
  @GetMapping
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_VIEW)
  public ApiResponse<List<DropdownOptionFieldSummaryResponse>> listFields() {
    return ApiResponse.success(fieldService.listFields());
  }

  /** 单字段完整配置视图（含使用者清单）。 */
  @GetMapping("/{fieldKey}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_VIEW)
  public ApiResponse<DropdownOptionFieldConfigResponse> getConfig(@PathVariable String fieldKey) {
    return ApiResponse.success(fieldService.getConfig(fieldKey));
  }

  /** 该字段自动获取值池关键词联想（手动添加输入框候选）。 */
  @GetMapping("/{fieldKey}/acquired-options")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_VIEW)
  public ApiResponse<List<String>> acquiredOptions(
      @PathVariable String fieldKey,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.success(fieldService.acquiredOptions(fieldKey, keyword, limit));
  }

  /** 草稿预览：按提交的双套规则与手动选项求值，不落存储。 */
  @PostMapping("/{fieldKey}/preview")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_VIEW)
  public ApiResponse<DropdownOptionPreviewResponse> preview(
      @PathVariable String fieldKey, @RequestBody DropdownOptionPreviewRequest request) {
    return ApiResponse.success(fieldService.preview(fieldKey, request));
  }

  /** 整体保存当前绑定配置；字段首次保存即创建配置并绑定。版本冲突返回 CONFLICT 语义。 */
  @PutMapping("/{fieldKey}")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_MANAGE)
  public ApiResponse<DropdownOptionFieldConfigResponse> saveConfig(
      @PathVariable String fieldKey,
      @RequestBody DropdownOptionConfigSaveRequest request,
      HttpServletRequest servletRequest) {
    AuthUserResponse user = AuthSessionSupport.currentUser(servletRequest);
    return ApiResponse.success("下拉框配置已保存", fieldService.saveConfig(fieldKey, request, user.username()));
  }

  /** 字段绑定/拆分：新建空白、复制当前或共用既有配置。 */
  @PutMapping("/{fieldKey}/binding")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_DROPDOWN_OPTION_MANAGE)
  public ApiResponse<DropdownOptionFieldConfigResponse> bindField(
      @PathVariable String fieldKey,
      @RequestBody DropdownOptionBindingRequest request,
      HttpServletRequest servletRequest) {
    AuthUserResponse user = AuthSessionSupport.currentUser(servletRequest);
    return ApiResponse.success("字段绑定已更新", fieldService.bindField(fieldKey, request, user.username()));
  }
}
