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
import com.data.collection.platform.bi.infrastructure.BiDashboardRuntimeManager;
import com.data.collection.platform.common.exception.BizException;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BiDashboardControllerExcelExportTest {
  @Test
  void authorizesBeforeGeneratingAndReturnsXlsxAttachment() throws Exception {
    BiDashboardRuntimeManager manager = mock(BiDashboardRuntimeManager.class);
    BiDashboardRuntime runtime = mock(BiDashboardRuntime.class);
    BiDownloadAuthorizationService downloads = mock(BiDownloadAuthorizationService.class);
    when(manager.runtime()).thenReturn(runtime);
    when(runtime.downloads()).thenReturn(downloads);
    when(runtime.excel()).thenReturn(new BiExcelExportService());
    BiDashboardController controller = new BiDashboardController(manager);

    ResponseEntity<byte[]> response = controller.exportExcel(new BiExcelExportRequest(
        10L, "system-test", "developer-workload", "issue-version-3",
        "按指派人统计缺陷数", "CC2026R4", "统计各处理人员被指派的缺陷总数。",
        List.of("指派责任人", "缺陷总数"),
        List.of(List.<Object>of("张三", 10))));

    // 授权必须先于生成执行，避免绕过下载权限直接取文件。
    verify(downloads).authorize(new BiDownloadAuthorizationService.Request(
        10L, "system-test", "developer-workload", "issue-version-3"));
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
    BiDashboardController controller = new BiDashboardController(manager);

    BiExcelExportRequest request = new BiExcelExportRequest(
        10L, "system-test", "developer-workload", "stale-version",
        "按指派人统计缺陷数", "CC2026R4", null, List.of("指派责任人"), List.of());

    assertThatThrownBy(() -> controller.exportExcel(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("刷新整页");
  }
}
