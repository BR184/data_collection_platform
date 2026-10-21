package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.read.ReadViewMetadata;
import com.data.collection.platform.service.read.ReadViewApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 读取身份资源；权限按请求页面程序化核验，不增加独立业务授权或允许直接指定发布版本。 */
@RestController
@RequestMapping("/api/read-views")
public class ReadViewController {
  private final ReadViewApplicationService application;

  public ReadViewController(ReadViewApplicationService application) { this.application = application; }

  /** 获取完整页面身份，Location 指向本次建立的租约资源。 */
  @PostMapping
  public ResponseEntity<ApiResponse<ReadViewMetadata>> acquire(HttpServletRequest request, @Valid @RequestBody CreateView body) {
    var view = application.acquire(request, body.pageKey(), body.scope(), body.clientViewId());
    return ResponseEntity.created(java.net.URI.create("/api/read-views/" + view.viewId()))
        .body(ApiResponse.success(application.metadata(request, view)));
  }

  /** 可见页面续约；跨会话/跨账号拒绝，达到安全换版边界返回结构化 409。 */
  @PostMapping("/{id}/renew")
  public ApiResponse<ReadViewMetadata> renew(HttpServletRequest request, @PathVariable UUID id) {
    return ApiResponse.success(application.metadata(request, application.renew(request, id)));
  }

  /** 下钻获得独立租约；目标页当前权限及来源闭包单独核验。 */
  @PostMapping("/{id}/fork")
  public ResponseEntity<ApiResponse<ReadViewMetadata>> fork(HttpServletRequest request, @PathVariable UUID id,
      @Valid @RequestBody ForkView body) {
    var view = application.fork(request, id, body.pageKey(), body.scope(), body.clientViewId());
    return ResponseEntity.created(java.net.URI.create("/api/read-views/" + view.viewId()))
        .body(ApiResponse.success(application.metadata(request, view)));
  }

  /** 下钻仍只允许业务范围及幂等准备键，发布点来自本人已有完整视图。 */
  public record ForkView(@NotNull UUID clientViewId, @Size(max = 128) String pageKey,
      @Size(max = 16) Map<@Size(max = 64) String, @Size(max = 512) String> scope) {}

  /** 幂等释放本人页面租约，缺失/已释放时也返回 204。 */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> release(HttpServletRequest request, @PathVariable UUID id) {
    application.release(request, id);
    return ResponseEntity.noContent().build();
  }

  /** 页面准备输入不包含 publicationId/candidateId；范围只允许服务端目录登记的业务键。 */
  public record CreateView(@NotBlank @Size(max = 128) String pageKey, @NotNull UUID clientViewId,
      @Size(max = 16) Map<@Size(max = 64) String, @Size(max = 512) String> scope) {}
}
