package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.CollectFormEditContext;
import com.data.collection.platform.entity.CollectFormDetailResponse;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.CollectFormService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collect-forms")
public class CollectFormController {

  private final CollectFormService collectFormService;

  public CollectFormController(CollectFormService collectFormService) {
    this.collectFormService = collectFormService;
  }

  @GetMapping("/detail")
  public ApiResponse<CollectFormDetailResponse> detail(
      @RequestParam @NotBlank String gitlabBaseUrl,
      @RequestParam @NotNull @Positive Long projectId,
      @RequestParam @NotBlank String resourceType,
      @RequestParam @NotBlank String resourceId,
      @RequestParam @NotBlank String templateCode) {
    return ApiResponse.success(
        collectFormService.getDetail(gitlabBaseUrl, projectId, resourceType, resourceId, templateCode));
  }

  @GetMapping("/notification-payload")
  public ApiResponse<CollectFormNotificationPayloadResponse> notificationPayload(
      @RequestParam @NotBlank String gitlabBaseUrl,
      @RequestParam @NotNull @Positive Long projectId,
      @RequestParam @NotNull @Positive Long requestIid,
      @RequestParam @NotBlank String resourceType) {
    return ApiResponse.success(
        collectFormService.buildNotificationPayload(gitlabBaseUrl, projectId, requestIid, resourceType));
  }

  @PostMapping("/save")
  public ApiResponse<CollectFormDetailResponse> save(
      @Valid @RequestBody SaveRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单已保存到平台正式数据表",
        collectFormService.save(
            request.gitlabBaseUrl(),
            request.projectId(),
            request.requestIid(),
            request.resourceType(),
            request.resourceId(),
            request.templateCode(),
            request.formTitle(),
            request.reviewer(),
            request.reviewDurationMinutes(),
            request.specificationScore(),
            request.logicScore(),
            request.performanceScore(),
            request.designScore(),
            request.otherScore(),
            request.remark(),
            editContext(request.editorId(), request.editorUsername(), servletRequest)));
  }

  @PostMapping("/delete")
  public ApiResponse<Boolean> delete(
      @Valid @RequestBody DeleteRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单记录已作废",
        collectFormService.delete(
            request.gitlabBaseUrl(),
            request.projectId(),
            request.resourceType(),
            request.resourceId(),
            request.templateCode(),
            editContext(request.editorId(), request.editorUsername(), servletRequest)));
  }

  @PostMapping("/update-record")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<CollectFormDetailResponse> updateRecord(
      @Valid @RequestBody UpdateRecordRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单记录已更新",
        collectFormService.updateRecord(
            request.id(),
            request.formTitle(),
            request.reviewer(),
            request.reviewDurationMinutes(),
            request.specificationScore(),
            request.logicScore(),
            request.performanceScore(),
            request.designScore(),
            request.otherScore(),
            request.remark(),
            request.deleted(),
            editContext(request.editorId(), request.editorUsername(), servletRequest)));
  }

  public record SaveRequest(
      @NotBlank @Size(max = 255) String gitlabBaseUrl,
      @NotNull @Positive Long projectId,
      @Positive Long requestIid,
      @NotBlank @Size(max = 64) String resourceType,
      @NotBlank @Size(max = 255) String resourceId,
      @NotBlank @Size(max = 128) String templateCode,
      @Size(max = 255) String formTitle,
      @Size(max = 128) String reviewer,
      @Min(0) Integer reviewDurationMinutes,
      @Min(0) Integer specificationScore,
      @Min(0) Integer logicScore,
      @Min(0) Integer performanceScore,
      @Min(0) Integer designScore,
      @Min(0) Integer otherScore,
      String remark,
      @Size(max = 128) String editorId,
      @Size(max = 128) String editorUsername) {}

  public record DeleteRequest(
      @NotBlank @Size(max = 255) String gitlabBaseUrl,
      @NotNull @Positive Long projectId,
      @NotBlank @Size(max = 64) String resourceType,
      @NotBlank @Size(max = 255) String resourceId,
      @NotBlank @Size(max = 128) String templateCode,
      @Size(max = 128) String editorId,
      @Size(max = 128) String editorUsername) {}

  public record UpdateRecordRequest(
      @NotNull @Positive Long id,
      @Size(max = 255) String formTitle,
      @Size(max = 128) String reviewer,
      @Min(0) Integer reviewDurationMinutes,
      @Min(0) Integer specificationScore,
      @Min(0) Integer logicScore,
      @Min(0) Integer performanceScore,
      @Min(0) Integer designScore,
      @Min(0) Integer otherScore,
      String remark,
      boolean deleted,
      @Size(max = 128) String editorId,
      @Size(max = 128) String editorUsername) {}

  private CollectFormEditContext editContext(
      String editorId,
      String editorUsername,
      HttpServletRequest request) {
    return new CollectFormEditContext(
        editorId,
        editorUsername,
        clientAddress(request),
        request.getHeader("User-Agent"));
  }

  private String clientAddress(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",", 2)[0].trim();
    }
    return request.getRemoteAddr();
  }
}
