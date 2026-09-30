package com.data.collection.platform.bi.api;

import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.application.BiExcelExportService;
import com.data.collection.platform.bi.application.BiPagePermissionResolver;
import com.data.collection.platform.bi.domain.model.BiCodingPageData;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiReviewPageData;
import com.data.collection.platform.bi.domain.model.BiSystemTestPageData;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCodingSourcePort;
import com.data.collection.platform.bi.domain.source.BiCodingSource;
import com.data.collection.platform.bi.infrastructure.BiDashboardRuntimeManager;
import com.data.collection.platform.common.DownloadResponseHeaders;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.bi.application.BiCustomerIssuePageService;
import com.data.collection.platform.service.PlatformPermissionService;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** BI 六阶段页面、产品版本和 PNG 授权的薄 HTTP 网关。 */
@RestController
@RequestMapping("/api/bi")
public class BiDashboardController {
  private final BiDashboardRuntimeManager runtimeManager;
  private final BiPagePermissionResolver pagePermissionResolver;
  private final PlatformPermissionService permissionService;

  public BiDashboardController(
      BiDashboardRuntimeManager runtimeManager,
      BiPagePermissionResolver pagePermissionResolver,
      PlatformPermissionService permissionService) {
    this.runtimeManager = runtimeManager;
    this.pagePermissionResolver = pagePermissionResolver;
    this.permissionService = permissionService;
  }

  // 本层流程：先由端点级权限拦截器校验当前页面的查看权限，再在接口中完成参数转换，
  // 最后把受限参数交给 Runtime；数据查询和指标计算不在 Controller 中执行。

  /** 返回 BI 可用产品版本和默认稳定 ID。产品版本目录被全部阶段页共用，任一页面可见即可读取。 */
  @GetMapping("/versions")
  @RequirePermission(value = {
      PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_DESIGN_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_CODING_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_VIEW,
      PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_VIEW
  })
  public ApiResponse<BiProductVersionCatalog> versions() {
    // 先取得平台统一维护的产品版本目录，后续所有页面都用稳定 ID 作为查询边界。
    return ApiResponse.success(runtimeManager.runtime().versions().catalog());
  }

  /** 返回需求评审页面。 */
  @GetMapping("/requirements")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_VIEW)
  public ApiResponse<BiPageResponse<BiReviewPageData>> requirements(
      @RequestParam long productVersionId) {
    // Controller 只负责协议和路由；评审数据的读取、计算和状态判断留给 BI 应用服务。
    return ApiResponse.success(runtimeManager.page(
        "requirements", runtime -> runtime.requirements().load(productVersionId)));
  }

  /** 返回设计评审页面。 */
  @GetMapping("/design")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_DESIGN_VIEW)
  public ApiResponse<BiPageResponse<BiReviewPageData>> design(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "design", runtime -> runtime.design().load(productVersionId)));
  }

  /** 返回带日/周、来源和稳定仓库 ID 筛选的编码页面。 */
  @GetMapping("/coding")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_CODING_VIEW)
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
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_VIEW)
  public ApiResponse<BiPageResponse<BiTestQualityPageData>> unitTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "unit-test", runtime -> runtime.unitTest().load(productVersionId)));
  }

  /** 返回 CAT 集成测试页面或明确的未接入状态。 */
  @GetMapping("/integration-test")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_VIEW)
  public ApiResponse<BiPageResponse<BiTestQualityPageData>> integrationTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "integration-test", runtime -> runtime.integrationTest().load(productVersionId)));
  }

  /** 返回系统测试页面。 */
  @GetMapping("/system-test")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_VIEW)
  public ApiResponse<BiPageResponse<BiSystemTestPageData>> systemTest(
      @RequestParam long productVersionId) {
    return ApiResponse.success(runtimeManager.page(
        "system-test", runtime -> runtime.systemTest().load(productVersionId)));
  }

  /** 返回不使用产品版本范围的独立客户问题 BI 页面。 */
  @GetMapping("/customer-issues")
  @RequirePermission(PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_VIEW)
  public ApiResponse<BiPageResponse<BiCustomerIssuePageData>> customerIssues(
      @RequestParam(required = false) String milestoneBusinessKey,
      @RequestParam(required = false) String customerKind,
      @RequestParam(required = false) String customer,
      @RequestParam(required = false) String moduleKind,
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String functionKind,
      @RequestParam(required = false) String function) {
    var query = new BiCustomerIssuePageService.Query(
        milestoneBusinessKey,
        parseMemberSelection(customerKind, customer, "customer"),
        parseMemberSelection(moduleKind, module, "module"),
        parseMemberSelection(functionKind, function, "function"));
    return ApiResponse.success(runtimeManager.page(
        BiCustomerIssuePageService.PAGE_KEY,
        runtime -> runtime.customerIssues().load(query)));
  }

  /** 校验查看、下载、图表模板和来源版本后授权浏览器本地生成 PNG。 */
  @PostMapping("/download/authorize")
  @RequirePermission(value = {
      PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_DESIGN_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_CODING_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_DOWNLOAD
  })
  public ApiResponse<BiDownloadAuthorizationService.Authorization> authorizeDownload(
      @Valid @RequestBody BiDownloadAuthorizeRequest request,
      HttpServletRequest servletRequest) {
    requirePageDownloadPermission(servletRequest, request.pageKey());
    // 浏览器只申请当前图表的下载资格，不上传像素；服务端再次校验页面、模板和来源版本。
    return ApiResponse.success(runtimeManager.runtime().downloads().authorize(
        new BiDownloadAuthorizationService.Request(
            request.scope().toScope(),
            request.pageKey(),
            request.chartInstanceId(),
            request.chartTemplateId(),
            request.sourceVersion())));
  }

  /** 校验下载授权后，把前端按图表语义提取的表格序列化为标准 .xlsx 并回传下载。 */
  @PostMapping("/download/excel")
  @RequirePermission(value = {
      PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_DESIGN_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_CODING_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_DOWNLOAD,
      PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_DOWNLOAD
  })
  public ResponseEntity<byte[]> exportExcel(
      @Valid @RequestBody BiExcelExportRequest request,
      HttpServletRequest servletRequest) {
    requirePageDownloadPermission(servletRequest, request.pageKey());
    // 与 PNG 走同一道授权门：先校验页面/图表模板/来源版本，避免绕过下载权限直接取文件。
    BiDownloadAuthorizationService.Authorization authorization = runtimeManager.runtime().downloads().authorize(
        new BiDownloadAuthorizationService.Request(
            request.scope().toScope(),
            request.pageKey(),
            request.chartInstanceId(),
            request.chartTemplateId(),
            request.sourceVersion()));
    List<List<Object>> rows = request.rows() == null ? List.of() : request.rows();
    BiExcelExportService.Export export = runtimeManager.runtime().excel().export(
        request.title(),
        authorization.rangeDescription(),
        request.explanation(),
        request.headers(),
        rows);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,
            DownloadResponseHeaders.attachment(export.filename()))
        .body(export.content());
  }

  /**
   * 下载端点按请求体携带的页面身份做精确校验：必须同时具备该页的查看与下载权限。
   *
   * <p>端点注解只能表达「持有任一页面的下载权限」这一粗粒度门禁——pageKey 在请求体里，拦截器读不到；
   * 因此这里再按 pageKey 解析出该页两个权限码，用平台权限服务按当前登录用户校验。语义与拆分前的
   * {@code requireAll} 一致（下载权限生效以查看权限为前提），并保证 A 页权限无法下载 B 页图表。
   * 未登记的 pageKey 由解析器直接报错，不进入后续授权。
   *
   * <p>当前用户与拦截器同源（{@link AuthSessionSupport#currentUser}），避免两套取法产生分歧。
   */
  private void requirePageDownloadPermission(HttpServletRequest request, String pageKey) {
    AuthUserResponse user = AuthSessionSupport.currentUser(request);
    permissionService.requirePermission(user, pagePermissionResolver.viewCode(pageKey));
    permissionService.requirePermission(user, pagePermissionResolver.downloadCode(pageKey));
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

  private CustomerIssueFactQueryService.MemberSelection parseMemberSelection(
      String kind, String value, String parameter) {
    if (kind == null && value == null) {
      return CustomerIssueFactQueryService.MemberSelection.all();
    }
    if (kind == null) {
      throw invalidSelection(parameter, "缺少 kind");
    }
    return switch (kind) {
      case "ALL" -> {
        if (value != null) throw invalidSelection(parameter, "ALL 不接受 value");
        yield CustomerIssueFactQueryService.MemberSelection.all();
      }
      case "MISSING" -> {
        if (value != null) throw invalidSelection(parameter, "MISSING 不接受 value");
        yield CustomerIssueFactQueryService.MemberSelection.missing();
      }
      case "VALUE" -> {
        if (value == null || value.isBlank()) throw invalidSelection(parameter, "VALUE 必须携带非空 value");
        yield CustomerIssueFactQueryService.MemberSelection.of(value);
      }
      default -> throw invalidSelection(parameter, "kind 仅支持 ALL、MISSING、VALUE");
    };
  }

  private ResponseStatusException invalidSelection(String parameter, String detail) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "非法客户问题筛选参数 " + parameter + "：" + detail);
  }
}
