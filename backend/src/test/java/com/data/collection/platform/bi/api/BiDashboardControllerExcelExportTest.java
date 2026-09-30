package com.data.collection.platform.bi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.application.BiDashboardRuntime;
import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.application.BiExcelExportService;
import com.data.collection.platform.bi.application.BiPagePermissionResolver;
import com.data.collection.platform.bi.domain.model.BiDownloadScope;
import com.data.collection.platform.bi.domain.model.BiDownloadScope.RangeType;
import com.data.collection.platform.bi.infrastructure.BiDashboardRuntimeManager;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.PlatformPermissionService;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class BiDashboardControllerExcelExportTest {
  @Test
  void authorizesBeforeGeneratingAndReturnsXlsxAttachment() throws Exception {
    BiDashboardRuntimeManager manager = mock(BiDashboardRuntimeManager.class);
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDownloadAuthorizationService downloads = mock(BiDownloadAuthorizationService.class);
    when(manager.runtime()).thenReturn(runtime);
    when(runtime.downloads()).thenReturn(downloads);
    when(runtime.excel()).thenReturn(new BiExcelExportService());
    when(downloads.authorize(any())).thenReturn(new BiDownloadAuthorizationService.Authorization(
        true, BiDownloadScope.productVersion(10L), "system-test", "system-test-assignee-workload",
        "developer-workload", "issue-version-3", "产品版本：CC2026R4"));
    BiDashboardController controller = controller(manager);

    ResponseEntity<byte[]> response = controller.exportExcel(new BiExcelExportRequest(
        new BiDownloadScopeRequest(RangeType.PRODUCT_VERSION, 10L, null, null,
            null, null, null, null, null, null),
        "system-test", "system-test-assignee-workload", "developer-workload", "issue-version-3",
        "按指派人统计缺陷数", "统计各处理人员被指派的缺陷总数。",
        List.of("指派责任人", "缺陷总数"),
        List.of(List.<Object>of("张三", 10))), new MockHttpServletRequest());

    // 授权必须先于生成执行，避免绕过下载权限直接取文件。
    verify(downloads).authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "system-test", "system-test-assignee-workload",
        "developer-workload", "issue-version-3"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("attachment")
        .contains(".xlsx");
    try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
      var sheet = workbook.getSheetAt(0);
      // 口径说明必须经控制器透传到导出服务，并占据元信息与表头之间的一行。
      assertThat(sheet.getRow(2).getCell(0).getStringCellValue())
          .isEqualTo("口径说明：统计各处理人员被指派的缺陷总数。");
      assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("张三");
    }
  }

  @Test
  void propagatesAuthorizationFailureWithoutGeneratingFile() {
    BiDashboardRuntimeManager manager = mock(BiDashboardRuntimeManager.class);
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDownloadAuthorizationService downloads = mock(BiDownloadAuthorizationService.class);
    when(manager.runtime()).thenReturn(runtime);
    when(runtime.downloads()).thenReturn(downloads);
    when(downloads.authorize(any()))
        .thenThrow(new BizException("页面已有新数据，请刷新整页后再下载"));
    BiDashboardController controller = controller(manager);

    BiExcelExportRequest request = new BiExcelExportRequest(
        new BiDownloadScopeRequest(RangeType.PRODUCT_VERSION, 10L, null, null,
            null, null, null, null, null, null),
        "system-test", "system-test-assignee-workload", "developer-workload", "stale-version",
        "按指派人统计缺陷数", null, List.of("指派责任人"), List.of());

    assertThatThrownBy(() -> controller.exportExcel(request, new MockHttpServletRequest()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("刷新整页");
  }

  @Test
  void rejectsMalformedCustomerSelectorsBeforeLoadingRuntime() {
    BiDashboardController controller = controller(mock(BiDashboardRuntimeManager.class));

    assertThatThrownBy(() -> controller.customerIssues(
        null, "UNKNOWN", "x", null, null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("kind 仅支持");
    assertThatThrownBy(() -> controller.customerIssues(
        null, "VALUE", null, null, null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("VALUE 必须携带");
    assertThatThrownBy(() -> controller.customerIssues(
        null, "ALL", "x", null, null, null, null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("ALL 不接受");
  }

  /**
   * 本测试只关注 Excel 生成与授权调用顺序，页面级权限判定由
   * {@link BiDownloadPagePermissionTest} 覆盖，因此这里用放行替身。
   */
  private static BiDashboardController controller(BiDashboardRuntimeManager manager) {
    return new BiDashboardController(
        manager, new BiPagePermissionResolver(), mock(PlatformPermissionService.class));
  }
}
