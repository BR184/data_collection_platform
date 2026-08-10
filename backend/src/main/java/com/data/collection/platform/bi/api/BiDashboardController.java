package com.data.collection.platform.bi.api;

import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.domain.model.BiCodingPageData;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiReviewPageData;
import com.data.collection.platform.bi.domain.model.BiSystemTestPageData;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCodingSourcePort;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.infrastructure.BiDashboardRuntimeManager;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BI 六阶段页面、产品版本和 PNG 授权的薄 HTTP 网关。 */
@RestController
@RequestMapping("/api/bi")
@RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_VIEW)
public class BiDashboardController {
  private final BiDashboardRuntimeManager runtimeManager;

  public BiDashboardController(BiDashboardRuntimeManager runtimeManager) {
    this.runtimeManager = runtimeManager;
  }

  // 本层流程：先由类级权限拦截器校验查看权限，再在接口中完成参数转换，
  // 最后把受限参数交给 Runtime；数据查询和指标计算不在 Controller 中执行。

  /** 返回 BI 可用产品版本和默认稳定 ID。 */
  @GetMapping("/versions")
  public ApiResponse<BiProductVersionCatalog> versions() {
    // 先取得平台统一维护的产品版本目录，后续所有页面都用稳定 ID 作为查询边界。
    return ApiResponse.success(runtimeManager.runtime().versions().catalog());
  }

  /** 返回需求评审页面。 */
  @GetMapping("/requirements")
  public ApiResponse<BiPageResponse<BiReviewPageData>> requirements(
      @RequestParam long productVersionId) {
    // Controller 只负责协议和路由；评审数据的读取、计算和状态判断留给 BI 应用服务。
    return ApiResponse.success(runtimeManager.page(
        "requirements", runtime -> runtime.requirements().load(productVersionId)));
  }

  /** 返回设计评审页面。 */
  @GetMapping("/design")
  public ApiResponse<BiPageResponse<BiReviewPageData>> design(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "design", runtime -> runtime.design().load(productVersionId)));
  }

  /** 返回带日/周、来源和稳定仓库 ID 筛选的编码页面。 */
  @GetMapping("/coding")
  public ApiResponse<BiPageResponse<BiCodingPageData>> coding(
      @RequestParam long productVersionId,
      @RequestParam(defaultValue = "day") String granularity,
      @RequestParam(defaultValue = "all") String source,
      @RequestParam(required = false) String repositoryId) {
    // 筛选值在进入领域层前转换为受限枚举，避免页面字符串直接参与业务计算。
    BiCodingSourcePort.Query query = new BiCodingSourcePort.Query(
        parseGranularity(granularity), parseSource(source), repositoryId);
    return ApiResponse.success(runtimeManager.page(
        "coding", runtime -> runtime.coding().load(productVersionId, query)));
  }

  /** 返回 CAT 单元测试页面或明确的未接入状态。 */
  @GetMapping("/unit-test")
  public ApiResponse<BiPageResponse<BiTestQualityPageData>> unitTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "unit-test", runtime -> runtime.unitTest().load(productVersionId)));
  }

  /** 返回 CAT 集成测试页面或明确的未接入状态。 */
  @GetMapping("/integration-test")
  public ApiResponse<BiPageResponse<BiTestQualityPageData>> integrationTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "integration-test", runtime -> runtime.integrationTest().load(productVersionId)));
  }

  /** 返回系统测试页面。 */
  @GetMapping("/system-test")
  public ApiResponse<BiPageResponse<BiSystemTestPageData>> systemTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "system-test", runtime -> runtime.systemTest().load(productVersionId)));
  }

  /** 校验查看、下载、图表模板和来源版本后授权浏览器本地生成 PNG。 */
  @PostMapping("/download/authorize")
  @RequirePermission(
      value = {
          PlatformPermissionCodes.BI_DASHBOARD_VIEW,
          PlatformPermissionCodes.BI_DASHBOARD_DOWNLOAD
      },
      requireAll = true)
  public ApiResponse<BiDownloadAuthorizationService.Authorization> authorizeDownload(
      @Valid @RequestBody BiDownloadAuthorizeRequest request) {
    // 浏览器只申请当前图表的下载资格，不上传像素；服务端再次校验页面、模板和来源版本。
    return ApiResponse.success(runtimeManager.runtime().downloads().authorize(
        new BiDownloadAuthorizationService.Request(
            request.productVersionId(),
            request.pageKey(),
            request.chartTemplateId(),
            request.sourceVersion())));
  }

  private BiCodingSource.Granularity parseGranularity(String value) {
    return switch (normalize(value)) {
      case "day" -> BiCodingSource.Granularity.DAY;
      case "week" -> BiCodingSource.Granularity.WEEK;
      default -> throw new IllegalArgumentException("granularity 仅支持 day 或 week");
    };
  }

  private BiCodingSourcePort.Source parseSource(String value) {
    return switch (normalize(value)) {
      case "all" -> BiCodingSourcePort.Source.ALL;
      case "cc" -> BiCodingSourcePort.Source.CC;
      case "dgm" -> BiCodingSourcePort.Source.DGM;
      default -> throw new IllegalArgumentException("source 仅支持 all、cc 或 dgm");
    };
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
