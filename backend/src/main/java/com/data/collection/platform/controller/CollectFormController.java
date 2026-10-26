package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormReviewerCandidatesResponse;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.PlatformPermissionService;
import com.data.collection.platform.service.form.FormRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一表单记录接口。
 *
 * <p>写入口按记录增量提交；旧整页 upsert/删除入口已退役，记录修改统一走本人记录端点。
 */
@RestController
@RequestMapping("/api/collect-forms")
public class CollectFormController {

  private final FormRecordService formRecordService;
  private final PlatformPermissionService permissionService;

  public CollectFormController(
      FormRecordService formRecordService, PlatformPermissionService permissionService) {
    this.formRecordService = formRecordService;
    this.permissionService = permissionService;
  }

  @GetMapping("/detail")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_CREATE)
  public ApiResponse<FormDetailResponse> detail(
      @RequestParam @NotBlank @Size(max = 255) String gitlabBaseUrl,
      @RequestParam @NotNull @Positive Long projectId,
      @RequestParam @NotBlank @Size(max = 64) String resourceType,
      @RequestParam @NotBlank @Size(max = 32) String resourceId,
      @RequestParam @NotBlank @Size(max = 64) String templateKey,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        formRecordService.detail(
            templateKey,
            gitlabBaseUrl,
            projectId,
            resourceType,
            resourceId,
            actor(servletRequest)));
  }

  @PostMapping("/records")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_CREATE)
  public ApiResponse<FormCreateResponse> create(
      @Valid @RequestBody FormCreateRequest request, HttpServletRequest servletRequest) {
    return ApiResponse.success(
        formRecordService.create(
            request,
            actor(servletRequest),
            clientAddress(servletRequest),
            servletRequest.getHeader("User-Agent")));
  }

  @PutMapping("/records/{id}")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_EDIT_OWN)
  public ApiResponse<FormRecordView> update(
      @PathVariable @NotBlank String id,
      @Valid @RequestBody FormUpdateRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单记录已更新",
        formRecordService.update(
            id,
            request,
            actor(servletRequest),
            clientAddress(servletRequest),
            servletRequest.getHeader("User-Agent")));
  }

  @PostMapping("/records/{id}/void")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_DELETE_OWN)
  public ApiResponse<FormRecordView> voidRecord(
      @PathVariable @NotBlank String id,
      @Valid @RequestBody FormVoidRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单记录已作废",
        formRecordService.voidRecord(
            id,
            request,
            actor(servletRequest),
            clientAddress(servletRequest),
            servletRequest.getHeader("User-Agent")));
  }

  @GetMapping("/reviewer-candidates")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_CREATE)
  public ApiResponse<FormReviewerCandidatesResponse> reviewerCandidates(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        formRecordService.reviewerCandidates(keyword, limit, cursor, actor(servletRequest)));
  }

  @GetMapping("/notification-payload")
  @RequirePermission(PlatformPermissionCodes.FORM_RECORD_CREATE)
  public ApiResponse<CollectFormNotificationPayloadResponse> notificationPayload(
      @RequestParam @NotBlank @Size(max = 255) String gitlabBaseUrl,
      @RequestParam @NotNull @Positive Long projectId,
      @RequestParam @NotNull @Positive Long requestIid,
      @RequestParam @NotBlank @Size(max = 64) String resourceType,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        formRecordService.notificationPayload(
            gitlabBaseUrl, projectId, requestIid, resourceType, actor(servletRequest)));
  }

  private FormActorContext actor(HttpServletRequest request) {
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    Set<String> permissions = permissionService.permissionsForRoles(user.roleCodes());
    return new FormActorContext(user.username(), user.displayName(), permissions);
  }

  private String clientAddress(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }
}
