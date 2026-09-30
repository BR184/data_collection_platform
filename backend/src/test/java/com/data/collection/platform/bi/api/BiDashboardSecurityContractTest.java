package com.data.collection.platform.bi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.application.BiPagePermissionResolver;
import com.data.collection.platform.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiDashboardSecurityContractTest {
  /** 端点方法名 → 该端点服务的 BI 页面。产品版本目录被全部页面共用，用 null 表达「任一页面可见」。 */
  private static final Map<String, String> PAGE_BY_ENDPOINT = Map.of(
      "requirements", "requirements",
      "design", "design",
      "coding", "coding",
      "unitTest", "unit-test",
      "integrationTest", "integration-test",
      "systemTest", "system-test",
      "customerIssues", "customer-issues");

  private final BiPagePermissionResolver resolver = new BiPagePermissionResolver();

  @Test
  void hasNoControllerLevelPermissionGate() {
    // BI 看板不保留模块级总开关：权限只按页面授予，避免出现「有模块码无页面码」的空菜单中间态。
    assertThat(BiDashboardController.class.getAnnotation(RequirePermission.class)).isNull();
  }

  @Test
  void requiresEachPageItsOwnViewPermission() {
    for (Map.Entry<String, String> endpoint : PAGE_BY_ENDPOINT.entrySet()) {
      RequirePermission permission = methodPermission(endpoint.getKey());
      assertThat(permission.value())
          .as("%s 只能要求本页查看权限", endpoint.getKey())
          .containsExactly(resolver.viewCode(endpoint.getValue()));
    }
  }

  @Test
  void requiresAnyPageViewPermissionForSharedProductVersionCatalog() {
    RequirePermission permission = methodPermission("versions");

    assertThat(permission.requireAll()).isFalse();
    assertThat(permission.value())
        .as("产品版本目录由全部阶段页共用，登记页面的集合必须与解析器一致")
        .containsExactlyInAnyOrderElementsOf(resolver.viewCodes());
  }

  @Test
  void requiresAnyPageDownloadPermissionOnDownloadEndpoints() {
    for (String endpoint : new String[] {"authorizeDownload", "exportExcel"}) {
      RequirePermission permission = methodPermission(endpoint);

      assertThat(permission.requireAll())
          .as("%s 的页面精确校验在方法内完成，注解只做粗粒度门禁", endpoint)
          .isFalse();
      assertThat(permission.value())
          .as("%s 必须与解析器的下载码集合一致", endpoint)
          .containsExactlyInAnyOrderElementsOf(resolver.downloadCodes());
    }
  }

  private RequirePermission methodPermission(String methodName) {
    Method method = Arrays.stream(BiDashboardController.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("缺少端点方法: " + methodName));
    RequirePermission permission = method.getAnnotation(RequirePermission.class);
    assertThat(permission).as("%s 必须声明权限注解", methodName).isNotNull();
    return permission;
  }
}
