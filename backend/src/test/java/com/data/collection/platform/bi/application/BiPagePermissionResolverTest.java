package com.data.collection.platform.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiPagePermissionResolverTest {
  /**
   * 页面 → 权限码的独立对照表。
   *
   * <p>这里刻意写死期望字符串而不是反向读取生产常量：若映射被改错，测试必须失败，而不是跟着一起变。
   */
  private static final Map<String, String[]> EXPECTED_CODES = expectedCodes();

  private final BiPagePermissionResolver resolver = new BiPagePermissionResolver();

  @Test
  void mapsEachPageToItsOwnViewAndDownloadCode() {
    for (Map.Entry<String, String[]> page : EXPECTED_CODES.entrySet()) {
      String pageKey = page.getKey();
      assertThat(resolver.viewCode(pageKey)).as("查看权限码 %s", pageKey).isEqualTo(page.getValue()[0]);
      assertThat(resolver.downloadCode(pageKey)).as("下载权限码 %s", pageKey)
          .isEqualTo(page.getValue()[1]);
    }
  }

  @Test
  void keepsViewAndDownloadCodesPairedWithinTheSamePage() {
    for (Map.Entry<String, String[]> page : EXPECTED_CODES.entrySet()) {
      String view = resolver.viewCode(page.getKey());
      String download = resolver.downloadCode(page.getKey());
      assertThat(view).endsWith(".view");
      assertThat(download).endsWith(".download");
      assertThat(download.substring(0, download.length() - ".download".length()))
          .as("同一页面的查看与下载码必须同源: %s", page.getKey())
          .isEqualTo(view.substring(0, view.length() - ".view".length()));
    }
  }

  @Test
  void rejectsUnregisteredPageKeyInsteadOfFallingThrough() {
    assertThatThrownBy(() -> resolver.viewCode("unknown-page"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown-page");
    assertThatThrownBy(() -> resolver.downloadCode(null))
        .isInstanceOf(BizException.class);
  }

  @Test
  void exposesAllPageCodesForCrossPageEndpoints() {
    List<String> expectedViews =
        EXPECTED_CODES.values().stream().map(codes -> codes[0]).toList();
    List<String> expectedDownloads =
        EXPECTED_CODES.values().stream().map(codes -> codes[1]).toList();

    assertThat(resolver.viewCodes()).containsExactlyInAnyOrderElementsOf(expectedViews);
    assertThat(resolver.downloadCodes()).containsExactlyInAnyOrderElementsOf(expectedDownloads);
    // 跨页端点按「任一页面可见」放行，因此集合必须覆盖全部页面且无越界码。
    assertThat(resolver.viewCodes()).noneMatch(code -> code.endsWith(".download"));
    assertThat(resolver.downloadCodes()).noneMatch(code -> code.endsWith(".view"));
  }

  private static Map<String, String[]> expectedCodes() {
    Map<String, String[]> codes = new LinkedHashMap<>();
    codes.put("requirements", new String[] {
        "bi.dashboard.requirements.view", "bi.dashboard.requirements.download"});
    codes.put("design", new String[] {
        "bi.dashboard.design.view", "bi.dashboard.design.download"});
    codes.put("coding", new String[] {
        "bi.dashboard.coding.view", "bi.dashboard.coding.download"});
    codes.put("unit-test", new String[] {
        "bi.dashboard.unit_test.view", "bi.dashboard.unit_test.download"});
    codes.put("integration-test", new String[] {
        "bi.dashboard.integration_test.view", "bi.dashboard.integration_test.download"});
    codes.put("system-test", new String[] {
        "bi.dashboard.system_test.view", "bi.dashboard.system_test.download"});
    codes.put("customer-issues", new String[] {
        "bi.dashboard.customer_issues.view", "bi.dashboard.customer_issues.download"});
    return codes;
  }
}
