package com.data.collection.platform.bi.application;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.security.PlatformPermissionCodes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * BI 页面标识与页面权限码的唯一映射。
 *
 * <p>BI 的页面身份只存在于端点路径常量与下载请求体的 {@code pageKey} 中，没有可供拦截器解析的路由变量，
 * 因此无法套用统计板/分析看板的 {@code @RequirePagePermission} 范式。本类把「页面 → 该页查看权限码、
 * 下载权限码」收敛为单一事实源：查看端点注解、下载端点的页面级校验与契约测试共用同一份映射，
 * 任一页面改码或新增页面只需改这里。
 *
 * <p>未登记的 pageKey 一律抛 {@link BizException}，与 {@code PagePermissionKeyResolver.require} 同语义：
 * 宁可明确报错，也不放行一个没有权限归属的页面。
 */
@Service
public class BiPagePermissionResolver {
  /** 一个页面的查看与下载权限码。 */
  private record PagePermissions(String viewCode, String downloadCode) {}

  /** 按页面展示顺序登记全部 BI 页面；新增页面必须在此补登记，否则查看与下载都会报权限未定义。 */
  private static final Map<String, PagePermissions> PAGES = registerPages();

  /**
   * 页面查看权限码。
   *
   * @param pageKey BI 页面标识，如 {@code system-test}
   * @return 该页面的查看权限码
   * @throws BizException pageKey 未登记
   */
  public String viewCode(String pageKey) {
    return require(pageKey).viewCode();
  }

  /**
   * 页面下载权限码。下载权限生效的必要条件是该页查看权限同时生效，由调用方成对校验。
   *
   * @param pageKey BI 页面标识，如 {@code system-test}
   * @return 该页面的下载权限码
   * @throws BizException pageKey 未登记
   */
  public String downloadCode(String pageKey) {
    return require(pageKey).downloadCode();
  }

  /**
   * 全部已登记页面的查看权限码。
   *
   * <p>跨页端点（产品版本目录）与契约测试用它表达「任一页面可见即可」，避免各自硬编码页面清单。
   */
  public Set<String> viewCodes() {
    Set<String> codes = new LinkedHashSet<>();
    for (PagePermissions page : PAGES.values()) {
      codes.add(page.viewCode());
    }
    return Set.copyOf(codes);
  }

  /** 全部已登记页面的下载权限码，供下载端点的粗粒度门禁与契约测试使用。 */
  public Set<String> downloadCodes() {
    Set<String> codes = new LinkedHashSet<>();
    for (PagePermissions page : PAGES.values()) {
      codes.add(page.downloadCode());
    }
    return Set.copyOf(codes);
  }

  private PagePermissions require(String pageKey) {
    // 显式判空：不可变映射对 null 键抛 NPE，而契约要求任何未登记取值（含 null）都是明确的权限未定义。
    PagePermissions page = pageKey == null ? null : PAGES.get(pageKey);
    if (page == null) {
      throw new BizException("BI 页面权限未定义: " + pageKey);
    }
    return page;
  }

  private static Map<String, PagePermissions> registerPages() {
    Map<String, PagePermissions> pages = new LinkedHashMap<>();
    pages.put("requirements",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_REQUIREMENTS_DOWNLOAD));
    pages.put("design",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_DESIGN_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_DESIGN_DOWNLOAD));
    pages.put("coding",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_CODING_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_CODING_DOWNLOAD));
    pages.put("unit-test",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_UNIT_TEST_DOWNLOAD));
    pages.put("integration-test",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_INTEGRATION_TEST_DOWNLOAD));
    pages.put("system-test",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_SYSTEM_TEST_DOWNLOAD));
    pages.put("customer-issues",
        new PagePermissions(PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_VIEW,
            PlatformPermissionCodes.BI_DASHBOARD_CUSTOMER_ISSUES_DOWNLOAD));
    return Map.copyOf(pages);
  }
}
