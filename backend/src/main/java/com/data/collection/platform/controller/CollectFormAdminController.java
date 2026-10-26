package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormDraftUpdateRequest;
import com.data.collection.platform.entity.form.admin.FormPreviewRequest;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.entity.form.admin.FormRetireRequest;
import com.data.collection.platform.entity.form.admin.FormScopeCreateRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.form.FormActorResolver;
import com.data.collection.platform.service.form.FormPublicationService;
import com.data.collection.platform.service.form.FormTemplateAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

/**
 * 表单管理端接口：字段草稿、预览、绑定、发布、恢复与退役。
 *
 * <p>全部端点要求 {@code form.template.manage}；普通填写账号直接请求返回 403。
 */
@RestController
@RequestMapping("/api/collect-forms")
@RequirePermission(PlatformPermissionCodes.FORM_TEMPLATE_MANAGE)
public class CollectFormAdminController {

  private final FormTemplateAdminService adminService;
  private final FormPublicationService publicationService;
  private final FormActorResolver actorResolver;

  public CollectFormAdminController(
      FormTemplateAdminService adminService,
      FormPublicationService publicationService,
      FormActorResolver actorResolver) {
    this.adminService = adminService;
    this.publicationService = publicationService;
    this.actorResolver = actorResolver;
  }

  @GetMapping("/templates")
  public ApiResponse<List<FormAdminViews.TemplateSummary>> templates() {
    return ApiResponse.success(adminService.templates());
  }

  @GetMapping("/template-scopes/{scopeId}")
  public ApiResponse<FormAdminViews.ScopeDetail> scope(
      @PathVariable @NotNull @Positive Long scopeId) {
    return ApiResponse.success(adminService.scope(scopeId));
  }

  @PostMapping("/template-scopes")
  public ApiResponse<FormAdminViews.ScopeDetail> createScope(
      @Valid @RequestBody FormScopeCreateRequest request, HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单范围已创建", adminService.createScope(request, actorResolver.resolve(servletRequest)));
  }

  @PutMapping("/template-scopes/{scopeId}/draft")
  public ApiResponse<FormAdminViews.DraftView> updateDraft(
      @PathVariable @NotNull @Positive Long scopeId,
      @Valid @RequestBody FormDraftUpdateRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "草稿已保存",
        adminService.updateDraft(scopeId, request, actorResolver.resolve(servletRequest)));
  }

  @PostMapping("/template-scopes/{scopeId}/preview")
  public ApiResponse<FormAdminViews.PreviewView> preview(
      @PathVariable @NotNull @Positive Long scopeId,
      @Valid @RequestBody FormPreviewRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        adminService.preview(scopeId, request.draftRevision(), actorResolver.resolve(servletRequest)));
  }

  @PostMapping("/template-scopes/{scopeId}/publications")
  public ApiResponse<FormAdminViews.PublicationView> createPublication(
      @PathVariable @NotNull @Positive Long scopeId,
      @Valid @RequestBody FormPublicationCreateRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "发布任务已创建",
        adminService.createPublication(scopeId, request, actorResolver.resolve(servletRequest)));
  }

  @PostMapping("/template-scopes/{scopeId}/retire")
  public ApiResponse<FormAdminViews.ScopeDetail> retire(
      @PathVariable @NotNull @Positive Long scopeId,
      @Valid @RequestBody FormRetireRequest request,
      HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "表单范围已退役",
        adminService.retire(
            scopeId, request.expectedCurrentVersionId(), actorResolver.resolve(servletRequest)));
  }

  @DeleteMapping("/template-scopes/{scopeId}")
  public ApiResponse<Void> deleteScope(@PathVariable @NotNull @Positive Long scopeId) {
    adminService.deleteScope(scopeId);
    return ApiResponse.success("表单范围已删除", null);
  }

  @GetMapping("/publications/{publicationId}")
  public ApiResponse<FormAdminViews.PublicationView> publication(
      @PathVariable @NotNull @Positive Long publicationId) {
    return ApiResponse.success(publicationService.publicationView(publicationId));
  }

  @PostMapping("/publications/{publicationId}/recover")
  public ApiResponse<FormAdminViews.PublicationView> recover(
      @PathVariable @NotNull @Positive Long publicationId, HttpServletRequest servletRequest) {
    return ApiResponse.success(
        "发布任务已恢复",
        publicationService.recover(publicationId, actorResolver.resolve(servletRequest)));
  }

  @GetMapping("/binding-options")
  public ApiResponse<List<FormAdminViews.BindingOption>> bindingOptions() {
    return ApiResponse.success(adminService.bindingOptions());
  }

  @GetMapping("/version-milestones")
  public ApiResponse<FormAdminViews.MilestoneAvailability> versionMilestones(
      @RequestParam @NotNull @Positive Long sourceConfigId,
      @RequestParam @NotNull @Positive Long projectId) {
    return ApiResponse.success(adminService.milestones(sourceConfigId, projectId));
  }
}
